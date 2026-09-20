package com.iplacex.qa.reservas.pasos;

import com.iplacex.qa.reservas.soporte.AmbientePruebas;
import com.iplacex.qa.reservas.soporte.ClienteHttp;

import io.cucumber.java.Before;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import io.cucumber.java.es.Y;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions de la especificación BDD.
 *
 * <p>Aplican la <b>separación de responsabilidades</b> recomendada en el Módulo
 * de Estudio 4: el archivo {@code .feature} declara el "qué" en lenguaje de
 * negocio y esta clase resuelve el "cómo" técnico, delegando el detalle del
 * protocolo HTTP en {@link ClienteHttp}. Así los escenarios siguen siendo
 * legibles para perfiles no técnicos.</p>
 */
public class PasosReserva {

    private ClienteHttp cliente;
    private HttpResponse<String> ultimaRespuesta;
    private String idReservaVigente;

    @Before
    public void prepararContexto() throws Exception {
        cliente = AmbientePruebas.cliente();
    }

    // ==================== Dado (precondiciones) ====================

    @Dado("que el servicio de reservas está disponible")
    public void elServicioEstaDisponible() throws Exception {
        HttpResponse<String> salud = cliente.get("/health");

        assertThat(salud.statusCode())
                .as("El ambiente bajo prueba debe estar operativo antes de ejecutar escenarios")
                .isEqualTo(200);
    }

    @Dado("que el cliente {string} tiene una reserva para el {string}")
    public void elClienteTieneUnaReserva(String nombreCliente, String fecha) throws Exception {
        String cuerpo = construirSolicitud(nombreCliente, "Destino Base", fecha, 2, 2);
        HttpResponse<String> respuesta = cliente.post("/api/reservas", cuerpo);

        assertThat(respuesta.statusCode()).isEqualTo(201);
        idReservaVigente = ClienteHttp.extraerCampo(respuesta.body(), "id");
    }

    // ==================== Cuando (acciones) ====================

    @Cuando("el cliente {string} reserva {string} para el {string} por {int} noches y {int} personas")
    public void elClienteReserva(String nombreCliente, String destino, String fecha,
                                 int noches, int personas) throws Exception {
        String cuerpo = construirSolicitud(nombreCliente, destino, fecha, noches, personas);
        ultimaRespuesta = cliente.post("/api/reservas", cuerpo);

        if (ultimaRespuesta.statusCode() == 201) {
            idReservaVigente = ClienteHttp.extraerCampo(ultimaRespuesta.body(), "id");
        }
    }

    @Cuando("solicita la cancelación de su reserva")
    public void solicitaLaCancelacion() throws Exception {
        ultimaRespuesta = cliente.delete("/api/reservas/" + idReservaVigente);
    }

    // ==================== Entonces (verificaciones) ====================

    @Entonces("la reserva queda confirmada")
    public void laReservaQuedaConfirmada() {
        assertThat(ultimaRespuesta.statusCode()).isEqualTo(201);
        assertThat(ultimaRespuesta.body()).contains("\"estado\":\"CONFIRMADA\"");
    }

    @Entonces("la reserva queda cancelada")
    public void laReservaQuedaCancelada() {
        assertThat(ultimaRespuesta.statusCode()).isEqualTo(200);
        assertThat(ultimaRespuesta.body()).contains("\"estado\":\"CANCELADA\"");
    }

    @Entonces("el sistema rechaza la solicitud por conflicto")
    public void elSistemaRechazaPorConflicto() {
        assertThat(ultimaRespuesta.statusCode()).isEqualTo(409);
        assertThat(ultimaRespuesta.body()).contains("ya posee una reserva vigente");
    }

    @Entonces("el sistema responde con el código {int}")
    public void elSistemaRespondeConElCodigo(int codigoEsperado) {
        assertThat(ultimaRespuesta.statusCode()).isEqualTo(codigoEsperado);
    }

    @Y("el valor total de la reserva es {int}")
    public void elValorTotalDeLaReservaEs(int valorEsperado) {
        assertThat(ultimaRespuesta.body())
                .as("La tarifa calculada debe coincidir con la regla de negocio acordada")
                .contains("\"tarifaTotal\":" + valorEsperado + ".00");
    }

    // ==================== Utilidades ====================

    private String construirSolicitud(String nombreCliente, String destino, String fecha,
                                      int noches, int personas) {
        return String.format(
                "{\"cliente\":\"%s\",\"destino\":\"%s\",\"fechaInicio\":\"%s\","
                        + "\"noches\":%d,\"personas\":%d}",
                nombreCliente, destino, fecha, noches, personas);
    }
}
