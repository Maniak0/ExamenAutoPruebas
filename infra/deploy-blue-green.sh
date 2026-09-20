#!/usr/bin/env bash
# =============================================================================
#  ACTIVIDAD 3 - DESPLIEGUE BLUE-GREEN CON ROLLBACK AUTOMÁTICO
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Implementa el patrón Blue-Green descrito en el Módulo de Estudio 6 §3.1:
#
#    1. Se mantienen DOS entornos idénticos: Blue (activo) y Green (candidato).
#    2. La versión nueva se despliega en el entorno INACTIVO, sin tocar el que
#       está atendiendo usuarios.
#    3. Se ejecutan health checks y smoke tests contra el entorno candidato.
#    4. Solo si todo pasa, el balanceador "switchea" el tráfico. El cambio es
#       instantáneo y sin downtime.
#    5. El entorno anterior queda en STAND-BY, lo que hace que el rollback sea
#       trivial: basta con volver a apuntar el balanceador.
#
#  VENTANA DE MONITOREO: tras el switch el script vigila la salud de la versión
#  nueva durante unos segundos. Si detecta degradación, ejecuta un ROLLBACK
#  AUTOMÁTICO devolviendo el tráfico a la versión estable anterior. Esto cubre
#  el escenario en que "el pipeline pasa" pero la versión falla con tráfico real.
#
#  USO:
#    ./infra/deploy-blue-green.sh --version 1.1.0
#    ./infra/deploy-blue-green.sh --version 2.0.0 --fallo-tras 4   (demo rollback)
# =============================================================================

set -uo pipefail

DIR_INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-despliegue.sh
source "${DIR_INFRA}/lib-despliegue.sh"

# --- Parámetros ---------------------------------------------------------------
VERSION_NUEVA="1.1.0"
SIMULAR_FALLO="false"
FALLO_TRAS=0
SEGUNDOS_MONITOREO="${SEGUNDOS_MONITOREO:-8}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)      VERSION_NUEVA="$2"; shift 2 ;;
        --simular-fallo) SIMULAR_FALLO="true"; shift ;;
        --fallo-tras)   FALLO_TRAS="$2"; shift 2 ;;
        --monitoreo)    SEGUNDOS_MONITOREO="$2"; shift 2 ;;
        *) echo "Parámetro desconocido: $1"; exit 2 ;;
    esac
done

echo "============================================================================"
echo "  DEPLOYMENT PIPELINE - ESTRATEGIA BLUE-GREEN"
echo "  Versión candidata: ${VERSION_NUEVA}"
echo "============================================================================"

preparar_estado

# =============================================================================
#  PASO 1: Identificar cuál entorno está activo y cuál queda como candidato
# =============================================================================
log_paso "PASO 1/6 - Identificando el entorno activo"

PUERTO_ACTIVO="$(grep -o '"puertoPrimario":[[:space:]]*[0-9]*' "${ARCHIVO_ROUTER}" 2>/dev/null \
                 | grep -o '[0-9]*$')"
PUERTO_ACTIVO="${PUERTO_ACTIVO:-${PUERTO_AZUL}}"

if [[ "${PUERTO_ACTIVO}" == "${PUERTO_AZUL}" ]]; then
    COLOR_ACTIVO="azul";  PUERTO_CANDIDATO="${PUERTO_VERDE}"; COLOR_CANDIDATO="verde"
else
    COLOR_ACTIVO="verde"; PUERTO_CANDIDATO="${PUERTO_AZUL}";  COLOR_CANDIDATO="azul"
fi

log "Entorno ACTIVO    : ${COLOR_ACTIVO}  (puerto ${PUERTO_ACTIVO})  <- atiende usuarios"
log "Entorno CANDIDATO : ${COLOR_CANDIDATO} (puerto ${PUERTO_CANDIDATO})  <- recibirá la versión nueva"

VERSION_ANTERIOR="$(curl -s --noproxy '*' -m 5 "http://localhost:${PUERTO_ACTIVO}/version" 2>/dev/null \
                    | grep -o '"version":"[^"]*"' | cut -d'"' -f4)"
VERSION_ANTERIOR="${VERSION_ANTERIOR:-desconocida}"
log "Versión actualmente en producción: ${VERSION_ANTERIOR}"

# =============================================================================
#  PASO 2: Desplegar la versión nueva en el entorno inactivo
#  Los usuarios siguen siendo atendidos por el entorno activo: riesgo cero.
# =============================================================================
log_paso "PASO 2/6 - Desplegando ${VERSION_NUEVA} en el entorno ${COLOR_CANDIDATO}"

detener_instancia "${COLOR_CANDIDATO}"
if ! iniciar_instancia "${COLOR_CANDIDATO}" "${PUERTO_CANDIDATO}" "${VERSION_NUEVA}" \
                       "${COLOR_CANDIDATO}" "${SIMULAR_FALLO}" "${FALLO_TRAS}"; then
    log_error "No fue posible iniciar el entorno candidato. Despliegue abortado."
    exit 1
fi

# =============================================================================
#  PASO 3: GATE DE SALUD - health check contra el entorno candidato
# =============================================================================
log_paso "PASO 3/6 - Verificando la salud del entorno ${COLOR_CANDIDATO}"

if ! esperar_saludable "${PUERTO_CANDIDATO}" "entorno ${COLOR_CANDIDATO}"; then
    log_error "GATE NO SUPERADO: la versión ${VERSION_NUEVA} no está sana."
    log_alerta "ROLLBACK PREVENTIVO: el tráfico NUNCA se movió del entorno ${COLOR_ACTIVO}."
    detener_instancia "${COLOR_CANDIDATO}"
    log_ok "Producción intacta, sirviendo la versión ${VERSION_ANTERIOR} sin interrupción"
    echo ""
    echo "RESULTADO: DESPLIEGUE RECHAZADO POR EL GATE DE SALUD"
    exit 1
fi

# =============================================================================
#  PASO 4: SMOKE TESTS contra el candidato, antes de exponerlo a usuarios
# =============================================================================
log_paso "PASO 4/6 - Ejecutando smoke tests sobre el entorno ${COLOR_CANDIDATO}"

if ! "${DIR_INFRA}/smoke-test.sh" "${PUERTO_CANDIDATO}"; then
    log_error "SMOKE TESTS FALLIDOS sobre la versión ${VERSION_NUEVA}"
    log_alerta "ROLLBACK PREVENTIVO: se descarta el candidato sin mover el tráfico."
    detener_instancia "${COLOR_CANDIDATO}"
    log_ok "Producción intacta, sirviendo la versión ${VERSION_ANTERIOR}"
    echo ""
    echo "RESULTADO: DESPLIEGUE RECHAZADO POR LOS SMOKE TESTS"
    exit 1
fi

# =============================================================================
#  PASO 5: SWITCH DE TRÁFICO - instantáneo y sin downtime
# =============================================================================
log_paso "PASO 5/6 - Conmutando el tráfico de ${COLOR_ACTIVO} a ${COLOR_CANDIDATO}"

configurar_router "${PUERTO_CANDIDATO}"
sleep 1

RESPUESTA_TRAS_SWITCH="$(version_en_produccion)"
log "El balanceador ahora responde: ${RESPUESTA_TRAS_SWITCH}"

if ! echo "${RESPUESTA_TRAS_SWITCH}" | grep -q "\"version\":\"${VERSION_NUEVA}\""; then
    log_error "El switch no se reflejó correctamente en el balanceador"
    configurar_router "${PUERTO_ACTIVO}"
    log_alerta "ROLLBACK INMEDIATO ejecutado: tráfico devuelto al entorno ${COLOR_ACTIVO}"
    exit 1
fi

log_ok "Switch completado sin downtime. La versión ${VERSION_NUEVA} atiende a los usuarios."
log "El entorno ${COLOR_ACTIVO} (versión ${VERSION_ANTERIOR}) queda en STAND-BY para rollback inmediato"

# =============================================================================
#  PASO 6: VENTANA DE MONITOREO POST-SWITCH
#  Aquí se detectan los defectos latentes que ningún gate previo pudo ver.
# =============================================================================
log_paso "PASO 6/6 - Monitoreando la versión ${VERSION_NUEVA} durante ${SEGUNDOS_MONITOREO}s"

FALLOS_CONSECUTIVOS=0
UMBRAL_FALLOS=2      # Dos fallos seguidos descartan una intermitencia puntual

for (( segundo = 1; segundo <= SEGUNDOS_MONITOREO; segundo++ )); do
    if health_check "${PUERTO_CANDIDATO}"; then
        FALLOS_CONSECUTIVOS=0
        log "  monitoreo t+${segundo}s: instancia sana"
    else
        (( FALLOS_CONSECUTIVOS++ ))
        log_alerta "  monitoreo t+${segundo}s: HEALTH CHECK FALLIDO (${FALLOS_CONSECUTIVOS}/${UMBRAL_FALLOS})"

        if (( FALLOS_CONSECUTIVOS >= UMBRAL_FALLOS )); then
            echo ""
            log_error "================================================================"
            log_error " DEGRADACIÓN CRÍTICA DETECTADA EN PRODUCCIÓN"
            log_error " Versión afectada: ${VERSION_NUEVA} (entorno ${COLOR_CANDIDATO})"
            log_error "================================================================"

            # ---------------- ROLLBACK AUTOMÁTICO ----------------
            log_paso "ROLLBACK AUTOMÁTICO - restaurando la versión ${VERSION_ANTERIOR}"

            # El entorno anterior nunca se apagó: el rollback es instantáneo.
            configurar_router "${PUERTO_ACTIVO}"
            sleep 1

            if health_check "${PUERTO_ACTIVO}"; then
                log_ok "Tráfico devuelto al entorno ${COLOR_ACTIVO} (versión ${VERSION_ANTERIOR})"
            else
                log_error "El entorno de respaldo tampoco responde: se requiere intervención manual"
                exit 1
            fi

            VERIFICACION="$(version_en_produccion)"
            log "Verificación post-rollback: ${VERIFICACION}"

            detener_instancia "${COLOR_CANDIDATO}"
            log "Instancia defectuosa ${COLOR_CANDIDATO} retirada del ambiente"

            # Notificación al equipo (ME_6 §1.1): en una instalación real aquí
            # se invocaría a Slack, Teams o Alertmanager.
            log_alerta "NOTIFICACIÓN -> canal #deploy-alerts: rollback automático ejecutado"
            log_alerta "  De  : ${VERSION_NUEVA} (fallida)"
            log_alerta "  A   : ${VERSION_ANTERIOR} (estable)"
            log_alerta "  Downtime para el usuario final: ninguno"

            echo ""
            echo "============================================================================"
            echo "  RESULTADO: ROLLBACK AUTOMÁTICO COMPLETADO"
            echo "  Producción sirviendo nuevamente la versión ${VERSION_ANTERIOR}"
            echo "============================================================================"
            exit 3
        fi
    fi
    sleep 1
done

# =============================================================================
#  DESPLIEGUE CONSOLIDADO
# =============================================================================
echo ""
log_ok "Ventana de monitoreo superada sin incidentes"
log "Registro de auditoría: ${HISTORIAL}"

echo ""
echo "============================================================================"
echo "  RESULTADO: DESPLIEGUE BLUE-GREEN EXITOSO"
echo "  Versión en producción : ${VERSION_NUEVA}  (entorno ${COLOR_CANDIDATO})"
echo "  Versión en stand-by   : ${VERSION_ANTERIOR}  (entorno ${COLOR_ACTIVO})"
echo "  Downtime              : ninguno"
echo "============================================================================"
exit 0
