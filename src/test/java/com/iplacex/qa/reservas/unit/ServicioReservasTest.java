package com.iplacex.qa.reservas.unit;

import com.iplacex.qa.reservas.modelo.EstadoReserva;
import com.iplacex.qa.reservas.modelo.Reserva;
import com.iplacex.qa.reservas.repositorio.RepositorioReservas;
import com.iplacex.qa.reservas.servicio.CalculadoraTarifas;
import com.iplacex.qa.reservas.servicio.ServicioReservas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NIVEL 1 - PRUEBAS UNITARIAS del servicio de reservas con dobles de prueba.
 *
 * <p>El repositorio se sustituye por un <b>mock</b> de Mockito. Esto permite
 * verificar la lógica de orquestación —validaciones, detección de duplicados y
 * llamadas de persistencia— sin base de datos, sin ambiente y en milisegundos.
 * Es la aplicación directa del aislamiento de dependencias revisado en el
 * Módulo de Estudio 3.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Servicio de reservas - orquestación de casos de uso")
class ServicioReservasTest {

    @Mock
    private RepositorioReservas repositorioMock;

    private ServicioReservas servicio;

    private ServicioReservas servicio() {
        if (servicio == null) {
            servicio = new ServicioReservas(repositorioMock, new CalculadoraTarifas());
        }
        return servicio;
    }

    @Test
    @DisplayName("Crea una reserva válida y la persiste con la tarifa calculada")
    void creaReservaValida() {
        when(repositorioMock.existeReservaDuplicada(anyString(), any())).thenReturn(false);
        when(repositorioMock.guardar(any(Reserva.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        Reserva reserva = servicio().crearReserva(
                "Camila Rojas", "Torres del Paine", LocalDate.of(2026, 6, 10), 3, 2);

        assertThat(reserva.getCliente()).isEqualTo("Camila Rojas");
        assertThat(reserva.getDestino()).isEqualTo("Torres del Paine");
        assertThat(reserva.getTarifaTotal()).isEqualTo(270_000.0);
        assertThat(reserva.getEstado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(reserva.getId()).startsWith("RES-");

        // Verifica la interacción: debe persistirse exactamente una vez
        verify(repositorioMock).guardar(any(Reserva.class));
    }

    @Test
    @DisplayName("Captura el objeto persistido y valida sus atributos")
    void persisteLaReservaConLosDatosCorrectos() {
        when(repositorioMock.existeReservaDuplicada(anyString(), any())).thenReturn(false);
        when(repositorioMock.guardar(any(Reserva.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        servicio().crearReserva("Luis Soto", "Valle Nevado", LocalDate.of(2026, 1, 20), 7, 2);

        ArgumentCaptor<Reserva> captor = ArgumentCaptor.forClass(Reserva.class);
        verify(repositorioMock).guardar(captor.capture());

        Reserva capturada = captor.getValue();
        assertThat(capturada.getNoches()).isEqualTo(7);
        assertThat(capturada.getCantidadPersonas()).isEqualTo(2);
        // Enero + 7 noches: 630.000 + 25% - 10% = 708.750
        assertThat(capturada.getTarifaTotal()).isEqualTo(708_750.0);
    }

    @Test
    @DisplayName("Rechaza una reserva duplicada para el mismo cliente y fecha")
    void rechazaReservaDuplicada() {
        when(repositorioMock.existeReservaDuplicada(anyString(), any())).thenReturn(true);

        assertThatThrownBy(() -> servicio().crearReserva(
                "Camila Rojas", "Pucón", LocalDate.of(2026, 6, 10), 2, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya posee una reserva vigente");

        // Regla crítica: ante un duplicado no debe escribirse nada en el repositorio
        verify(repositorioMock, never()).guardar(any(Reserva.class));
    }

    @Test
    @DisplayName("Rechaza una reserva sin nombre de cliente")
    void rechazaClienteVacio() {
        assertThatThrownBy(() -> servicio().crearReserva(
                "   ", "Pucón", LocalDate.of(2026, 6, 10), 2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cliente es obligatorio");

        verify(repositorioMock, never()).guardar(any(Reserva.class));
    }

    @Test
    @DisplayName("Rechaza una reserva sin destino")
    void rechazaDestinoVacio() {
        assertThatThrownBy(() -> servicio().crearReserva(
                "Luis Soto", "", LocalDate.of(2026, 6, 10), 2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destino es obligatorio");
    }

    @Test
    @DisplayName("Cancela una reserva existente y persiste el nuevo estado")
    void cancelaReservaExistente() {
        Reserva reserva = new Reserva("RES-001", "Ana Díaz", "Chiloé",
                LocalDate.of(2026, 6, 10), 2, 1, 90_000.0);
        when(repositorioMock.buscarPorId("RES-001")).thenReturn(Optional.of(reserva));
        when(repositorioMock.guardar(any(Reserva.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        Reserva cancelada = servicio().cancelarReserva("RES-001");

        assertThat(cancelada.getEstado()).isEqualTo(EstadoReserva.CANCELADA);
        assertThat(cancelada.estaVigente()).isFalse();
        verify(repositorioMock).guardar(reserva);
    }

    @Test
    @DisplayName("Informa error al cancelar una reserva inexistente")
    void fallaAlCancelarReservaInexistente() {
        when(repositorioMock.buscarPorId("RES-INEXISTENTE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio().cancelarReserva("RES-INEXISTENTE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No existe la reserva");
    }

    @Test
    @DisplayName("Suma únicamente los ingresos de las reservas vigentes")
    void calculaIngresosSoloDeReservasVigentes() {
        Reserva vigenteUno = new Reserva("RES-001", "Ana", "Chiloé",
                LocalDate.of(2026, 6, 10), 2, 1, 90_000.0);
        Reserva vigenteDos = new Reserva("RES-002", "Beto", "Arica",
                LocalDate.of(2026, 6, 11), 1, 2, 90_000.0);
        Reserva cancelada = new Reserva("RES-003", "Carla", "Iquique",
                LocalDate.of(2026, 6, 12), 3, 2, 270_000.0);
        cancelada.cancelar();

        when(repositorioMock.listarTodas())
                .thenReturn(List.of(vigenteUno, vigenteDos, cancelada));

        double ingresos = servicio().calcularIngresosVigentes();

        // Las 270.000 de la reserva cancelada quedan excluidas del total
        assertThat(ingresos).isEqualTo(180_000.0);
    }
}
