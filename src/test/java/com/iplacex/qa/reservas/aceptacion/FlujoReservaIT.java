package com.iplacex.qa.reservas.aceptacion;

import com.iplacex.qa.reservas.soporte.AmbientePruebas;
import com.iplacex.qa.reservas.soporte.ClienteHttp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NIVEL 3 - PRUEBAS DE ACEPTACIÓN ejecutadas por el <b>Acceptance Test Gate</b>
 * del deployment pipeline (ME_5 §1.4).
 *
 * <p>Estas pruebas responden a la pregunta del negocio —"¿puede un cliente
 * reservar, consultar y cancelar sin problemas?"— y no a detalles técnicos
 * internos. Constituyen la última barrera antes de que una versión avance a
 * producción.</p>
 *
 * <p>Siguiendo la buena práctica de <i>pruebas representativas, no exhaustivas</i>,
 * la suite cubre únicamente los flujos críticos que otorgan confianza rápida:
 * disponibilidad del servicio, flujo completo de reserva y protección de las
 * reglas de negocio.</p>
 *
 * <p>Se ejecuta contra el ambiente que el pipeline acaba de desplegar:</p>
 * <pre>mvn verify -Paceptacion -Dtest.baseUrl=http://localhost:9081</pre>
 */
@Tag("aceptacion")
@DisplayName("Aceptación - flujos críticos de negocio")
class FlujoReservaIT {

    private static ClienteHttp cliente;
    private static String baseUrl;

    @BeforeAll
    static void conectarAlAmbiente() throws Exception {
        baseUrl = AmbientePruebas.baseUrl();
        cliente = new ClienteHttp(baseUrl);
        System.out.println("[Acceptance Gate] Validando ambiente: " + baseUrl);
    }

    @Test
    @DisplayName("SMOKE: el servicio desplegado está operativo")
    void elServicioDesplegadoEstaOperativo() throws Exception {
        HttpResponse<String> respuesta = cliente.get("/health");

        assertThat(respuesta.statusCode())
                .as("El health check del ambiente desplegado debe responder 200")
                .isEqualTo(200);
        assertThat(respuesta.body()).contains("\"estado\":\"UP\"");
    }

    @Test
    @DisplayName("SMOKE: el portal web carga y expone el formulario de reserva")
    void elPortalWebCarga() throws Exception {
        HttpResponse<String> respuesta = cliente.get("/");

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.body()).contains("Sistema de Reservas Turisticas");
        assertThat(respuesta.body()).contains("id=\"form-reserva\"");
    }

    @Test
    @DisplayName("NEGOCIO: un cliente completa el ciclo reservar, consultar y cancelar")
    void clienteCompletaElCicloDeReserva() throws Exception {
        // --- Dado que un cliente solicita una estadía de una semana en enero
        String solicitud = """
                {"cliente":"Cliente Aceptacion","destino":"Isla de Pascua",
                 "fechaInicio":"2026-01-20","noches":7,"personas":2}
                """;

        // --- Cuando registra su reserva
        HttpResponse<String> creacion = cliente.post("/api/reservas", solicitud);

        // --- Entonces la reserva queda confirmada con la tarifa promocional correcta
        assertThat(creacion.statusCode()).isEqualTo(201);
        assertThat(creacion.body()).contains("\"estado\":\"CONFIRMADA\"");
        assertThat(creacion.body())
                .as("Enero aplica recargo de temporada alta y 7 noches aplican descuento")
                .contains("\"tarifaTotal\":708750.00");

        String id = ClienteHttp.extraerCampo(creacion.body(), "id");

        // --- Y el cliente puede consultarla posteriormente
        HttpResponse<String> consulta = cliente.get("/api/reservas/" + id);
        assertThat(consulta.statusCode()).isEqualTo(200);
        assertThat(consulta.body()).contains("Isla de Pascua");

        // --- Y puede cancelarla cuando lo requiera
        HttpResponse<String> cancelacion = cliente.delete("/api/reservas/" + id);
        assertThat(cancelacion.statusCode()).isEqualTo(200);
        assertThat(cancelacion.body()).contains("\"estado\":\"CANCELADA\"");
    }

    @Test
    @DisplayName("NEGOCIO: el sistema protege contra reservas duplicadas")
    void elSistemaProtegeContraDuplicados() throws Exception {
        String solicitud = """
                {"cliente":"Cliente Duplicado","destino":"Pucón",
                 "fechaInicio":"2026-08-15","noches":2,"personas":2}
                """;

        HttpResponse<String> primera = cliente.post("/api/reservas", solicitud);
        assertThat(primera.statusCode()).isEqualTo(201);

        HttpResponse<String> segunda = cliente.post("/api/reservas", solicitud);
        assertThat(segunda.statusCode())
                .as("Una segunda reserva idéntica debe ser rechazada con 409")
                .isEqualTo(409);
    }

    @Test
    @DisplayName("NEGOCIO: no se aceptan grupos que exceden la capacidad máxima")
    void rechazaGruposSobreLaCapacidadMaxima() throws Exception {
        String solicitud = """
                {"cliente":"Grupo Excedido","destino":"Chiloé",
                 "fechaInicio":"2026-09-01","noches":3,"personas":12}
                """;

        HttpResponse<String> respuesta = cliente.post("/api/reservas", solicitud);

        assertThat(respuesta.statusCode()).isEqualTo(400);
        assertThat(respuesta.body()).contains("8 personas");
    }
}
