#!/usr/bin/env bash
# =============================================================================
#  ACTIVIDAD 3 - AUTOMATED ACCEPTANCE TEST GATE
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Implementa el gate descrito en el Módulo de Estudio 5 §1.4: la barrera
#  automatizada que valida, en un ambiente lo más parecido posible a producción
#  (staging), que la versión candidata cumple los criterios de aceptación
#  definidos por el negocio ANTES de avanzar a producción.
#
#  FLUJO:
#    1. Desplegar el artefacto en STAGING (el mismo .jar, nunca uno recompilado).
#    2. Esperar a que el ambiente esté sano.
#    3. Ejecutar la suite de aceptación (JUnit + BDD Cucumber) contra staging.
#    4. Decisión automatizada: si pasa, se habilita la promoción a producción;
#       si falla, el pipeline se detiene y notifica con los reportes adjuntos.
#
#  USO: ./infra/acceptance-gate.sh --version 1.1.0
# =============================================================================

set -uo pipefail

DIR_INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-despliegue.sh
source "${DIR_INFRA}/lib-despliegue.sh"

VERSION="1.1.0"
FORZAR_SIN_MAVEN="no"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)    VERSION="$2"; shift 2 ;;
        # Ejecuta la suite de aceptación vía HTTP en lugar de Maven. Útil en
        # agentes sin acceso al repositorio de dependencias.
        --sin-maven)  FORZAR_SIN_MAVEN="si"; shift ;;
        *) echo "Parámetro desconocido: $1"; exit 2 ;;
    esac
done

echo "============================================================================"
echo "  AUTOMATED ACCEPTANCE TEST GATE"
echo "  Versión candidata: ${VERSION}"
echo "  Ambiente         : staging (puerto ${PUERTO_STAGING})"
echo "============================================================================"

preparar_estado
RESULTADO_GATE=0

# =============================================================================
#  PASO 1: Desplegar el artefacto en staging
# =============================================================================
log_paso "PASO 1/4 - Desplegando la versión ${VERSION} en staging"

detener_instancia "staging"
if ! iniciar_instancia "staging" "${PUERTO_STAGING}" "${VERSION}" "staging" "false" "0"; then
    log_error "No fue posible desplegar en staging"
    exit 1
fi

# =============================================================================
#  PASO 2: Esperar a que staging esté operativo
# =============================================================================
log_paso "PASO 2/4 - Esperando a que staging quede operativo"

if ! esperar_saludable "${PUERTO_STAGING}" "staging"; then
    log_error "Staging no llegó a estar disponible. El gate no puede ejecutarse."
    detener_instancia "staging"
    exit 1
fi

# =============================================================================
#  PASO 3: Ejecutar la suite de aceptación contra el ambiente desplegado
# =============================================================================
log_paso "PASO 3/4 - Ejecutando las pruebas de aceptación contra staging"

BASE_URL="http://localhost:${PUERTO_STAGING}"

if command -v mvn >/dev/null 2>&1 && [[ "${FORZAR_SIN_MAVEN}" == "no" ]]; then
    # Ruta principal: la suite de aceptación JUnit + los escenarios BDD en
    # Gherkin se ejecutan contra el ambiente que acabamos de desplegar.
    log "Ejecutando: mvn verify -Paceptacion -Dtest.baseUrl=${BASE_URL}"

    # El perfil 'aceptacion' omite las pruebas unitarias y la medicion de
    # cobertura: aqui solo se validan los criterios de negocio sobre staging.
    if (cd "${DIR_RAIZ}" && mvn -B --no-transfer-progress verify -Paceptacion \
            -Dtest.baseUrl="${BASE_URL}"); then
        log_ok "Suite de aceptación SUPERADA"
    else
        log_error "Suite de aceptación FALLIDA"
        RESULTADO_GATE=1
    fi
else
    # Ruta alternativa: se ejecutan los mismos criterios de negocio mediante
    # peticiones HTTP directas, sin depender del repositorio de dependencias.
    if [[ "${FORZAR_SIN_MAVEN}" == "si" ]]; then
        log_alerta "Ejecución forzada sin Maven (--sin-maven)"
    else
        log_alerta "Maven no está disponible en este agente"
    fi
    log "Ejecutando la verificación de aceptación equivalente vía HTTP"

    if "${DIR_INFRA}/aceptacion-http.sh" "${PUERTO_STAGING}"; then
        log_ok "Verificación de aceptación SUPERADA"
    else
        log_error "Verificación de aceptación FALLIDA"
        RESULTADO_GATE=1
    fi
fi

# =============================================================================
#  PASO 4: DECISIÓN AUTOMATIZADA DEL GATE
# =============================================================================
log_paso "PASO 4/4 - Decisión del gate"

detener_instancia "staging"
log "Ambiente de staging liberado"

if (( RESULTADO_GATE == 0 )); then
    log_ok "GATE SUPERADO: la versión ${VERSION} queda habilitada para producción"
    echo ""
    echo "============================================================================"
    echo "  ACCEPTANCE GATE: APROBADO"
    echo "  La versión ${VERSION} puede promoverse a producción."
    echo "============================================================================"
    exit 0
else
    log_error "GATE NO SUPERADO: la versión ${VERSION} NO avanza a producción"
    log_alerta "NOTIFICACIÓN -> canal #ci-alerts: acceptance gate fallido en ${VERSION}"
    echo ""
    echo "============================================================================"
    echo "  ACCEPTANCE GATE: RECHAZADO"
    echo "  El pipeline se detiene. La versión no llega a producción."
    echo "============================================================================"
    exit 1
fi
