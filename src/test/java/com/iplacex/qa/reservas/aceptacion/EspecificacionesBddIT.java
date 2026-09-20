package com.iplacex.qa.reservas.aceptacion;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * Runner de la suite BDD, ejecutado por Failsafe como parte del
 * <b>Acceptance Test Gate</b> del deployment pipeline.
 *
 * <p>Enlaza los escenarios Gherkin de {@code src/test/resources/features} con
 * las step definitions del paquete {@code pasos} y publica un reporte HTML en
 * {@code target/cucumber-report.html}, que el pipeline archiva como evidencia
 * de la ejecución (ME_5 §1.4.2: "reportes claros").</p>
 *
 * <p>Se ejecuta con:</p>
 * <pre>mvn verify -Paceptacion -Dtest.baseUrl=http://localhost:9081</pre>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.iplacex.qa.reservas.pasos")
@ConfigurationParameter(
        key = PLUGIN_PROPERTY_NAME,
        value = "pretty,"
                + "html:target/cucumber-report.html,"
                + "json:target/cucumber-report.json,"
                + "junit:target/failsafe-reports/TEST-cucumber.xml")
public class EspecificacionesBddIT {
    // El cuerpo permanece vacío: toda la configuración es declarativa.
}
