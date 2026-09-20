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

# -----------------------------------------------------------------------------
#  Respaldo: procesos de la aplicación que hubieran quedado huérfanos, es decir,
#  sin archivo .pid asociado (por ejemplo, tras un corte abrupto del pipeline).
#
#  Deliberadamente NO se usa 'pkill -f <patrón>': esa opción compara el patrón
#  contra la línea de comandos COMPLETA de todos los procesos, de modo que
#  alcanza también al shell que invoca este script si su propia línea de
#  comandos contiene el patrón. En un agente de CI eso aborta el job entero.
#
#  En su lugar se verifica, proceso por proceso, que el ejecutable sea
#  realmente java y que no se trate de este mismo script ni de su padre.
# -----------------------------------------------------------------------------
detener_huerfanos() {
    local patron="$1" etiqueta="$2"
    local encontrados=0 pid ejecutable

    for pid in $(pgrep -f "${patron}" 2>/dev/null); do
        # Nunca este script ni el shell que lo invocó
        [[ "${pid}" == "$$" || "${pid}" == "${PPID}" ]] && continue

        # Solo procesos cuyo ejecutable real sea java
        ejecutable="$(readlink -f "/proc/${pid}/exe" 2>/dev/null || true)"
        [[ "${ejecutable}" == */java ]] || continue

        kill -9 "${pid}" 2>/dev/null && (( encontrados++ ))
    done

    (( encontrados > 0 )) && echo "  ${encontrados} proceso(s) huérfano(s) de ${etiqueta} detenido(s)"
    return 0
}

detener_huerfanos "com.iplacex.qa.reservas.api.Main" "la aplicación"
detener_huerfanos "Router.java" "el balanceador"

echo ""
echo "Ambiente limpio. La bitácora de auditoría se conserva en:"
echo "  ${HISTORIAL}"
