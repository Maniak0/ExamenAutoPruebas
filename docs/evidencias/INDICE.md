# Índice de Evidencias de Ejecución

Todos los archivos de esta carpeta son **registros de ejecuciones reales** del proyecto, capturados directamente de la salida de los scripts y programas. Incluyen marcas de tiempo UTC, identificadores de proceso y códigos de respuesta HTTP verificables.

---

## EV-01 — Historial de GitFlow
**Archivo:** `EV-01-gitflow-historial.log`

Grafo completo de ramas, commits y merges del repositorio, más el listado de ramas y tags. Evidencia que el flujo GitFlow fue efectivamente aplicado: ramas `feature/*` que nacen de `develop`, merges con `--no-ff` que conservan la trazabilidad, una rama `release/1.0.0` y el tag `v1.0.0` sobre `main`.

**Qué observar:** los commits de merge que preservan la estructura de cada funcionalidad y los mensajes de commit que explican el *porqué* de cada cambio, no solo el *qué*.

---

## EV-02 — Verificación de las expectativas de las pruebas unitarias
**Archivo:** `EV-02-verificacion-logica-pruebas.log`

Comprobación independiente de las **37 expectativas** declaradas en `CalculadoraTarifasTest` y `ServicioReservasTest`, ejecutada contra el código real. Verifica que cada cifra esperada y cada mensaje de error coincidan con el comportamiento efectivo del sistema.

**Qué observar:** los cálculos que combinan reglas, como 7 noches para 2 personas en enero: $630.000 de base, más 25% de recargo por temporada alta, menos 10% de descuento por estadía larga, resultando en $708.750.

> **Nota:** esta verificación no reemplaza al reporte de JUnit. El reporte oficial se genera al ejecutar `mvn clean verify` y queda en `target/surefire-reports/` y `target/failsafe-reports/`.

---

## EV-03 — Aprovisionamiento del ambiente
**Archivo:** `EV-03-preparar-ambiente.log`

Levantamiento completo del ambiente de despliegue: entorno azul con la versión 1.0.0, balanceador en el puerto 9080 y verificación de extremo a extremo con smoke tests.

**Qué observar:** el health check superado en el intento 2, que evidencia una espera real por el arranque de la instancia, y los 6/6 smoke tests ejecutados a través del balanceador.

---

## EV-04 — Acceptance Test Gate
**Archivo:** `EV-04-acceptance-gate.log`

Despliegue en staging y ejecución de los **14 criterios de negocio** de la suite de aceptación, con la decisión automatizada del gate.

**Qué observar:** los escenarios expresados en formato Dado / Cuando / Entonces, que mantienen la trazabilidad con la especificación BDD en Gherkin, y la liberación del ambiente de staging al finalizar.

---

## EV-05 — Despliegue Blue-Green exitoso
**Archivo:** `EV-05-blue-green-exitoso.log`

Despliegue de la versión 1.1.0 siguiendo los seis pasos de la estrategia: identificación del entorno activo, despliegue en el inactivo, health check, smoke tests, switch de tráfico y ventana de monitoreo.

**Qué observar:** la respuesta del balanceador cambiando de `{"version":"1.0.0","color":"azul"}` a `{"version":"1.1.0","color":"verde"}` tras el switch, y el entorno azul quedando explícitamente en stand-by para un eventual rollback.

---

## EV-06 — Rollback automático (Blue-Green)
**Archivo:** `EV-06-blue-green-rollback-automatico.log`

**Evidencia central del mecanismo de recuperación.** Se despliega la versión 2.0.0 con un defecto latente: arranca sana, supera el health check y los smoke tests, recibe el tráfico y comienza a fallar a los pocos segundos.

**Qué observar, en orden:**
1. Los gates previos se superan correctamente (health check y 6/6 smoke tests): el defecto es invisible antes del despliegue.
2. El switch de tráfico se completa y el balanceador responde con la versión 2.0.0.
3. En `t+1s` y `t+2s` la instancia se reporta sana.
4. En `t+3s` y `t+4s` el health check falla dos veces consecutivas, superando el umbral.
5. Se ejecuta el **rollback automático**: el tráfico vuelve al entorno estable y se verifica con `{"version":"1.0.0","color":"azul"}`.
6. La instancia defectuosa se retira del ambiente.

El script termina con **código de salida 3**, que el pipeline interpreta como "rollback ejecutado": el despliegue no prosperó, pero el mecanismo de recuperación cumplió su función y producción quedó sana. Por eso el build se marca como *inestable* y no como *fallido*.

Este escenario demuestra por qué la ventana de monitoreo posterior al switch es indispensable: ningún gate previo podía detectar este defecto.

---

## EV-07 — Despliegue Canary progresivo
**Archivo:** `EV-07-canary-exitoso.log`

Incremento del tráfico en escalones de 10%, 25%, 50% y 100%, con **medición real del reparto** en cada paso mediante 200 peticiones enviadas a través del balanceador.

**Qué observar:** la distribución medida sigue de cerca el peso configurado —7%, 26%, 47% y 100%— lo que confirma que el enrutamiento ponderado opera efectivamente y no es solo una declaración en un archivo de configuración. La pequeña dispersión respecto del valor nominal es el comportamiento estadístico esperado de un sorteo aleatorio.

---

## EV-08 — Rollback parcial (Canary)
**Archivo:** `EV-08-canary-rollback-parcial.log`

Despliegue canary de la versión 3.0.0 con defecto latente. El canary supera el escalón del 10% y falla durante el del 25%.

**Qué observar:** el rollback parcial lleva el peso del canary a 0 y la medición posterior confirma **0% del tráfico al canary** sobre 200 peticiones. El impacto quedó acotado al 25% de los usuarios durante la ventana de detección, que es precisamente la ventaja de esta estrategia frente a un switch total.

---

## EV-09 — Rollback manual asistido
**Archivo:** `EV-09-rollback-manual.log`

Reversión ejecutada por un operador mediante un único comando, para los casos en que el problema lo detecta una persona y no el pipeline.

**Qué observar:** el inventario inicial que identifica qué instancia recibe el tráfico; la **validación de salud del destino antes de conmutar**, que impide revertir hacia una instancia caída; y la verificación funcional posterior con smoke tests, porque no basta con que el servicio responda, debe además funcionar.

---

## EV-10 — Estado final del ambiente
**Archivo:** `EV-10-estado-final-ambiente.log`

Inventario del ambiente tras completar todos los escenarios, ejecutado con `./infra/rollback.sh --estado`, que consulta sin modificar nada.

---

## Capturas del sistema en ejecución
**Carpeta:** `capturas/`

Imágenes tomadas del sistema real en funcionamiento, no maquetas ni montajes. El portal muestra en su cabecera un distintivo con la versión y el entorno que atiende la petición, lo que permite verificar visualmente el efecto de cada despliegue.

| Archivo | Contenido |
|---|---|
| `cap-01-portal-v1.0.0-azul.png` | Estado inicial: versión 1.0.0 servida desde el entorno azul |
| `cap-02-reserva-confirmada.png` | Reserva creada desde el portal, confirmada con su identificador |
| `cap-03-health-up.png` | Health check de una instancia sana: HTTP 200 con estado UP |
| `cap-04-portal-v1.1.0-verde.png` | Tras el switch Blue-Green: versión 1.1.0 en el entorno verde |
| `cap-05-health-down-503.png` | Instancia defectuosa: HTTP 503 con estado DOWN y el motivo |
| `cap-06-portal-tras-rollback.png` | Tras el rollback automático: versión estable restaurada |
| `cap-07-version-tras-rollback.png` | Endpoint /version confirmando la versión restaurada |

**Qué observar:** comparando `cap-01` con `cap-04` se aprecia la conmutación Blue-Green, ya que el distintivo pasa de azul a verde y la versión de 1.0.0 a 1.1.0 sin que la dirección de acceso cambie. La secuencia `cap-03` → `cap-05` → `cap-06` documenta el ciclo completo de detección del fallo y recuperación.

---

## EV-11 — Bitácora de auditoría
**Archivo:** `EV-11-bitacora-auditoria.log`

Registro acumulado de **201 eventos** con marca de tiempo UTC, generado automáticamente por todos los scripts de despliegue. Constituye la evidencia de trazabilidad exigida para auditoría y cumplimiento: permite reconstruir qué se desplegó, cuándo, con qué resultado y qué acciones de recuperación se ejecutaron.

---

## Cómo reproducir estas evidencias

```bash
mvn clean package
chmod +x infra/*.sh

./infra/preparar-ambiente.sh --version 1.0.0
./infra/acceptance-gate.sh   --version 1.1.0
./infra/deploy-blue-green.sh --version 1.1.0
./infra/deploy-canary.sh     --version 1.2.0
./infra/deploy-blue-green.sh --version 2.0.0 --fallo-tras 4   # rollback automático
./infra/deploy-canary.sh     --version 3.0.0 --fallo-tras 8   # rollback parcial
./infra/rollback.sh                                           # rollback manual
./infra/limpiar.sh
```

Para capturar pantallazos del pipeline completo ejecutándose en un servidor de CI, la vía más directa es subir el repositorio a GitHub: el workflow de integración continua corre automáticamente en cada push, y el de despliegue se lanza desde **Actions → CD - Deployment Pipeline → Run workflow**.
