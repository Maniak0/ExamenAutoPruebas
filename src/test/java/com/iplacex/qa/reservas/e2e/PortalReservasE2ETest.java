package com.iplacex.qa.reservas.e2e;

import com.iplacex.qa.reservas.soporte.AmbientePruebas;

import io.github.bonigarcia.wdm.WebDriverManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NIVEL 4 - PRUEBAS END-TO-END sobre el portal web con Selenium WebDriver.
 *
 * <p>Estas pruebas operan el navegador igual que un usuario real: escriben en el
 * formulario, presionan el botón y verifican el mensaje resultante. Validan la
 * integración completa navegador → JavaScript → API → dominio → persistencia.</p>
 *
 * <p><b>Por qué están aisladas del pipeline principal:</b> son las pruebas más
 * lentas y frágiles de la pirámide. Incluirlas en cada commit contradice la
 * práctica de <i>fail fast</i> y desalienta el uso del pipeline (anti-patrón
 * "tests pesados en todas las ejecuciones", ME_5 §1.2.3). Por eso se ejecutan
 * solo bajo el perfil {@code e2e} o en el pipeline nocturno:</p>
 *
 * <pre>mvn verify -Pe2e</pre>
 *
 * <p>Si el agente no dispone de un navegador compatible, la suite se
 * <b>omite</b> mediante {@code Assumptions} en lugar de fallar: un agente sin
 * navegador es una limitación de infraestructura, no un defecto del producto.</p>
 */
@Tag("e2e")
@DisplayName("E2E - Portal web de reservas (Selenium)")
class PortalReservasE2ETest {

    private static WebDriver navegador;
    private static WebDriverWait espera;
    private static String baseUrl;

    @BeforeAll
    static void abrirNavegador() throws Exception {
        baseUrl = AmbientePruebas.baseUrl();

        try {
            WebDriverManager.chromedriver().setup();

            ChromeOptions opciones = new ChromeOptions();
            // Modo headless: indispensable para ejecutar en agentes de CI sin escritorio
            if (Boolean.parseBoolean(System.getProperty("selenium.headless", "true"))) {
                opciones.addArguments("--headless=new");
            }
            opciones.addArguments("--no-sandbox", "--disable-dev-shm-usage",
                    "--disable-gpu", "--window-size=1366,768");

            navegador = new ChromeDriver(opciones);
            espera = new WebDriverWait(navegador, Duration.ofSeconds(10));

        } catch (RuntimeException e) {
            // No hay navegador disponible: se omite la suite con un motivo claro
            Assumptions.abort("Navegador no disponible en este agente: " + e.getMessage());
        }
    }

    @AfterAll
    static void cerrarNavegador() {
        if (navegador != null) {
            navegador.quit();
        }
        AmbientePruebas.detener();
    }

    @Test
    @DisplayName("El portal carga y muestra el título y la versión desplegada")
    void elPortalCargaCorrectamente() {
        navegador.get(baseUrl);

        WebElement titulo = espera.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("titulo")));

        assertThat(titulo.getText()).isEqualTo("Sistema de Reservas Turisticas");
        assertThat(navegador.findElement(By.id("version-app")).getText())
                .contains("Version:");
    }

    @Test
    @DisplayName("Un usuario registra una reserva desde el formulario web")
    void usuarioRegistraReservaDesdeElFormulario() {
        navegador.get(baseUrl);

        // --- Dado que el usuario completa el formulario
        navegador.findElement(By.id("cliente")).sendKeys("Usuario Selenium");
        navegador.findElement(By.id("destino")).sendKeys("Torres del Paine");

        WebElement campoNoches = navegador.findElement(By.id("noches"));
        campoNoches.clear();
        campoNoches.sendKeys("4");

        // --- Cuando presiona el botón de reservar
        navegador.findElement(By.id("btn-reservar")).click();

        // --- Entonces el portal confirma la reserva con su identificador.
        // Se usa una espera explícita (nunca Thread.sleep) para sincronizar con
        // la respuesta asíncrona del fetch y evitar pruebas intermitentes.
        espera.until(ExpectedConditions.textToBePresentInElementLocated(
                By.id("mensaje"), "Reserva confirmada"));

        WebElement mensaje = navegador.findElement(By.id("mensaje"));

        assertThat(mensaje.getText())
                .startsWith("Reserva confirmada:")
                .contains("RES-");
    }

    @Test
    @DisplayName("El portal informa el error cuando faltan datos obligatorios")
    void elPortalInformaErroresDeValidacion() {
        navegador.get(baseUrl);

        // Se envía el formulario sin completar el nombre del cliente
        navegador.findElement(By.id("destino")).sendKeys("Arica");
        navegador.findElement(By.id("btn-reservar")).click();

        WebElement mensaje = espera.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("mensaje")));
        espera.until(driver -> !mensaje.getText().isBlank());

        assertThat(mensaje.getText()).startsWith("Error:");
    }
}
