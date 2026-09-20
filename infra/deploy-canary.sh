#!/usr/bin/env bash
# =============================================================================
#  ACTIVIDAD 3 - DESPLIEGUE CANARY PROGRESIVO CON ROLLBACK PARCIAL
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Implementa el patrón Canary Release del Módulo de Estudio 6 §3.2:
#
#    1. La versión nueva se despliega junto a la estable y recibe solo una
#       fracción pequeña del tráfico (10%).
#    2. Se monitorean métricas y health checks en cada escalón.
#    3. Si el comportamiento es correcto, el porcentaje aumenta de forma
#       progresiva: 10% -> 25% -> 50% -> 100%.
#    4. Ante cualquier anomalía se ejecuta un ROLLBACK PARCIAL: el peso del
#       canary se lleva a 0 y la versión estable recupera todo el tráfico.
#
#  Diferencia clave con Blue-Green: aquí el riesgo se acota desde el inicio,
#  porque una versión defectuosa afecta únicamente al 10% de los usuarios
#  durante un lapso breve, en lugar de al 100% de forma instantánea.
#
#  USO:
#    ./infra/deploy-canary.sh --version 1.2.0
#    ./infra/deploy-canary.sh --version 3.0.0 --fallo-tras 6   (demo rollback)
# =============================================================================

set -uo pipefail

DIR_INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-despliegue.sh
source "${DIR_INFRA}/lib-despliegue.sh"

VERSION_CANARY="1.2.0"
SIMULAR_FALLO="false"
FALLO_TRAS=0
PUERTO_CANARY="${PUERTO_CANARY:-9084}"
ESCALONES=(10 25 50 100)
# Tamaño de la muestra con que se mide el reparto real del tráfico.
# Una muestra pequeña arroja porcentajes engañosos por simple azar estadístico:
# con 40 peticiones, un peso configurado del 10% puede medirse como 17%.
PETICIONES_MUESTRA="${PETICIONES_MUESTRA:-200}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)       VERSION_CANARY="$2"; shift 2 ;;
        --simular-fallo) SIMULAR_FALLO="true"; shift ;;
        --fallo-tras)    FALLO_TRAS="$2"; shift 2 ;;
        *) echo "Parámetro desconocido: $1"; exit 2 ;;
    esac
done

echo "============================================================================"
echo "  DEPLOYMENT PIPELINE - ESTRATEGIA CANARY RELEASE"
echo "  Versión candidata: ${VERSION_CANARY}"
echo "  Escalones de tráfico: ${ESCALONES[*]} %"
echo "============================================================================"

preparar_estado

# =============================================================================
#  PASO 1: Identificar la versión estable que atiende el tráfico actual
# =============================================================================
log_paso "PASO 1/5 - Identificando la versión estable en producción"

PUERTO_ESTABLE="$(grep -o '"puertoPrimario":[[:space:]]*[0-9]*' "${ARCHIVO_ROUTER}" 2>/dev/null \
                  | grep -o '[0-9]*$')"
PUERTO_ESTABLE="${PUERTO_ESTABLE:-${PUERTO_AZUL}}"

VERSION_ESTABLE="$(curl -s --noproxy '*' -m 5 "http://localhost:${PUERTO_ESTABLE}/version" 2>/dev/null \
                   | grep -o '"version":"[^"]*"' | cut -d'"' -f4)"
VERSION_ESTABLE="${VERSION_ESTABLE:-desconocida}"

log "Versión estable  : ${VERSION_ESTABLE} (puerto ${PUERTO_ESTABLE}) - 100% del tráfico"
log "Versión canary   : ${VERSION_CANARY} (puerto ${PUERTO_CANARY}) - aún sin tráfico"

# =============================================================================
#  PASO 2: Desplegar el canary SIN asignarle tráfico todavía
# =============================================================================
log_paso "PASO 2/5 - Desplegando la instancia canary (peso 0%)"

detener_instancia "canary"
if ! iniciar_instancia "canary" "${PUERTO_CANARY}" "${VERSION_CANARY}" \
                       "canary" "${SIMULAR_FALLO}" "${FALLO_TRAS}"; then
    log_error "No fue posible iniciar la instancia canary. Despliegue abortado."
    exit 1
fi

if ! esperar_saludable "${PUERTO_CANARY}" "instancia canary"; then
    log_error "La instancia canary no superó el health check inicial."
    log_alerta "ROLLBACK PREVENTIVO: el canary se retira sin haber recibido tráfico."
    detener_instancia "canary"
    echo ""
    echo "RESULTADO: CANARY RECHAZADO ANTES DE RECIBIR TRÁFICO"
    exit 1
fi

# =============================================================================
#  PASO 3: Smoke tests sobre el canary antes de exponerlo
# =============================================================================
log_paso "PASO 3/5 - Smoke tests sobre la instancia canary"

if ! "${DIR_INFRA}/smoke-test.sh" "${PUERTO_CANARY}"; then
    log_error "El canary no superó los smoke tests."
    log_alerta "ROLLBACK PREVENTIVO: se retira sin exponerlo a usuarios."
    detener_instancia "canary"
    exit 1
fi

# =============================================================================
#  PASO 4: INCREMENTO PROGRESIVO DEL TRÁFICO
#  En cada escalón se verifica la salud ANTES de aumentar la exposición.
# =============================================================================
log_paso "PASO 4/5 - Incrementando el tráfico de forma progresiva"

for PESO in "${ESCALONES[@]}"; do

    echo ""
    log "--- Escalón: dirigiendo el ${PESO}% del tráfico al canary ---"
    configurar_router "${PUERTO_ESTABLE}" "${PUERTO_CANARY}" "${PESO}"
    sleep 1

    # Medición REAL del reparto: se envían peticiones por el balanceador y se
    # contabiliza a qué instancia llegó cada una.
    medir_distribucion "${PETICIONES_MUESTRA}"

    # Monitoreo de la salud del canary bajo el tráfico recién asignado
    ANOMALIA="no"
    for (( verificacion = 1; verificacion <= 3; verificacion++ )); do
        if health_check "${PUERTO_CANARY}"; then
            log "  verificación ${verificacion}/3: canary sano con ${PESO}% del tráfico"
        else
            log_alerta "  verificación ${verificacion}/3: ANOMALÍA DETECTADA en el canary"
            ANOMALIA="si"
            break
        fi
        sleep 1
    done

    # ---------------- ROLLBACK PARCIAL ANTE ANOMALÍA ----------------
    if [[ "${ANOMALIA}" == "si" ]]; then
        echo ""
        log_error "================================================================"
        log_error " ANOMALÍA EN EL CANARY CON ${PESO}% DEL TRÁFICO"
        log_error " Versión afectada: ${VERSION_CANARY}"
        log_error "================================================================"

        log_paso "ROLLBACK PARCIAL - retirando el canary del enrutamiento"

        # Equivalente a: kubectl scale deploy/miapp-canary --replicas=0
        # El peso se lleva a 0 y la versión estable recupera todo el tráfico.
        configurar_router "${PUERTO_ESTABLE}" 0 0
        sleep 1

        medir_distribucion "${PETICIONES_MUESTRA}"
        if [[ "${DISTRIBUCION_CANARY}" == "0" ]]; then
            log_ok "Confirmado: el 100% del tráfico vuelve a la versión estable ${VERSION_ESTABLE}"
        else
            log_error "El canary sigue recibiendo tráfico: se requiere intervención manual"
        fi

        detener_instancia "canary"
        log "Instancia canary retirada del ambiente"

        log_alerta "NOTIFICACIÓN -> canal #deploy-alerts: rollback parcial ejecutado"
        log_alerta "  Usuarios afectados: solo el ${PESO}% durante la ventana de detección"
        log_alerta "  Versión vigente   : ${VERSION_ESTABLE}"

        echo ""
        echo "============================================================================"
        echo "  RESULTADO: ROLLBACK PARCIAL COMPLETADO"
        echo "  El impacto quedó acotado al ${PESO}% del tráfico"
        echo "  Producción sirviendo la versión estable ${VERSION_ESTABLE}"
        echo "============================================================================"
        exit 3
    fi

    log_ok "Escalón del ${PESO}% superado sin anomalías"
done

# =============================================================================
#  PASO 5: PROMOCIÓN DEFINITIVA
#  El canary superó todos los escalones y pasa a ser la versión estable.
# =============================================================================
log_paso "PASO 5/5 - Promoviendo el canary a versión estable"

configurar_router "${PUERTO_CANARY}" 0 0
sleep 1

VERIFICACION="$(version_en_produccion)"
log "El balanceador responde: ${VERIFICACION}"

if echo "${VERIFICACION}" | grep -q "\"version\":\"${VERSION_CANARY}\""; then
    log_ok "Promoción confirmada: ${VERSION_CANARY} es ahora la versión estable"
else
    log_error "La promoción no se reflejó correctamente. Revirtiendo."
    configurar_router "${PUERTO_ESTABLE}" 0 0
    exit 1
fi

echo ""
echo "============================================================================"
echo "  RESULTADO: DESPLIEGUE CANARY EXITOSO"
echo "  Versión promovida : ${VERSION_CANARY}"
echo "  Versión anterior  : ${VERSION_ESTABLE} (disponible para rollback)"
echo "  Escalones         : ${ESCALONES[*]} % completados sin anomalías"
echo "============================================================================"
exit 0
