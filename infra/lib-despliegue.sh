#!/usr/bin/env bash
# =============================================================================
#  Librería común del deployment pipeline
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Centraliza las operaciones que comparten los scripts de despliegue:
#  levantar y detener instancias, verificar salud, ajustar el enrutamiento y
#  registrar la bitácora de auditoría.
#
#  Concentrar esta lógica en un solo archivo evita el anti-patrón de
#  "dependencias implícitas" (ME_5 §1.2.3): ningún script asume recursos o
#  configuraciones previas sin declararlas explícitamente.
# =============================================================================

set -uo pipefail

# --- Rutas base, resueltas siempre respecto de la ubicación del script -------
DIR_INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIR_RAIZ="$(cd "${DIR_INFRA}/.." && pwd)"
DIR_ESTADO="${DIR_INFRA}/estado"
DIR_LOGS="${DIR_ESTADO}/logs"
ARCHIVO_ROUTER="${DIR_ESTADO}/router.json"
HISTORIAL="${DIR_ESTADO}/historial-despliegues.log"

# --- Puertos del ambiente de pruebas ----------------------------------------
PUERTO_ROUTER="${PUERTO_ROUTER:-9080}"    # Punto de entrada estable (balanceador)
PUERTO_AZUL="${PUERTO_AZUL:-9081}"        # Entorno Blue  (versión en producción)
PUERTO_VERDE="${PUERTO_VERDE:-9082}"      # Entorno Green (versión candidata)
PUERTO_STAGING="${PUERTO_STAGING:-9083}"  # Ambiente de staging / acceptance gate

# --- Parámetros de los health checks ----------------------------------------
INTENTOS_HEALTH="${INTENTOS_HEALTH:-15}"
ESPERA_HEALTH="${ESPERA_HEALTH:-1}"

# =============================================================================
#  Registro con marca de tiempo. Toda salida queda además en el historial,
#  que constituye la evidencia auditable del despliegue (ME_6 §5.3).
# =============================================================================
_marca() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

log()       { local m="[$(_marca)] $*";        echo "$m";        _historial "$m"; }
log_paso()  { local m="[$(_marca)] ==> $*";    echo ""; echo "$m"; _historial "$m"; }
log_ok()    { local m="[$(_marca)] [  OK  ] $*";  echo "$m";     _historial "$m"; }
log_error() { local m="[$(_marca)] [ERROR ] $*";  echo "$m" >&2; _historial "$m"; }
log_alerta(){ local m="[$(_marca)] [ALERTA] $*";  echo "$m";     _historial "$m"; }

_historial() {
    mkdir -p "${DIR_ESTADO}"
    echo "$1" >> "${HISTORIAL}"
}

# =============================================================================
#  Prepara los directorios de estado del pipeline.
# =============================================================================
preparar_estado() {
    mkdir -p "${DIR_ESTADO}" "${DIR_LOGS}"
}

# =============================================================================
#  Localiza el artefacto desplegable.
#
#  El pipeline promueve SIEMPRE el mismo .jar construido en la etapa de commit
#  ("build once, deploy many"): nunca se recompila entre ambientes, porque eso
#  introduciría diferencias entre lo probado y lo desplegado.
# =============================================================================
ubicar_artefacto() {
    local jar="${DIR_RAIZ}/target/reservas-automatizacion.jar"
    if [[ -f "${jar}" ]]; then
        echo "${jar}"
        return 0
    fi
    # Respaldo: ejecutar desde las clases compiladas (útil sin Maven disponible)
    if [[ -d "${DIR_RAIZ}/target/classes" ]]; then
        echo "CLASSES:${DIR_RAIZ}/target/classes"
        return 0
    fi
    return 1
}

# =============================================================================
#  Levanta una instancia de la aplicación.
#
#  Parámetros: nombre puerto version color [simular_fallo]
#
#  La configuración se inyecta por variables de entorno y el artefacto no se
#  modifica jamás: es la separación entre código y configuración de entorno
#  descrita en el Módulo de Estudio 2.
# =============================================================================
iniciar_instancia() {
    local nombre="$1" puerto="$2" version="$3" color="$4" fallo="${5:-false}"
    local fallo_tras="${6:-0}"
    preparar_estado

    local artefacto
    if ! artefacto="$(ubicar_artefacto)"; then
        log_error "No se encontró el artefacto desplegable. Ejecute antes: mvn package"
        return 1
    fi

    local log_instancia="${DIR_LOGS}/${nombre}.log"

    if [[ "${artefacto}" == CLASSES:* ]]; then
        SRT_PUERTO="${puerto}" SRT_VERSION="${version}" SRT_COLOR="${color}" \
        SRT_SIMULAR_FALLO="${fallo}" SRT_FALLO_TRAS_SEGUNDOS="${fallo_tras}" \
            nohup java -cp "${artefacto#CLASSES:}" com.iplacex.qa.reservas.api.Main \
            > "${log_instancia}" 2>&1 &
    else
        SRT_PUERTO="${puerto}" SRT_VERSION="${version}" SRT_COLOR="${color}" \
        SRT_SIMULAR_FALLO="${fallo}" SRT_FALLO_TRAS_SEGUNDOS="${fallo_tras}" \
            nohup java -jar "${artefacto}" > "${log_instancia}" 2>&1 &
    fi

    local pid=$!
    echo "${pid}" > "${DIR_ESTADO}/${nombre}.pid"
    log "Instancia '${nombre}' iniciada | puerto=${puerto} version=${version} color=${color} pid=${pid}"
    return 0
}

# =============================================================================
#  Detiene una instancia de forma ordenada, liberando su puerto.
# =============================================================================
detener_instancia() {
    local nombre="$1"
    local archivo_pid="${DIR_ESTADO}/${nombre}.pid"

    if [[ -f "${archivo_pid}" ]]; then
        local pid
        pid="$(cat "${archivo_pid}")"
        if kill -0 "${pid}" 2>/dev/null; then
            kill "${pid}" 2>/dev/null
            sleep 1
            kill -9 "${pid}" 2>/dev/null || true
            log "Instancia '${nombre}' detenida (pid=${pid})"
        fi
        rm -f "${archivo_pid}"
    fi
}

# =============================================================================
#  HEALTH CHECK: consulta /health y exige un 200 con estado UP.
#
#  Es el criterio objetivo que decide entre promover una versión o ejecutar un
#  rollback. No se limita al código HTTP: valida además el contenido de la
#  respuesta, tal como recomienda el ME_6 §3.3 ("health checks exhaustivos").
# =============================================================================
health_check() {
    local puerto="$1"
    local respuesta codigo

    respuesta="$(curl -s --noproxy '*' -m 5 -w '\n%{http_code}' \
                 "http://localhost:${puerto}/health" 2>/dev/null)" || return 1
    codigo="$(echo "${respuesta}" | tail -n1)"
    local cuerpo
    cuerpo="$(echo "${respuesta}" | sed '$d')"

    if [[ "${codigo}" == "200" ]] && echo "${cuerpo}" | grep -q '"estado":"UP"'; then
        return 0
    fi

    ULTIMO_CUERPO_HEALTH="${cuerpo}"
    ULTIMO_CODIGO_HEALTH="${codigo}"
    return 1
}

# =============================================================================
#  Espera a que una instancia quede saludable, con reintentos acotados.
#  Un timeout razonable impide que el gate bloquee el pipeline indefinidamente.
# =============================================================================
esperar_saludable() {
    local puerto="$1" etiqueta="${2:-instancia}"
    local intento=1

    while (( intento <= INTENTOS_HEALTH )); do
        if health_check "${puerto}"; then
            log_ok "Health check de ${etiqueta} superado en el intento ${intento} (puerto ${puerto})"
            return 0
        fi
        sleep "${ESPERA_HEALTH}"
        (( intento++ ))
    done

    log_error "Health check de ${etiqueta} FALLIDO tras ${INTENTOS_HEALTH} intentos (puerto ${puerto})"
    [[ -n "${ULTIMO_CODIGO_HEALTH:-}" ]] && log_error "  Código HTTP: ${ULTIMO_CODIGO_HEALTH}"
    [[ -n "${ULTIMO_CUERPO_HEALTH:-}" ]] && log_error "  Respuesta  : ${ULTIMO_CUERPO_HEALTH}"
    return 1
}

# =============================================================================
#  Ajusta el enrutamiento del balanceador en caliente.
#  Parámetros: puerto_primario [puerto_canary] [peso_canary]
# =============================================================================
configurar_router() {
    local primario="$1" canary="${2:-0}" peso="${3:-0}"
    preparar_estado

    cat > "${ARCHIVO_ROUTER}" <<EOF
{
  "puertoPrimario": ${primario},
  "puertoCanary":   ${canary},
  "pesoCanary":     ${peso},
  "actualizado":    "$(_marca)"
}
EOF
    log "Enrutamiento actualizado | primario=${primario} canary=${canary} peso=${peso}%"
}

# =============================================================================
#  Levanta el balanceador que expone el punto de entrada estable del ambiente.
# =============================================================================
iniciar_router() {
    preparar_estado
    [[ -f "${ARCHIVO_ROUTER}" ]] || configurar_router "${PUERTO_AZUL}"

    nohup java "${DIR_INFRA}/Router.java" "${PUERTO_ROUTER}" "${ARCHIVO_ROUTER}" \
        > "${DIR_LOGS}/router.log" 2>&1 &

    echo $! > "${DIR_ESTADO}/router.pid"
    sleep 2
    log "Balanceador levantado en el puerto ${PUERTO_ROUTER}"
}

detener_router() {
    detener_instancia "router"
}

# =============================================================================
#  Mide el reparto REAL del tráfico enviando N peticiones al balanceador.
#
#  Es la verificación objetiva de que el peso configurado para el Canary se
#  está aplicando de verdad, y no solo declarado en un archivo.
# =============================================================================
medir_distribucion() {
    local total="${1:-100}"
    local a_canary=0 a_primario=0 i

    for (( i = 0; i < total; i++ )); do
        local destino
        destino="$(curl -s --noproxy '*' -m 5 -D - -o /dev/null \
                   "http://localhost:${PUERTO_ROUTER}/version" 2>/dev/null \
                   | grep -i '^X-Ruteado-A:' | tr -d '\r' | awk '{print $2}')"
        if [[ "${destino}" == "canary" ]]; then
            (( a_canary++ ))
        else
            (( a_primario++ ))
        fi
    done

    local pct_canary=$(( a_canary * 100 / total ))
    log "Distribución medida sobre ${total} peticiones: primario=${a_primario} canary=${a_canary} (${pct_canary}% al canary)"
    DISTRIBUCION_CANARY="${pct_canary}"
}

# =============================================================================
#  Consulta la versión que el balanceador está sirviendo actualmente.
# =============================================================================
version_en_produccion() {
    curl -s --noproxy '*' -m 5 "http://localhost:${PUERTO_ROUTER}/version" 2>/dev/null
}

# =============================================================================
#  Detiene TODO el ambiente. Se invoca al finalizar cada demostración para
#  dejar el entorno limpio y evitar contaminación entre ejecuciones.
# =============================================================================
limpiar_ambiente() {
    log_paso "Limpiando el ambiente de pruebas"
    for nombre in azul verde canary staging router; do
        detener_instancia "${nombre}"
    done
    log_ok "Ambiente limpio: todos los puertos liberados"
}
