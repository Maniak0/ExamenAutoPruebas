#!/usr/bin/env bash
# =============================================================================
#  ACTIVIDAD 3 - ROLLBACK MANUAL ASISTIDO
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Además del rollback automático que ejecutan los scripts de despliegue, el
#  Módulo de Estudio 6 §1.1 exige disponer de un ROLLBACK MANUAL ASISTIDO: un
#  único comando que devuelva el sistema a una versión estable cuando el
#  problema lo detecta una persona y no el pipeline (una queja de usuarios, una
#  alerta de negocio, una métrica que el health check no cubre).
#
#  Es el equivalente conceptual de:
#      helm rollback miapp 1
#      kubectl rollout undo deployment/miapp
#
#  El script se versiona junto al código de despliegue (buena práctica ME_6
#  §2.1) para que su comportamiento sea auditable y revisable en un pull request.
#
#  USO:
#    ./infra/rollback.sh                 # vuelve al entorno en stand-by
#    ./infra/rollback.sh --a-puerto 9081 # vuelve a un entorno específico
#    ./infra/rollback.sh --estado        # solo muestra el estado actual
# =============================================================================

set -uo pipefail

DIR_INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-despliegue.sh
source "${DIR_INFRA}/lib-despliegue.sh"

PUERTO_DESTINO=""
SOLO_ESTADO="no"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --a-puerto) PUERTO_DESTINO="$2"; shift 2 ;;
        --estado)   SOLO_ESTADO="si"; shift ;;
        *) echo "Parámetro desconocido: $1"; exit 2 ;;
    esac
done

echo "============================================================================"
echo "  ROLLBACK MANUAL ASISTIDO"
echo "============================================================================"

# =============================================================================
#  Inventario del ambiente: qué instancias existen y cuál recibe el tráfico
# =============================================================================
log_paso "Inventario del ambiente"

PUERTO_ACTUAL="$(grep -o '"puertoPrimario":[[:space:]]*[0-9]*' "${ARCHIVO_ROUTER}" 2>/dev/null \
                 | grep -o '[0-9]*$')"
PUERTO_ACTUAL="${PUERTO_ACTUAL:-${PUERTO_AZUL}}"

consultar_version() {
    curl -s --noproxy '*' -m 3 "http://localhost:$1/version" 2>/dev/null \
        | grep -o '"version":"[^"]*"' | cut -d'"' -f4
}

estado_instancia() {
    local puerto="$1" etiqueta="$2"
    local version
    version="$(consultar_version "${puerto}")"

    if [[ -z "${version}" ]]; then
        printf "  %-10s puerto %-6s [ detenida ]\n" "${etiqueta}" "${puerto}"
        return 1
    fi

    local marca=""
    [[ "${puerto}" == "${PUERTO_ACTUAL}" ]] && marca="  <== RECIBE EL TRÁFICO"

    local salud="sana"
    health_check "${puerto}" || salud="DEGRADADA"

    printf "  %-10s puerto %-6s version %-10s %-10s%s\n" \
           "${etiqueta}" "${puerto}" "${version}" "${salud}" "${marca}"
    return 0
}

echo ""
estado_instancia "${PUERTO_AZUL}"  "azul"    || true
estado_instancia "${PUERTO_VERDE}" "verde"   || true
estado_instancia "9084"            "canary"  || true
echo ""

if [[ "${SOLO_ESTADO}" == "si" ]]; then
    log "Consulta de estado finalizada. No se realizaron cambios."
    exit 0
fi

# =============================================================================
#  Determinar el destino del rollback
# =============================================================================
log_paso "Determinando el destino del rollback"

VERSION_ACTUAL="$(consultar_version "${PUERTO_ACTUAL}")"
log "Versión que atiende actualmente: ${VERSION_ACTUAL:-desconocida} (puerto ${PUERTO_ACTUAL})"

if [[ -z "${PUERTO_DESTINO}" ]]; then
    # Sin destino explícito: se vuelve al entorno que quedó en stand-by.
    if [[ "${PUERTO_ACTUAL}" == "${PUERTO_AZUL}" ]]; then
        PUERTO_DESTINO="${PUERTO_VERDE}"
    else
        PUERTO_DESTINO="${PUERTO_AZUL}"
    fi
    log "Destino seleccionado automáticamente: entorno en stand-by (puerto ${PUERTO_DESTINO})"
else
    log "Destino indicado por el operador: puerto ${PUERTO_DESTINO}"
fi

# =============================================================================
#  VALIDACIÓN PREVIA: nunca se conmuta hacia un destino que no esté sano.
#  Un rollback hacia una instancia caída convertiría un incidente en una caída
#  total del servicio.
# =============================================================================
log_paso "Validando la salud del destino antes de conmutar"

if ! health_check "${PUERTO_DESTINO}"; then
    log_error "El destino (puerto ${PUERTO_DESTINO}) NO está sano o no existe."
    log_error "El rollback se ABORTA para no empeorar el incidente."
    log_error "Acción sugerida: levantar una instancia estable con"
    log_error "  ./infra/preparar-ambiente.sh --version <versión estable>"
    echo ""
    echo "RESULTADO: ROLLBACK ABORTADO POR VALIDACIÓN PREVIA"
    exit 1
fi

VERSION_DESTINO="$(consultar_version "${PUERTO_DESTINO}")"
log_ok "Destino sano y disponible: versión ${VERSION_DESTINO}"

# =============================================================================
#  EJECUCIÓN DEL ROLLBACK
# =============================================================================
log_paso "Ejecutando el rollback"

configurar_router "${PUERTO_DESTINO}" 0 0
sleep 1

VERIFICACION="$(version_en_produccion)"
log "El balanceador responde ahora: ${VERIFICACION}"

if echo "${VERIFICACION}" | grep -q "\"version\":\"${VERSION_DESTINO}\""; then
    log_ok "ROLLBACK COMPLETADO: producción sirve la versión ${VERSION_DESTINO}"
else
    log_error "El rollback no se reflejó en el balanceador. Se requiere revisión manual."
    exit 1
fi

# Verificación funcional: no basta con que responda, debe funcionar.
log_paso "Verificación funcional posterior al rollback"
if "${DIR_INFRA}/smoke-test.sh" "${PUERTO_DESTINO}"; then
    log_ok "Los flujos críticos operan correctamente tras el rollback"
else
    log_error "La versión restaurada no supera los smoke tests: escalar al equipo"
    exit 1
fi

log_alerta "NOTIFICACIÓN -> canal #deploy-alerts: rollback manual ejecutado"
log_alerta "  De : ${VERSION_ACTUAL:-desconocida}"
log_alerta "  A  : ${VERSION_DESTINO}"
log_alerta "  Operador: ${USER:-pipeline}"

echo ""
echo "============================================================================"
echo "  RESULTADO: ROLLBACK MANUAL COMPLETADO"
echo "  Versión restaurada : ${VERSION_DESTINO}"
echo "  Registro auditable : ${HISTORIAL}"
echo "============================================================================"
exit 0
