# Estrategia de Pruebas Automatizadas

**Proyecto:** Sistema de Reservas Turísticas
**Asignatura:** Automatización de Pruebas — IPLACEX

---

## 1. Propósito de la estrategia

Una suite de pruebas no vale por la cantidad de casos que contiene, sino por la confianza que permite tener al momento de desplegar. Esta estrategia define qué se prueba en cada nivel, por qué se eligió ese nivel y no otro, y bajo qué criterio el pipeline decide detener una versión antes de que llegue a producción.

El principio que ordena todas las decisiones siguientes es el equilibrio entre **cobertura**, **velocidad de retroalimentación** y **estabilidad**. Aumentar la cobertura mediante pruebas lentas o frágiles no mejora la calidad del producto: degrada la confianza del equipo en el pipeline y, cuando eso ocurre, las pruebas terminan siendo ignoradas o desactivadas.

---

## 2. La pirámide de pruebas aplicada al proyecto

```
                    ▲
                   /│\        NIVEL 4 — E2E (Selenium)
                  /  \        3 escenarios · lentas · perfil aparte
                 /----\
                /      \      NIVEL 3 — Aceptación y BDD
               /        \     5 pruebas + 6 escenarios Gherkin
              /----------\
             /            \   NIVEL 2 — Integración (API REST real)
            /              \  8 pruebas
           /----------------\
          /                  \ NIVEL 1 — Unitarias (aisladas)
         /____________________\ 37 verificaciones
```

La forma de la pirámide no es casual: **el costo de mantener una prueba crece con su nivel, y su velocidad decrece**. Por eso la base concentra el grueso de las verificaciones y la cúspide se reserva para unos pocos flujos críticos.

### Nivel 1 — Pruebas unitarias

**Ubicación:** `src/test/java/com/iplacex/qa/reservas/unit/`
**Ejecuta:** `maven-surefire-plugin`, fase `test`
**Tiempo objetivo:** menos de 5 segundos

Verifican las reglas de negocio de forma completamente aislada. `CalculadoraTarifasTest` cubre el cálculo de tarifas, los recargos de temporada alta, el descuento por estadía prolongada, sus combinaciones y las validaciones de parámetros; `ServicioReservasTest` verifica la orquestación sustituyendo el repositorio por un mock de Mockito.

Se emplea `@ParameterizedTest` con `@CsvSource` para cubrir casos de borde sin duplicar código de prueba, y `@Nested` para agrupar escenarios relacionados de modo que el reporte de fallos indique con precisión qué regla se rompió.

Estas pruebas son **idempotentes**: no tocan disco, red, base de datos ni reloj del sistema, por lo que pueden repetirse indefinidamente con idéntico resultado. Esa propiedad es la que las hace aptas para la etapa de commit, donde el desarrollador espera respuesta inmediata.

Un detalle que merece mención: la prueba `rechazaReservaDuplicada` no se limita a comprobar que se lance la excepción; verifica además, con `verify(repositorioMock, never()).guardar(...)`, que **no se escriba nada** en el repositorio. Comprobar solo la excepción dejaría pasar un defecto en que el sistema rechaza la operación pero igualmente persiste datos corruptos.

### Nivel 2 — Pruebas de integración

**Ubicación:** `src/test/java/com/iplacex/qa/reservas/integracion/`
**Ejecuta:** `maven-failsafe-plugin`, fase `verify`

A diferencia de las unitarias, aquí **no se sustituye nada**: se levanta el servidor HTTP real y se verifica mediante peticiones auténticas que la capa API, el servicio de negocio y el repositorio colaboren correctamente. Se comprueban los códigos de estado HTTP que el contrato de la API promete —201 al crear, 409 ante un duplicado, 400 ante datos inválidos— y se verifica que los cambios de estado efectivamente persistan, consultando el recurso después de modificarlo.

El servidor se levanta en un **puerto libre asignado por el sistema operativo**, de modo que varias ejecuciones concurrentes del pipeline en el mismo agente no colisionen entre sí. Un puerto fijo es una de las causas más frecuentes de fallos intermitentes en servidores de CI compartidos.

### Nivel 3 — Pruebas de aceptación y BDD

**Ubicación:** `src/test/java/com/iplacex/qa/reservas/aceptacion/` y `src/test/resources/features/`

Responden a la pregunta del negocio —¿puede un cliente reservar, consultar y cancelar sin problemas?— y no a detalles técnicos internos. Constituyen el **Acceptance Test Gate**, la última barrera antes de que una versión avance a producción.

Los escenarios están además escritos en **Gherkin en español**, cumpliendo la doble función de documentación legible por perfiles no técnicos y de caso de prueba ejecutable. Al versionarse junto al código, la especificación no puede envejecer respecto del sistema que describe.

La clase `AmbientePruebas` resuelve un problema práctico del deployment pipeline: estas mismas pruebas deben ejecutarse tanto localmente —donde no hay ambiente desplegado y corresponde levantar una instancia embebida— como desde el Acceptance Gate, donde el pipeline ya desplegó staging y las pruebas deben limitarse a consumirlo. La decisión no se toma por el nombre del host, porque un staging puede perfectamente vivir en `localhost`, sino **sondeando el endpoint `/health`**: si alguien responde, se reutiliza ese ambiente. Esta única decisión evita duplicar la suite y garantiza que el gate valide exactamente los mismos escenarios que el desarrollador ejecuta en su equipo.

### Nivel 4 — Pruebas E2E

**Ubicación:** `src/test/java/com/iplacex/qa/reservas/e2e/`
**Ejecuta:** solo bajo el perfil `e2e`

Operan el navegador igual que un usuario real. Son las pruebas más lentas y frágiles de la pirámide, por lo que se mantienen **fuera del pipeline principal**: incluirlas en cada commit contradice la estrategia de fail fast y desalienta el uso del pipeline. Se reservan para ejecuciones nocturnas o para la validación previa a una liberación.

Dos decisiones técnicas sostienen su estabilidad. La primera es el uso exclusivo de **esperas explícitas** (`WebDriverWait`) en lugar de `Thread.sleep`: una pausa fija o bien alarga innecesariamente la ejecución, o bien falla cuando el agente está cargado. La segunda es que, si el agente carece de un navegador compatible, la suite **se omite mediante `Assumptions` en lugar de fallar**, porque un agente sin navegador es una limitación de infraestructura y no un defecto del producto; reportarlo como fallo entrenaría al equipo a ignorar los fallos rojos.

---

## 3. Quality gates del pipeline

El pipeline detiene el avance de una versión ante el incumplimiento de cualquiera de estos criterios objetivos:

| Gate | Criterio | Etapa |
|---|---|---|
| Compilación | El código compila sin errores | Build |
| Pruebas unitarias | 100% de las pruebas pasan | Etapa de commit |
| Pruebas de integración | 100% de las pruebas pasan | Verify |
| Cobertura de líneas | ≥ 80% (`jacoco:check`) | Quality Gate |
| Acceptance Gate | 100% de los criterios de negocio | Previo a producción |
| Health check | HTTP 200 con `"estado":"UP"` | Despliegue |
| Smoke tests | 6/6 verificaciones correctas | Despliegue |
| Monitoreo post-switch | Sin 2 fallos consecutivos | Posterior al despliegue |

Los umbrales están declarados en archivos versionados —`pom.xml` para la cobertura, los propios scripts para los health checks— y no configurados a mano en el servidor de CI. Un umbral que vive únicamente en la interfaz de Jenkins no es auditable ni revisable en un pull request.

---

## 4. Tratamiento de las pruebas intermitentes

Una prueba intermitente es más dañina que una prueba ausente: enseña al equipo a volver a ejecutar el pipeline en lugar de investigar la causa, y ese hábito termina ocultando defectos reales. La estrategia adoptada busca eliminar sus causas de raíz antes que tolerarlas mediante reintentos automáticos.

Las medidas concretas aplicadas en este proyecto son el uso de puertos dinámicos asignados por el sistema operativo en lugar de puertos fijos; fixtures que garantizan un estado limpio antes de cada prueba; sufijos únicos en los datos de prueba de los smoke tests y la suite de aceptación, de modo que ejecutarlos dos veces contra la misma instancia no choque con el control de reservas duplicadas; esperas explícitas en lugar de pausas fijas en las pruebas de navegador; y la liberación garantizada de recursos mediante `@AfterAll`, que se ejecuta aunque las pruebas fallen.

El tamaño de muestra con que se mide el reparto de tráfico del canary ilustra el mismo criterio aplicado a la observación del sistema. Una muestra de 40 peticiones arrojaba un 17% medido para un peso configurado del 10%, simple dispersión estadística que sin embargo llevaría a dudar de un enrutamiento correcto. Elevar la muestra a 200 peticiones redujo esa dispersión a valores del orden del 7% al 10%, haciendo la medición interpretable.

---

## 5. Criterios de priorización y mantenimiento

Frente a la pregunta de qué automatizar primero, el orden adoptado privilegia los flujos que producen pérdida económica o de confianza si fallan —crear una reserva, calcular su tarifa, cancelarla—, seguidos de las reglas de negocio con casos de borde, donde el cálculo de tarifas concentra la mayor densidad de defectos posibles por combinar recargos y descuentos. A continuación se ubican las validaciones que protegen la integridad de los datos, como el control de duplicados, y solo después los caminos alternativos y los mensajes de error.

En cuanto al mantenimiento, la suite se revisa con la misma disciplina que el código productivo. Una prueba que falla de forma intermitente se investiga y se corrige o se elimina, pero nunca se reintenta automáticamente para ocultar el problema. Las pruebas redundantes se eliminan: dos pruebas que fallan siempre juntas aportan la información de una sola y duplican el costo de mantenerla. Cuando un defecto llega a producción, la respuesta no es solo corregirlo, sino incorporar la prueba que lo habría detectado, en el nivel más bajo de la pirámide que resulte capaz de hacerlo.

---

## 6. Síntesis

La estrategia descrita articula cuatro niveles de prueba con propósitos claramente diferenciados, evitando la duplicación de esfuerzo que se produce cuando una misma verificación se repite en varios niveles sin agregar información. La base de la pirámide concentra la verificación exhaustiva de las reglas de negocio con pruebas rápidas e idempotentes, mientras que los niveles superiores se reservan para validar la integración real entre componentes y los flujos críticos de cara al usuario.

Esa distribución es la que permite que el pipeline entregue retroalimentación en segundos durante la etapa de commit y, al mismo tiempo, sostenga gates de calidad rigurosos antes de que una versión alcance producción. La separación entre Surefire y Failsafe, los perfiles diferenciados de Maven y el aislamiento de la suite E2E no son decisiones de configuración accesorias, sino los mecanismos concretos mediante los cuales se sostiene ese equilibrio entre velocidad y profundidad.

Finalmente, los mecanismos de recuperación documentados en el deployment pipeline completan la estrategia reconociendo una realidad ineludible: ninguna suite de pruebas, por completa que sea, detecta la totalidad de los defectos antes del despliegue. Los defectos latentes que solo se manifiestan bajo tráfico real exigen que la capacidad de revertir con rapidez y de forma verificada sea considerada parte integrante de la estrategia de calidad, y no un procedimiento de emergencia ajeno a ella.
