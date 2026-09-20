#!/usr/bin/env bash
# =============================================================================
#  PREPARACIÓN DEL AMBIENTE DE PRUEBAS
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Aprovisiona desde cero el ambiente sobre el que operan los despliegues:
#
#      Usuarios --> [ BALANCEADOR :9080 ] --> [ AZUL :9081 ]  (activo)
#                                         \-> [ VERDE :9082 ] (stand-by)
#                                         \-> [ CANARY :9084 ] (según despliegue)
#
#  Levantar el ambiente con un único comando garantiza que cada ejecución parta
#  de un estado conocido y reproducible, eliminando los fallos derivados de
#  entornos inconsistentes (ME_2).
#
#  USO: ./infra/preparar-ambiente.sh [--version 1.0.0]
# =============================================================================

set -uo pipefail

DIR_INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-despliegue.sh
source "${DIR_INFRA}/lib-despliegue.sh"

VERSION_INICIAL="1.0.0"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION_INICIAL="$2"; shift 2 ;;
        *) echo "Parámetro desconocido: $1"; exit 2 ;;
    esac
done

echo "============================================================================"
echo "  APROVISIONAMIENTO DEL AMBIENTE DE PRUEBAS"
echo "  Versión inicial en producción: ${VERSION_INICIAL}"
echo "============================================================================"

preparar_estado

# =============================================================================
#  PASO 1: Ambiente limpio (entornos efímeros, ME_5 §1.2.2)
# =============================================================================
log_paso "PASO 1/4 - Limpiando restos de ejecuciones anteriores"
limpiar_ambiente

# =============================================================================
#  PASO 2: Verificar que exista el artefacto a desplegar
# =============================================================================
log_paso "PASO 2/4 - Verificando el artefacto desplegable"

if ! ARTEFACTO="$(ubicar_artefacto)"; then
    log_error "No hay artefacto disponible."
    log_error "Construya el proyecto antes de aprovisionar el ambiente:"
    log_error "   mvn clean package"
    exit 1
fi

if [[ "${ARTEFACTO}" == CLASSES:* ]]; then
    log_alerta "Se usarán las clases compiladas (${ARTEFACTO#CLASSES:})"
    log_alerta "Para un despliegue equivalente al de producción ejecute: mvn clean package"
else
    log_ok "Artefacto localizado: ${ARTEFACTO}"
fi

# =============================================================================
#  PASO 3: Levantar el entorno AZUL como versión activa
# =============================================================================
log_paso "PASO 3/4 - Desplegando el entorno azul (versión ${VERSION_INICIAL})"

iniciar_instancia "azul" "${PUERTO_AZUL}" "${VERSION_INICIAL}" "azul" "false" "0"

if ! esperar_saludable "${PUERTO_AZUL}" "entorno azul"; then
    log_error "El entorno azul no llegó a estar sano. Revise ${DIR_LOGS}/azul.log"
    exit 1
fi

# =============================================================================
#  PASO 4: Levantar el balanceador apuntando al entorno azul
# =============================================================================
log_paso "PASO 4/4 - Levantando el balanceador en el puerto ${PUERTO_ROUTER}"

configurar_router "${PUERTO_AZUL}" 0 0
iniciar_router

if ! esperar_saludable "${PUERTO_ROUTER}" "balanceador"; then
    log_error "El balanceador no responde. Revise ${DIR_LOGS}/router.log"
    exit 1
fi

# =============================================================================
#  Verificación final de extremo a extremo
# =============================================================================
log_paso "Verificación del ambiente aprovisionado"

RESPUESTA="$(version_en_produccion)"
log "Respuesta del punto de entrada: ${RESPUESTA}"

if ! "${DIR_INFRA}/smoke-test.sh" "${PUERTO_ROUTER}"; then
    log_error "El ambiente recién aprovisionado no supera los smoke tests"
    exit 1
fi

echo ""
echo "============================================================================"
echo "  AMBIENTE LISTO"
echo "    Punto de entrada estable : http://localhost:${PUERTO_ROUTER}"
echo "    Entorno azul  (activo)   : http://localhost:${PUERTO_AZUL}   v${VERSION_INICIAL}"
echo "    Entorno verde (libre)    : puerto ${PUERTO_VERDE}"
echo "    Bitácora de auditoría    : ${HISTORIAL}"
echo ""
echo "  Siguiente paso sugerido:"
echo "    ./infra/deploy-blue-green.sh --version 1.1.0"
echo "============================================================================"
exit 0
