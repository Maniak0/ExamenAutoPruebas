# Sistema de Reservas Turísticas — Automatización de Pruebas y Tuberías de Despliegue

**Asignatura:** Automatización de Pruebas
**Institución:** IPLACEX — Escuela de Informática y Telecomunicaciones
**Unidades:** I (Control de versiones y dependencias), II (Integración Continua), III (Tuberías de despliegue)

---

## 1. Descripción del proyecto

Este repositorio implementa un sistema de reservas turísticas que sirve como **sistema bajo prueba** para construir, de extremo a extremo, una plataforma de automatización de calidad:

- un repositorio **Git con flujo de ramas GitFlow**;
- un proyecto **Maven** con dependencias de prueba declaradas explícitamente;
- un **pipeline de Integración Continua** con pruebas unitarias y de integración;
- un **deployment pipeline** con Acceptance Test Gate, despliegues **Blue-Green** y **Canary**, y **rollback** automático y manual.

La aplicación expone una API REST y un portal web mínimo. Está escrita **únicamente con el JDK**, sin dependencias de runtime, por una decisión deliberada: mantiene el árbol de dependencias limitado a librerías de prueba, reduce la superficie de vulnerabilidades que reportaría un escaneo SAST y permite desplegarla en cualquier agente sin instalar nada adicional.

### Funcionalidad del sistema

| Endpoint | Método | Descripción |
|---|---|---|
| `/health` | GET | Estado de la instancia. Lo consultan los health checks del pipeline |
| `/version` | GET | Versión y entorno (azul / verde / canary) que atiende |
| `/api/reservas` | GET | Lista las reservas registradas |
| `/api/reservas` | POST | Crea una reserva aplicando las reglas de negocio |
| `/api/reservas/{id}` | GET | Consulta una reserva |
| `/api/reservas/{id}` | DELETE | Cancela una reserva |
| `/` | GET | Portal web (superficie de las pruebas E2E con Selenium) |

### Reglas de negocio verificadas por las pruebas

- Tarifa base de **$45.000** por noche y por persona.
- Recargo del **25%** en temporada alta (diciembre, enero, febrero).
- Descuento del **10%** en estadías de **7 noches o más**.
- Máximo de **8 personas** por reserva.
- Un cliente **no puede tener dos reservas vigentes** para la misma fecha.

---

## 2. Estructura del repositorio

```
proyecto-qa-reservas/
├── pom.xml                        # Actividad 1: dependencias y ciclo de construcción
├── Jenkinsfile                    # Actividad 2: pipeline de CI
├── .gitlab-ci.yml                 # Actividad 2: pipeline equivalente en GitLab
├── Jenkinsfile.deploy             # Actividad 3: deployment pipeline
├── .github/workflows/
│   ├── ci.yml                     # Actividad 2 ejecutable en GitHub Actions
│   └── deploy.yml                 # Actividad 3 ejecutable en GitHub Actions
├── .gitattributes                 # Normaliza fin de línea (LF) para Windows/Linux
│
├── src/main/java/com/iplacex/qa/reservas/
│   ├── modelo/                    # Reserva, EstadoReserva
│   ├── servicio/                  # CalculadoraTarifas, ServicioReservas
│   ├── repositorio/               # Interfaz + implementación en memoria
│   └── api/                       # ServidorReservas, Main
│
├── src/test/java/com/iplacex/qa/reservas/
│   ├── unit/                      # Nivel 1: pruebas unitarias (JUnit 5 + Mockito)
│   ├── integracion/               # Nivel 2: pruebas de integración (Failsafe)
│   ├── aceptacion/                # Nivel 3: pruebas de aceptación + runner BDD
│   ├── e2e/                       # Nivel 4: pruebas E2E (Selenium)
│   ├── pasos/                     # Step definitions de Cucumber
│   └── soporte/                   # ClienteHttp, AmbientePruebas
│
├── src/test/resources/features/   # Escenarios BDD en Gherkin (español)
│
├── infra/                         # Actividad 3: tubería de despliegue
│   ├── Router.java                # Balanceador con soporte Blue-Green y Canary
│   ├── lib-despliegue.sh          # Librería común
│   ├── preparar-ambiente.sh       # Aprovisiona el ambiente
│   ├── acceptance-gate.sh         # Acceptance Test Gate
│   ├── deploy-blue-green.sh       # Despliegue Blue-Green + rollback automático
│   ├── deploy-canary.sh           # Despliegue Canary + rollback parcial
│   ├── rollback.sh                # Rollback manual asistido
│   ├── smoke-test.sh              # Smoke tests
│   ├── aceptacion-http.sh         # Suite de aceptación vía HTTP
│   └── limpiar.sh                 # Limpieza del ambiente
│
└── docs/
    ├── ESTRATEGIA-PRUEBAS.md      # Estrategia de pruebas en detalle
    └── evidencias/                # Logs de las ejecuciones reales
```

---

## 3. Actividad 1 — Git con GitFlow y proyecto Maven

### 3.1 Flujo de ramas adoptado: GitFlow

Se escogió **GitFlow** por sobre Trunk-Based porque el proyecto necesita sostener versiones liberadas y demostrar despliegues y rollbacks sobre versiones identificables. GitFlow entrega una rama estable (`main`) que siempre refleja lo que está en producción, lo que hace que la pregunta "¿a qué versión debo revertir?" tenga una respuesta inequívoca.

| Rama | Propósito | Origen | Destino |
|---|---|---|---|
| `main` | Refleja lo desplegado en producción. Cada commit lleva un tag | — | — |
| `develop` | Integra el trabajo terminado y aún no liberado | `main` | `release/*` |
| `feature/*` | Desarrollo aislado de una funcionalidad | `develop` | `develop` |
| `release/*` | Estabilización previa a la liberación | `develop` | `main` + `develop` |
| `hotfix/*` | Corrección urgente sobre producción | `main` | `main` + `develop` |

Los merges se realizan con `--no-ff` de forma deliberada: conservan el commit de merge y con ello la trazabilidad de qué cambios entraron juntos y por qué, información que un merge fast-forward destruye.

**Comandos del flujo:**

```bash
# Iniciar una funcionalidad
git checkout develop
git checkout -b feature/pruebas-unitarias
# ... trabajo y commits atómicos ...
git checkout develop
git merge --no-ff feature/pruebas-unitarias

# Preparar una liberación
git checkout -b release/1.0.0 develop
git checkout main
git merge --no-ff release/1.0.0
git tag -a v1.0.0 -m "Versión 1.0.0"
git checkout develop
git merge --no-ff release/1.0.0

# Corrección urgente en producción
git checkout -b hotfix/1.0.1 main
# ... corrección ...
git checkout main && git merge --no-ff hotfix/1.0.1 && git tag -a v1.0.1 -m "Hotfix 1.0.1"
git checkout develop && git merge --no-ff hotfix/1.0.1
```

Para ver el historial de ramas y merges de este repositorio:

```bash
git log --graph --oneline --all --decorate
```

### 3.2 Dependencias de prueba configuradas en `pom.xml`

| Dependencia | Versión | Nivel de prueba que habilita |
|---|---|---|
| `junit-jupiter` | 5.10.2 | Unitarias, integración y aceptación |
| `mockito-core` / `mockito-junit-jupiter` | 5.11.0 | Dobles de prueba para aislar dependencias |
| `assertj-core` | 3.25.3 | Aserciones fluidas y legibles |
| `testng` | 7.9.0 | Framework alternativo disponible |
| `selenium-java` | 4.19.1 | Pruebas E2E sobre el portal web |
| `webdrivermanager` | 5.7.0 | Aprovisiona el driver del navegador |
| `cucumber-java` + engine | 7.15.0 | Escenarios BDD en Gherkin |

Plugins del ciclo de construcción:

| Plugin | Función |
|---|---|
| `maven-surefire-plugin` | Ejecuta las **pruebas unitarias** en la fase `test` |
| `maven-failsafe-plugin` | Ejecuta las pruebas de **integración y aceptación** en `verify` |
| `jacoco-maven-plugin` | Mide la cobertura y **detiene el build** si baja del 80% |
| `maven-jar-plugin` | Genera el artefacto ejecutable que recorre el deployment pipeline |

**Decisiones de configuración y su fundamento:**

- **Versiones explícitas en `<properties>`.** Una versión flotante hace que el mismo commit produzca builds distintos en días distintos, que es la causa más difícil de diagnosticar de una prueba intermitente.
- **Separación Surefire / Failsafe.** Mantiene la etapa de commit en segundos. Si las pruebas lentas corrieran en cada compilación, el equipo dejaría de ejecutar el pipeline localmente.
- **Perfiles `e2e` y `aceptacion`.** Permiten que la misma suite se ejecute contra un ambiente embebido o contra staging, sin duplicar código de prueba.

---

## 4. Actividad 2 — Pipeline de Integración Continua

El mismo proceso está implementado en tres tecnologías, porque un pipeline bien diseñado describe el **proceso** y no la herramienta:

| Archivo | Servidor de CI |
|---|---|
| `Jenkinsfile` | Jenkins |
| `.gitlab-ci.yml` | GitLab CI/CD |
| `.github/workflows/ci.yml` | GitHub Actions |

### Etapas del pipeline

```
Checkout → Build → Análisis estático → PRUEBAS UNITARIAS
        → PRUEBAS DE INTEGRACIÓN → Quality Gate (cobertura ≥ 80%) → Empaquetado
```

Las etapas están ordenadas según la estrategia **fail fast**: las validaciones más baratas se ejecutan primero, de modo que un error de compilación detiene el pipeline en segundos en lugar de hacerlo tras veinte minutos de pruebas.

### Los dos tipos de prueba exigidos

**1. Pruebas unitarias** (`src/test/java/.../unit/`, ejecutadas por Surefire)

Verifican la lógica de negocio de forma aislada. El repositorio se sustituye por un mock de Mockito, por lo que no requieren base de datos, red ni ambiente. Son idempotentes y se ejecutan en milisegundos.

**2. Pruebas de integración** (`src/test/java/.../integracion/`, ejecutadas por Failsafe)

Levantan el servidor HTTP real sobre un puerto libre asignado por el sistema operativo y validan mediante peticiones auténticas que la API, el servicio de negocio y el repositorio colaboren correctamente. El puerto dinámico evita que dos ejecuciones concurrentes del pipeline colisionen en el mismo agente.

---

## 5. Actividad 3 — Deployment Pipeline

### 5.1 Arquitectura del ambiente

```
                   ┌──────────────────────────┐
   Usuarios  ───►  │  BALANCEADOR  :9080      │   ← punto de entrada estable
                   └────────────┬─────────────┘
                                │  enrutamiento dinámico
            ┌───────────────────┼───────────────────┐
            ▼                   ▼                   ▼
    ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
    │  AZUL  :9081  │   │  VERDE :9082  │   │ CANARY :9084  │
    │  (producción) │   │  (candidata)  │   │  (% parcial)  │
    └───────────────┘   └───────────────┘   └───────────────┘

            ┌───────────────┐
            │ STAGING :9083 │  ← Acceptance Test Gate
            └───────────────┘
```

El balanceador (`infra/Router.java`) relee su configuración **en cada petición**, lo que permite conmutar el tráfico y ajustar el peso del canary **en caliente**, sin reiniciar nada y sin interrumpir a los usuarios. Cumple el papel que en un entorno productivo desempeñaría un Ingress Controller de Kubernetes o un balanceador NGINX.

### 5.2 Flujo completo

```
Commit Stage → Deploy a STAGING → ACCEPTANCE GATE → Deploy a PRODUCCIÓN
                                                     (Blue-Green | Canary)
                                                              │
                                              Ventana de monitoreo
                                                              │
                                                    ¿degradación? → ROLLBACK
```

Principio aplicado en todo el flujo: **build once, deploy many**. El artefacto se construye una sola vez en el Commit Stage y ese mismo `.jar` se promueve por todos los ambientes. Recompilar entre ambientes introduciría diferencias entre lo que se probó y lo que se desplegó.

### 5.3 Estrategia Blue-Green

1. Se identifica cuál entorno está activo y cuál queda como candidato.
2. La versión nueva se despliega en el entorno **inactivo**: los usuarios no se ven afectados en ningún momento.
3. **Gate de salud**: health check contra el candidato.
4. **Smoke tests** sobre el candidato.
5. **Switch de tráfico**: instantáneo y sin downtime. El entorno anterior queda en **stand-by**.
6. **Ventana de monitoreo**: si la versión nueva se degrada, se ejecuta un **rollback automático**.

El paso 6 existe por una razón concreta: los pasos 3 y 4 solo detectan defectos presentes al arrancar. Un defecto que se manifiesta al recibir tráfico real atravesaría ambos gates sin ser detectado, y es precisamente el escenario en que "el pipeline pasa" pero producción falla.

### 5.4 Estrategia Canary

El tráfico se incrementa en escalones de **10% → 25% → 50% → 100%**, verificando la salud antes de cada aumento. Ante una anomalía se ejecuta un **rollback parcial**: el peso del canary se lleva a 0 y la versión estable recupera todo el tráfico.

Diferencia esencial con Blue-Green: el riesgo queda acotado desde el inicio. Una versión defectuosa afecta al 10% de los usuarios durante un lapso breve, en lugar de al 100% de forma instantánea.

### 5.5 Mecanismos de rollback implementados

| Tipo | Script | Cuándo se activa |
|---|---|---|
| **Preventivo** | `deploy-blue-green.sh` | El candidato no supera health check o smoke tests. El tráfico nunca se mueve |
| **Automático** | `deploy-blue-green.sh` | Degradación detectada en la ventana de monitoreo posterior al switch |
| **Parcial** | `deploy-canary.sh` | Anomalía en un escalón. El canary se retira del enrutamiento |
| **Manual asistido** | `rollback.sh` | Un operador detecta un problema que los health checks no cubren |

El rollback manual **valida la salud del destino antes de conmutar**: revertir hacia una instancia caída convertiría un incidente parcial en una caída total del servicio.

---

## 6. Cómo ejecutar las pruebas

**Requisitos:** JDK 17 o superior y Maven 3.9 o superior.

```bash
# Solo pruebas unitarias (rápido, segundos)
mvn test

# Pruebas unitarias + integración + aceptación + BDD, con Quality Gate de cobertura
mvn clean verify

# Suite E2E con Selenium (requiere Chrome instalado)
mvn verify -Pe2e

# Pruebas de aceptación contra un ambiente ya desplegado
mvn verify -Paceptacion -Dtest.baseUrl=http://localhost:9083

# Generar el artefacto desplegable
mvn clean package
```

**Reportes generados:**

| Reporte | Ubicación |
|---|---|
| Pruebas unitarias | `target/surefire-reports/` |
| Integración y aceptación | `target/failsafe-reports/` |
| Cobertura JaCoCo | `target/site/jacoco/index.html` |
| Escenarios BDD | `target/cucumber-report.html` |

---

## 7. Cómo ejecutar los pipelines

### 7.1 Deployment pipeline en local

Los scripts funcionan en Linux, macOS y en **Windows a través de Git Bash** (incluido en Git for Windows).

```bash
chmod +x infra/*.sh          # solo la primera vez, en Linux/macOS

mvn clean package            # construir el artefacto a desplegar

./infra/preparar-ambiente.sh --version 1.0.0     # levantar el ambiente
./infra/acceptance-gate.sh   --version 1.1.0     # Acceptance Test Gate

# --- Despliegue Blue-Green exitoso ---
./infra/deploy-blue-green.sh --version 1.1.0

# --- Despliegue Canary progresivo ---
./infra/deploy-canary.sh --version 1.2.0

# --- Demostrar el ROLLBACK AUTOMÁTICO (versión con defecto latente) ---
./infra/deploy-blue-green.sh --version 2.0.0 --fallo-tras 4

# --- Demostrar el ROLLBACK PARCIAL del canary ---
./infra/deploy-canary.sh --version 3.0.0 --fallo-tras 8

# --- Rollback manual asistido ---
./infra/rollback.sh --estado     # consultar el ambiente sin modificarlo
./infra/rollback.sh              # revertir al entorno en stand-by

./infra/limpiar.sh               # liberar todos los puertos
```

La opción `--fallo-tras N` despliega una versión que arranca sana y comienza a fallar pasados N segundos. Reproduce un **defecto latente** y permite demostrar el rollback automático con una falla real, no simulada.

Toda la actividad queda registrada en `infra/estado/historial-despliegues.log`, la bitácora auditable del ambiente.

### 7.2 Pipelines en un servidor de CI

**GitHub Actions** (no requiere instalación): al subir el repositorio, el workflow de CI se ejecuta automáticamente en cada push. El deployment pipeline se lanza desde la pestaña **Actions → CD - Deployment Pipeline → Run workflow**, eligiendo versión, estrategia y si se desea demostrar el rollback.

**Jenkins:** crear un job *Pipeline*, apuntarlo a este repositorio e indicar `Jenkinsfile` (CI) o `Jenkinsfile.deploy` (despliegue). Requiere configurar en *Global Tool Configuration* las herramientas con los nombres `Maven-3.9` y `JDK-17`.

**GitLab CI/CD:** el archivo `.gitlab-ci.yml` es detectado automáticamente al subir el proyecto.

---

## 8. Evidencias de ejecución

Los registros de las ejecuciones reales están en **`docs/evidencias/`**:

| Archivo | Evidencia |
|---|---|
| `EV-01-gitflow-historial.log` | Historial de ramas, merges y tags de GitFlow |
| `EV-02-verificacion-logica-pruebas.log` | Verificación de las 37 expectativas de las pruebas unitarias |
| `EV-03-preparar-ambiente.log` | Aprovisionamiento del ambiente y smoke tests |
| `EV-04-acceptance-gate.log` | Acceptance Test Gate: 14/14 criterios de negocio |
| `EV-05-blue-green-exitoso.log` | Despliegue Blue-Green sin downtime |
| `EV-06-blue-green-rollback-automatico.log` | **Rollback automático** ante degradación |
| `EV-07-canary-exitoso.log` | Canary progresivo con reparto de tráfico medido |
| `EV-08-canary-rollback-parcial.log` | **Rollback parcial** del canary |
| `EV-09-rollback-manual.log` | Rollback manual asistido |
| `EV-10-estado-final-ambiente.log` | Inventario final del ambiente |
| `EV-11-bitacora-auditoria.log` | Bitácora completa de auditoría |

Consulte `docs/evidencias/INDICE.md` para la lectura comentada de cada evidencia.

---

## 9. Documentación complementaria

- **`docs/ESTRATEGIA-PRUEBAS.md`** — estrategia de pruebas en detalle: pirámide aplicada, criterios de qué automatizar en cada nivel, manejo de pruebas intermitentes y umbrales de los quality gates.
- **`docs/evidencias/INDICE.md`** — guía de lectura de las evidencias.

---

## 10. Autor

**Gabriel Améstica** — Ingeniería de Software, IPLACEX.
