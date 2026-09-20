#!/usr/bin/env bash
# =============================================================================
#  LIMPIEZA DEL AMBIENTE DE PRUEBAS
#  Sistema de Reservas Turísticas - IPLACEX
# -----------------------------------------------------------------------------
#  Detiene todas las instancias y libera los puertos utilizados.
#
#  Ejecutar esta limpieza al final de cada despliegue evita la contaminación
#  entre ejecuciones sucesivas en un mismo agente (ME_3 §2.6), que es una de
#  las causas más frecuentes de pruebas intermitentes.
#
#  USO: ./infra/limpiar.sh
# =============================================================================

set -uo pipefail

DIR_INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-despliegue.sh
source "${DIR_INFRA}/lib-despliegue.sh"

limpiar_ambiente

# Respaldo: cualquier proceso de la aplicación que hubiera quedado huérfano
pkill -f "com.iplacex.qa.reservas.api.Main" 2>/dev/null || true
pkill -f "Router.java" 2>/dev/null || true

echo ""
echo "Ambiente limpio. La bitácora de auditoría se conserva en:"
echo "  ${HISTORIAL}"
