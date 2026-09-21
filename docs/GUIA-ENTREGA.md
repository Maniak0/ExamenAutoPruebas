# Guía de Entrega

Pasos concretos para publicar el repositorio, ejecutar los pipelines y capturar las pantallas que exigen las actividades.

---

## Paso 1 — Verificar el proyecto en tu equipo

**Requisitos:** JDK 17 o superior, Maven 3.9 o superior y Git.

```bash
java -version      # debe mostrar 17 o superior
mvn -version
git --version
```

Si falta alguno, en Windows la vía más simple es instalar [Temurin JDK 17](https://adoptium.net), [Maven](https://maven.apache.org/download.cgi) y [Git for Windows](https://git-scm.com/download/win), que incluye **Git Bash**, la terminal donde se ejecutan los scripts de `infra/`.

Desde la carpeta del proyecto:

```bash
mvn clean verify
```

Este comando compila, ejecuta las pruebas unitarias, las de integración, las de aceptación y los escenarios BDD, y aplica el Quality Gate de cobertura. **La primera ejecución descarga las dependencias y puede tardar varios minutos.**

> Los archivos de este proyecto ya vienen verificados: las 37 expectativas de las pruebas unitarias fueron comprobadas contra el código real (ver `docs/evidencias/EV-02`). Si aun así apareciera un error de compilación en alguna prueba, el mensaje de Maven indica el archivo y la línea exactos.

**Capturas útiles de este paso:**
- La salida de `mvn clean verify` mostrando `BUILD SUCCESS` con el resumen `Tests run: ...`
- El reporte de cobertura abriendo `target/site/jacoco/index.html` en el navegador
- El reporte BDD abriendo `target/cucumber-report.html`

---

## Paso 2 — Publicar el repositorio en GitHub

El repositorio Git ya está creado, con todo el historial GitFlow, sus ramas y el tag `v1.0.0`.

1. Crear un repositorio **vacío** en GitHub (sin README, sin .gitignore, sin licencia), por ejemplo `automatizacion-pruebas-iplacex`.

2. Vincularlo y subir **todas las ramas y tags**:

```bash
git remote add origin https://github.com/TU-USUARIO/automatizacion-pruebas-iplacex.git
git push -u origin --all
git push origin --tags
```

3. Verificar en GitHub que aparezcan las ramas `main`, `develop` y las `feature/*`, más el tag `v1.0.0`.

**Capturas útiles:**
- La vista **Insights → Network** de GitHub, que dibuja el grafo de ramas y merges
- La lista de ramas y el tag `v1.0.0` en la página del repositorio

---

## Paso 3 — Evidencia del pipeline de CI (Actividad 2)

Al hacer `push`, **GitHub Actions ejecuta el pipeline automáticamente**. No requiere instalar nada.

1. Entrar a la pestaña **Actions** del repositorio.
2. Abrir la ejecución más reciente de **"CI - Build y Pruebas Automatizadas"**.
3. Esperar a que los tres jobs queden en verde.

**Capturas exigidas por la actividad:**
- La vista general con los tres jobs en verde: *Build + Pruebas Unitarias*, *Pruebas de Integración + Aceptación*, *Quality Gate + Empaquetado*
- El detalle del paso **"Ejecutar PRUEBAS UNITARIAS"** desplegado, mostrando la salida de Maven
- El detalle del paso **"Ejecutar PRUEBAS DE INTEGRACIÓN y ACEPTACIÓN"** desplegado
- La sección **Artifacts** al pie, con los reportes y el artefacto publicados

---

## Paso 4 — Evidencia del deployment pipeline y del rollback (Actividad 3)

1. Ir a **Actions → CD - Deployment Pipeline (Blue-Green / Canary / Rollback) → Run workflow**.

2. Ejecutarlo **tres veces**, para cubrir los tres escenarios:

| Ejecución | Versión | Estrategia | Simular fallo | Demuestra |
|---|---|---|---|---|
| 1 | `1.1.0` | `blue-green` | **no** | Despliegue exitoso sin downtime |
| 2 | `1.2.0` | `canary` | **no** | Canary progresivo 10→25→50→100% |
| 3 | `2.0.0` | `blue-green` | **sí** | **Rollback automático** |

3. En la tercera ejecución, abrir el paso **"Desplegar a producción"** y desplegar su salida.

**Capturas exigidas por la actividad:**
- El despliegue Blue-Green exitoso, con el switch de tráfico visible en el log
- El Canary mostrando la distribución medida en cada escalón
- **El rollback automático**: la degradación detectada, la reversión y la verificación posterior
- La sección **Artifacts** con la bitácora de auditoría de cada despliegue

---

## Paso 5 — Alternativa local si prefieres ejecutar en tu equipo

En **Git Bash** (no en CMD ni PowerShell), desde la carpeta del proyecto:

```bash
mvn clean package

./infra/preparar-ambiente.sh --version 1.0.0
./infra/acceptance-gate.sh   --version 1.1.0
./infra/deploy-blue-green.sh --version 1.1.0
./infra/deploy-canary.sh     --version 1.2.0
./infra/deploy-blue-green.sh --version 2.0.0 --fallo-tras 4   # rollback automático
./infra/rollback.sh                                           # rollback manual
./infra/limpiar.sh
```

Mientras el ambiente esté levantado puedes abrir `http://localhost:9080` en el navegador y capturar el portal funcionando, junto con `http://localhost:9080/version`, que muestra qué entorno está atendiendo en ese momento.

---

## Paso 6 — Armar la entrega

Las actividades piden repositorio en GitHub más evidencias. Conviene entregar:

1. **El enlace al repositorio de GitHub** (contiene `pom.xml`, los archivos de pipeline, los scripts y el README).
2. **Un documento con las capturas**, ordenadas por actividad:
   - *Actividad 1*: grafo de ramas (Insights → Network), tag `v1.0.0`, contenido de `pom.xml`
   - *Actividad 2*: ejecución verde del pipeline de CI, detalle de pruebas unitarias y de integración
   - *Actividad 3*: los tres escenarios de despliegue, con especial detalle en el rollback
3. **El README.md**, que ya cubre lo exigido en el apartado de documentación: descripción del proyecto, estrategia de pruebas, instrucciones de ejecución y referencia a las evidencias.

Los registros de `docs/evidencias/` sirven como respaldo adicional y pueden anexarse tal cual.

---

## Problemas frecuentes

**`mvn` no se reconoce como comando**
Maven no está en el PATH. Reinstalar agregando Maven a las variables de entorno, o usar la terminal que instala el propio Maven.

**La primera ejecución de `mvn` tarda mucho**
Es normal: está descargando las dependencias al repositorio local `~/.m2`. Las siguientes ejecuciones son rápidas.

**`/usr/bin/env: 'bash\r': No such file or directory`**
Finales de línea CRLF. El archivo `.gitattributes` del proyecto lo previene, pero si ocurre:
```bash
git config core.autocrlf false
git rm --cached -r . && git reset --hard
```

**`Permission denied` al ejecutar un script**
```bash
chmod +x infra/*.sh
```

**`Address already in use` al levantar el ambiente**
Quedaron instancias de una ejecución anterior:
```bash
./infra/limpiar.sh
```

**Las pruebas E2E con Selenium fallan**
Requieren Chrome instalado. No forman parte del pipeline principal: `mvn clean verify` no las ejecuta. Solo corren con `mvn verify -Pe2e`, y si no hay navegador se omiten automáticamente en lugar de fallar.
