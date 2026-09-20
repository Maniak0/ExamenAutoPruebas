package com.iplacex.qa.reservas.integracion;

import com.iplacex.qa.reservas.api.ServidorReservas;
import com.iplacex.qa.reservas.soporte.AmbientePruebas;
import com.iplacex.qa.reservas.soporte.ClienteHttp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NIVEL 2 - PRUEBAS DE INTEGRACIÓN de la API REST.
 *
 * <p>A diferencia de las unitarias, aquí <b>no se sustituye nada</b>: se levanta
 * el servidor HTTP real y se verifica que la capa API, el servicio de negocio y
 * el repositorio colaboren correctamente a través de peticiones HTTP auténticas.</p>
 *
 * <p>El sufijo {@code IT} hace que estas pruebas sean ejecutadas por
 * <b>maven-failsafe-plugin</b> en la fase {@code verify} y no por Surefire en la
 * fase {@code test}. Esa separación es la que mantiene rápida la etapa de commit
 * del pipeline, tal como recomienda el Módulo de Estudio 5.</p>
 *
 * <p>El servidor se levanta en un <b>puerto libre asignado por el sistema
 * operativo</b>, de modo que varias ejecuciones concurrentes del pipeline en el
 * mismo agente no colisionan entre sí.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Integración - API REST de reservas")
class ApiReservasIT {

    private ServidorReservas servidor;
    private ClienteHttp cliente;
    private String idReservaCreada;

    /** Fixture de suite: levanta el ambiente una sola vez para toda la clase. */
    @BeforeAll
    void levantarAmbiente() throws IOException {
        int puerto = AmbientePruebas.puertoLibre();
        servidor = new ServidorReservas(puerto, "1.0.0", "integracion", false);
        servidor.iniciar();
        cliente = new ClienteHttp("http://localhost:" + puerto);
    }

    /** Libera el puerto pase lo que pase con las pruebas. */
    @AfterAll
    void derribarAmbiente() {
        if (servidor != null) {
            servidor.detener();
        }
    }

    @Test
    @Order(1)
    @DisplayName("El endpoint /health reporta la instancia como disponible")
    void healthReportaInstanciaDisponible() throws Exception {
        HttpResponse<String> respuesta = cliente.get("/health");

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.body()).contains("\"estado\":\"UP\"");
        assertThat(respuesta.headers().firstValue("X-App-Version")).contains("1.0.0");
    }

    @Test
    @Order(2)
    @DisplayName("La lista de reservas inicia vacía en un ambiente limpio")
    void listaIniciaVacia() throws Exception {
        HttpResponse<String> respuesta = cliente.get("/api/reservas");

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.body()).isEqualTo("[]");
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/reservas crea la reserva y responde 201 con la tarifa calculada")
    void creaReservaMedianteApi() throws Exception {
        String cuerpo = """
                {"cliente":"Gabriel Améstica","destino":"San Pedro de Atacama",
                 "fechaInicio":"2026-06-10","noches":3,"personas":2}
                """;

        HttpResponse<String> respuesta = cliente.post("/api/reservas", cuerpo);

        assertThat(respuesta.statusCode()).isEqualTo(201);
        assertThat(respuesta.body()).contains("\"estado\":\"CONFIRMADA\"");
        // Integración real de las tres capas: la tarifa la calculó el dominio
        assertThat(respuesta.body()).contains("\"tarifaTotal\":270000.00");

        idReservaCreada = ClienteHttp.extraerCampo(respuesta.body(), "id");
        assertThat(idReservaCreada).startsWith("RES-");
    }

    @Test
    @Order(4)
    @DisplayName("La reserva creada queda efectivamente persistida y es recuperable")
    void recuperaReservaPersistida() throws Exception {
        HttpResponse<String> respuesta = cliente.get("/api/reservas/" + idReservaCreada);

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.body()).contains(idReservaCreada);
        assertThat(respuesta.body()).contains("San Pedro de Atacama");
    }

    @Test
    @Order(5)
    @DisplayName("Un duplicado para el mismo cliente y fecha responde 409 Conflict")
    void rechazaDuplicadoConConflict() throws Exception {
        String cuerpo = """
                {"cliente":"Gabriel Améstica","destino":"Otro destino",
                 "fechaInicio":"2026-06-10","noches":2,"personas":1}
                """;

        HttpResponse<String> respuesta = cliente.post("/api/reservas", cuerpo);

        assertThat(respuesta.statusCode()).isEqualTo(409);
        assertThat(respuesta.body()).contains("ya posee una reserva vigente");
    }

    @Test
    @Order(6)
    @DisplayName("Datos inválidos son rechazados con 400 Bad Request")
    void rechazaDatosInvalidosConBadRequest() throws Exception {
        String cuerpo = """
                {"cliente":"Marta Pérez","destino":"Pucón",
                 "fechaInicio":"2026-07-01","noches":0,"personas":2}
                """;

        HttpResponse<String> respuesta = cliente.post("/api/reservas", cuerpo);

        assertThat(respuesta.statusCode()).isEqualTo(400);
        assertThat(respuesta.body()).contains("noches");
    }

    @Test
    @Order(7)
    @DisplayName("DELETE cancela la reserva y el cambio de estado se refleja en la consulta")
    void cancelaReservaYReflejaElCambio() throws Exception {
        HttpResponse<String> cancelacion = cliente.delete("/api/reservas/" + idReservaCreada);
        assertThat(cancelacion.statusCode()).isEqualTo(200);
        assertThat(cancelacion.body()).contains("\"estado\":\"CANCELADA\"");

        // Verificación cruzada: el estado persistió, no fue solo la respuesta
        HttpResponse<String> consulta = cliente.get("/api/reservas/" + idReservaCreada);
        assertThat(consulta.body()).contains("\"estado\":\"CANCELADA\"");
    }

    @Test
    @Order(8)
    @DisplayName("Tras cancelar, el cliente puede volver a reservar la misma fecha")
    void permiteReservarNuevamenteTrasCancelacion() throws Exception {
        String cuerpo = """
                {"cliente":"Gabriel Améstica","destino":"Valle del Elqui",
                 "fechaInicio":"2026-06-10","noches":2,"personas":2}
                """;

        HttpResponse<String> respuesta = cliente.post("/api/reservas", cuerpo);

        assertThat(respuesta.statusCode()).isEqualTo(201);
    }
}
