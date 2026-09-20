#!/usr/bin/env bash
# =============================================================================
#  SUITE DE ACEPTACIÓN VÍA HTTP
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Réplica en shell de los escenarios de negocio definidos en:
#    - src/test/java/.../aceptacion/FlujoReservaIT.java
#    - src/test/resources/features/reserva-turistica.feature
#
#  Permite que el Acceptance Gate se ejecute en agentes que no disponen de
#  Maven, manteniendo exactamente los mismos criterios de aceptación. Las
#  verificaciones están redactadas en formato Dado / Cuando / Entonces para
#  conservar la trazabilidad con la especificación BDD.
#
#  USO: ./infra/aceptacion-http.sh <puerto>
# =============================================================================

set -uo pipefail

PUERTO="${1:-9083}"
BASE="http://localhost:${PUERTO}"

TOTAL=0
FALLIDAS=0
SUFIJO="$(date +%s%N | tail -c 7)"   # Garantiza escenarios idempotentes

peticion() { curl -s --noproxy '*' -m 5 "$@"; }

escenario() {
    echo ""
    echo "  ESCENARIO: $1"
}

verificar() {
    local descripcion="$1" obtenido="$2" esperado="$3"
    (( TOTAL++ ))
    if echo "${obtenido}" | grep -q -- "${esperado}"; then
        printf "    [ OK ] %s\n" "${descripcion}"
    else
        printf "    [FALLA] %s\n" "${descripcion}"
        printf "            esperado: %s\n" "${esperado}"
        printf "            obtenido: %s\n" "${obtenido}"
        (( FALLIDAS++ ))
    fi
}

echo "======================================================================"
echo "  SUITE DE ACEPTACIÓN - criterios de negocio sobre ${BASE}"
echo "======================================================================"

# =============================================================================
escenario "El servicio desplegado está operativo (smoke)"
# =============================================================================
RESP="$(peticion "${BASE}/health")"
verificar "Dado que el ambiente fue desplegado, el health check responde UP" \
          "${RESP}" '"estado":"UP"'

RESP="$(peticion "${BASE}/")"
verificar "Y el portal web expone el formulario de reserva" \
          "${RESP}" 'id="form-reserva"'

# =============================================================================
escenario "Registrar una reserva en temporada normal"
# =============================================================================
RESP="$(peticion -X POST "${BASE}/api/reservas" \
        -d "{\"cliente\":\"Patricia Nunez ${SUFIJO}\",\"destino\":\"Valle del Elqui\",\"fechaInicio\":\"2026-06-10\",\"noches\":3,\"personas\":2}")"
verificar "Cuando reserva 3 noches para 2 personas, la reserva queda confirmada" \
          "${RESP}" '"estado":"CONFIRMADA"'
verificar "Y el valor total de la reserva es 270000" \
          "${RESP}" '"tarifaTotal":270000.00'

# =============================================================================
escenario "Registrar una reserva en temporada alta con estadía prolongada"
# =============================================================================
RESP="$(peticion -X POST "${BASE}/api/reservas" \
        -d "{\"cliente\":\"Rodrigo Silva ${SUFIJO}\",\"destino\":\"Isla de Pascua\",\"fechaInicio\":\"2026-01-20\",\"noches\":7,\"personas\":2}")"
verificar "Cuando reserva 7 noches en enero, la reserva queda confirmada" \
          "${RESP}" '"estado":"CONFIRMADA"'
verificar "Y el valor total aplica recargo de temporada alta y descuento: 708750" \
          "${RESP}" '"tarifaTotal":708750.00'

ID_RESERVA="$(echo "${RESP}" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)"

# =============================================================================
escenario "Consultar y cancelar una reserva vigente"
# =============================================================================
RESP="$(peticion "${BASE}/api/reservas/${ID_RESERVA}")"
verificar "Dado que existe la reserva, el cliente puede consultarla" \
          "${RESP}" 'Isla de Pascua'

RESP="$(peticion -X DELETE "${BASE}/api/reservas/${ID_RESERVA}")"
verificar "Cuando solicita la cancelación, la reserva queda cancelada" \
          "${RESP}" '"estado":"CANCELADA"'

RESP="$(peticion "${BASE}/api/reservas/${ID_RESERVA}")"
verificar "Y el cambio de estado quedó efectivamente persistido" \
          "${RESP}" '"estado":"CANCELADA"'

# =============================================================================
escenario "Impedir reservas duplicadas"
# =============================================================================
CUERPO_DUP="{\"cliente\":\"Andres Munoz ${SUFIJO}\",\"destino\":\"Pucon\",\"fechaInicio\":\"2026-08-12\",\"noches\":2,\"personas\":2}"

CODIGO="$(peticion -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/reservas" -d "${CUERPO_DUP}")"
verificar "Dado que el cliente ya tiene una reserva para esa fecha (201)" \
          "${CODIGO}" "201"

RESP="$(peticion -X POST "${BASE}/api/reservas" -d "${CUERPO_DUP}")"
verificar "Cuando intenta reservar de nuevo, el sistema rechaza por conflicto" \
          "${RESP}" 'ya posee una reserva vigente'

# =============================================================================
escenario "Validar los límites de capacidad de una reserva"
# =============================================================================
CODIGO="$(peticion -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/reservas" \
          -d "{\"cliente\":\"Limite A ${SUFIJO}\",\"destino\":\"Chiloe\",\"fechaInicio\":\"2026-09-01\",\"noches\":3,\"personas\":8}")"
verificar "Con 8 personas (máximo permitido) el sistema responde 201" "${CODIGO}" "201"

CODIGO="$(peticion -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/reservas" \
          -d "{\"cliente\":\"Limite B ${SUFIJO}\",\"destino\":\"Chiloe\",\"fechaInicio\":\"2026-09-02\",\"noches\":3,\"personas\":9}")"
verificar "Con 9 personas el sistema responde 400" "${CODIGO}" "400"

CODIGO="$(peticion -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/reservas" \
          -d "{\"cliente\":\"Limite C ${SUFIJO}\",\"destino\":\"Chiloe\",\"fechaInicio\":\"2026-09-03\",\"noches\":0,\"personas\":2}")"
verificar "Con 0 noches el sistema responde 400" "${CODIGO}" "400"

# =============================================================================
echo ""
echo "======================================================================"
if (( FALLIDAS == 0 )); then
    echo "  ACEPTACIÓN SUPERADA: ${TOTAL}/${TOTAL} criterios de negocio cumplidos"
    echo "======================================================================"
    exit 0
else
    echo "  ACEPTACIÓN FALLIDA: ${FALLIDAS} de ${TOTAL} criterios incumplidos"
    echo "======================================================================"
    exit 1
fi
