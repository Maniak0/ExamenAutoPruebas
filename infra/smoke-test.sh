#!/usr/bin/env bash
# =============================================================================
#  SMOKE TESTS del deployment pipeline
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Verificación de los flujos críticos básicos sobre una instancia recién
#  desplegada, antes de exponerla a usuarios reales.
#
#  Siguen la buena práctica de "pruebas representativas, no exhaustivas"
#  (ME_5 §1.4.2): son pocas y muy rápidas, porque su propósito es entregar
#  confianza inmediata, no sustituir a la suite de aceptación.
#
#  USO: ./infra/smoke-test.sh <puerto>
# =============================================================================

set -uo pipefail

PUERTO="${1:-9081}"
BASE="http://localhost:${PUERTO}"

TOTAL=0
FALLIDAS=0

# Envoltorio de curl. Se define como función y no como variable porque el
# comodín de --noproxy sería expandido como patrón de archivos al reutilizarse.
peticion() {
    curl -s --noproxy '*' -m 5 "$@"
}

echo "----------------------------------------------------------------------"
echo "  SMOKE TESTS sobre ${BASE}"
echo "----------------------------------------------------------------------"

# -----------------------------------------------------------------------------
#  Ejecuta una verificación y contabiliza su resultado.
#  Parámetros: descripción, valor_obtenido, patrón_esperado
# -----------------------------------------------------------------------------
verificar() {
    local descripcion="$1" obtenido="$2" esperado="$3"
    (( TOTAL++ ))

    if echo "${obtenido}" | grep -q "${esperado}"; then
        printf "  [ OK ] %s\n" "${descripcion}"
    else
        printf "  [FALLA] %s\n" "${descripcion}"
        printf "          esperado: %s\n" "${esperado}"
        printf "          obtenido: %s\n" "${obtenido}"
        (( FALLIDAS++ ))
    fi
}

# --- SMOKE 1: la instancia se declara disponible ------------------------------
RESP="$(peticion "${BASE}/health")"
verificar "El health check reporta la instancia como disponible" "${RESP}" '"estado":"UP"'

# --- SMOKE 2: el portal web responde ------------------------------------------
CODIGO="$(peticion -o /dev/null -w '%{http_code}' "${BASE}/")"
verificar "El portal web responde con código 200" "${CODIGO}" "200"

# --- SMOKE 3: la API lista reservas -------------------------------------------
CODIGO="$(peticion -o /dev/null -w '%{http_code}' "${BASE}/api/reservas")"
verificar "La API de reservas responde con código 200" "${CODIGO}" "200"

# --- SMOKE 4: flujo crítico de negocio, crear una reserva ---------------------
# El cliente lleva un sufijo único para que la prueba sea IDEMPOTENTE: ejecutar
# el smoke test varias veces contra la misma instancia no debe chocar con el
# control de reservas duplicadas.
CLIENTE_SMOKE="Smoke Test $(date +%s%N | tail -c 7)"
RESP="$(peticion -X POST "${BASE}/api/reservas" \
        -d "{\"cliente\":\"${CLIENTE_SMOKE}\",\"destino\":\"Valle del Elqui\",\"fechaInicio\":\"2026-06-10\",\"noches\":3,\"personas\":2}")"
verificar "Se puede crear una reserva (flujo crítico)" "${RESP}" '"estado":"CONFIRMADA"'

# --- SMOKE 5: la regla de negocio de tarifas opera correctamente --------------
verificar "La tarifa se calcula según la regla vigente" "${RESP}" '"tarifaTotal":270000.00'

# --- SMOKE 6: las validaciones rechazan datos inválidos -----------------------
CODIGO="$(peticion -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/reservas" \
          -d '{"cliente":"Invalido","destino":"X","fechaInicio":"2026-06-10","noches":0,"personas":2}')"
verificar "Los datos inválidos son rechazados con código 400" "${CODIGO}" "400"

# -----------------------------------------------------------------------------
echo "----------------------------------------------------------------------"
if (( FALLIDAS == 0 )); then
    echo "  SMOKE TESTS SUPERADOS: ${TOTAL}/${TOTAL} verificaciones correctas"
    echo "----------------------------------------------------------------------"
    exit 0
else
    echo "  SMOKE TESTS FALLIDOS: ${FALLIDAS} de ${TOTAL} verificaciones con error"
    echo "----------------------------------------------------------------------"
    exit 1
fi
