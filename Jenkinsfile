// ============================================================================
//  ACTIVIDAD 2 - PIPELINE DE INTEGRACIÓN CONTINUA
//  Sistema de Reservas Turísticas - IPLACEX
// ----------------------------------------------------------------------------
//  Pipeline declarativo de Jenkins versionado junto al código fuente
//  ("pipeline as code", ME_5 §1.2.2), lo que permite revisar sus cambios en un
//  pull request y revertir la configuración igual que cualquier otro archivo.
//
//  ORDEN DE LAS ETAPAS (estrategia "fail fast", ME_5 §1.2.2):
//  las validaciones más baratas se ejecutan primero, de modo que un error de
//  compilación detiene el pipeline en segundos y no después de veinte minutos
//  de pruebas.
//
//      Checkout -> Build -> Análisis estático -> Pruebas unitarias
//               -> Pruebas de integración -> Quality Gate -> Empaquetado
// ============================================================================

pipeline {

    // Agente etiquetado: permite distribuir la carga entre ejecutores y, en una
    // instalación con Docker, trabajar sobre contenedores efímeros que
    // garantizan un entorno limpio en cada ejecución.
    agent any

    tools {
        maven 'Maven-3.9'
        jdk   'JDK-17'
    }

    options {
        // Evita builds colgados que bloqueen la cola de ejecutores
        timeout(time: 30, unit: 'MINUTES')
        // Conserva historial suficiente para analizar tendencias y flakiness
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        // Caché del repositorio Maven: acelera el build sin contaminar el entorno
        MAVEN_OPTS   = '-Dmaven.repo.local=.m2/repository -Xmx1024m'
        APP_NOMBRE   = 'reservas-automatizacion'
        APP_VERSION  = "1.0.${BUILD_NUMBER}"
    }

    stages {

        // ------------------------------------------------------------------
        // ETAPA 1: CHECKOUT - trae exactamente la revisión que disparó el build
        // ------------------------------------------------------------------
        stage('Checkout') {
            steps {
                echo "==> Obteniendo el código de la revisión disparadora"
                checkout scm
                script {
                    // Metadatos de trazabilidad: permiten responder
                    // "¿con qué versión exacta se ejecutó esta prueba?" (ME_2 §1.4)
                    env.GIT_COMMIT_CORTO = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()
                    env.GIT_RAMA = sh(
                        script: 'git rev-parse --abbrev-ref HEAD',
                        returnStdout: true
                    ).trim()
                }
                echo "    Rama: ${env.GIT_RAMA} | Commit: ${env.GIT_COMMIT_CORTO}"
            }
        }

        // ------------------------------------------------------------------
        // ETAPA 2: BUILD - compilación rápida sin ejecutar pruebas
        // ------------------------------------------------------------------
        stage('Build') {
            steps {
                echo "==> Compilando el proyecto (sin pruebas, para feedback inmediato)"
                sh 'mvn -B clean compile -DskipTests'
            }
        }

        // ------------------------------------------------------------------
        // ETAPA 3: ANÁLISIS ESTÁTICO - detecta problemas sin ejecutar el código
        // ------------------------------------------------------------------
        stage('Análisis Estático') {
            steps {
                echo "==> Verificando dependencias y advertencias del compilador"
                // Expone conflictos de versiones antes de que causen fallos
                // intermitentes difíciles de diagnosticar (ME_1 §2.6).
                sh 'mvn -B dependency:tree -Dverbose -DoutputFile=target/dependency-tree.txt'
            }
            post {
                always {
                    archiveArtifacts artifacts: 'target/dependency-tree.txt',
                                     allowEmptyArchive: true
                }
            }
        }

        // ------------------------------------------------------------------
        // ETAPA 4: PRUEBAS UNITARIAS (tipo de prueba 1 de 2 exigido)
        // Rápidas y aisladas: constituyen el gate de la etapa de commit.
        // ------------------------------------------------------------------
        stage('Pruebas Unitarias') {
            steps {
                echo "==> Ejecutando pruebas unitarias con JUnit 5 y Mockito"
                sh 'mvn -B test'
            }
            post {
                always {
                    // 'always' garantiza publicar el reporte incluso si fallan,
                    // que es justamente cuando más se necesita revisarlo.
                    junit testResults: 'target/surefire-reports/*.xml',
                          allowEmptyResults: false
                }
                failure {
                    echo "!! Pruebas unitarias fallidas: el pipeline se detiene aquí."
                }
            }
        }

        // ------------------------------------------------------------------
        // ETAPA 5: PRUEBAS DE INTEGRACIÓN (tipo de prueba 2 de 2 exigido)
        // Levantan el servidor HTTP real y validan la colaboración entre capas.
        // ------------------------------------------------------------------
        stage('Pruebas de Integración') {
            steps {
                echo "==> Ejecutando pruebas de integración con Failsafe"
                sh 'mvn -B verify -DskipUnitTests=false'
            }
            post {
                always {
                    junit testResults: 'target/failsafe-reports/*.xml',
                          allowEmptyResults: true
                }
            }
        }

        // ------------------------------------------------------------------
        // ETAPA 6: QUALITY GATE - umbral objetivo que detiene el pipeline
        // ------------------------------------------------------------------
        stage('Quality Gate - Cobertura') {
            steps {
                echo "==> Verificando el umbral mínimo de cobertura (80%)"
                // El goal jacoco:check hace fallar el build si la cobertura de
                // líneas cae bajo el umbral definido en el pom.xml.
                sh 'mvn -B jacoco:report jacoco:check'
            }
            post {
                always {
                    publishHTML(target: [
                        allowMissing         : true,
                        alwaysLinkToLastBuild: true,
                        keepAll              : true,
                        reportDir            : 'target/site/jacoco',
                        reportFiles          : 'index.html',
                        reportName           : 'Cobertura JaCoCo'
                    ])
                }
            }
        }

        // ------------------------------------------------------------------
        // ETAPA 7: EMPAQUETADO - genera el artefacto que recorrerá el deploy
        // ------------------------------------------------------------------
        stage('Empaquetado') {
            steps {
                echo "==> Generando artefacto versionado ${APP_NOMBRE}-${APP_VERSION}.jar"
                sh 'mvn -B package -DskipTests'

                script {
                    // Metadatos de auditoría del build (ME_6 §5.3.1)
                    def metadatos = """{
  "artefacto":   "${env.APP_NOMBRE}",
  "version":     "${env.APP_VERSION}",
  "build":       "${env.BUILD_NUMBER}",
  "commit":      "${env.GIT_COMMIT_CORTO}",
  "rama":        "${env.GIT_RAMA}",
  "marcaTiempo": "${new Date().format("yyyy-MM-dd'T'HH:mm:ssZ")}"
}"""
                    writeFile file: 'target/build-metadata.json', text: metadatos
                }
            }
            post {
                success {
                    // El artefacto se construye UNA sola vez y es el mismo que
                    // se promoverá por todos los ambientes ("build once, deploy many").
                    archiveArtifacts artifacts: 'target/*.jar, target/build-metadata.json',
                                     fingerprint: true
                }
            }
        }
    }

    // ======================================================================
    //  NOTIFICACIONES Y LIMPIEZA
    // ======================================================================
    post {
        success {
            echo """
            ===========================================================
             BUILD EXITOSO  #${env.BUILD_NUMBER}
             Rama    : ${env.GIT_RAMA}
             Commit  : ${env.GIT_COMMIT_CORTO}
             Artefacto listo para el deployment pipeline.
            ===========================================================
            """
            // En una instalación con el plugin de Slack configurado:
            // slackSend channel: '#ci', color: 'good',
            //           message: "Build OK ${env.JOB_NAME} #${env.BUILD_NUMBER}"
        }
        failure {
            echo """
            ===========================================================
             BUILD FALLIDO  #${env.BUILD_NUMBER}
             Revise los reportes de pruebas publicados en este build.
            ===========================================================
            """
            // slackSend channel: '#ci', color: 'danger',
            //           message: "Build FAIL ${env.JOB_NAME} #${env.BUILD_NUMBER}"
        }
        unstable {
            echo "Build inestable: hay pruebas fallidas sin detener la compilación."
        }
        always {
            // Limpieza del workspace: evita contaminación entre ejecuciones
            // sucesivas en el mismo agente (ME_3 §2.6).
            echo "Limpiando el workspace del agente"
            cleanWs(deleteDirs: true, notFailBuild: true)
        }
    }
}
