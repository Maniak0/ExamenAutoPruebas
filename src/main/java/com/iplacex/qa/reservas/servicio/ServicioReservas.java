package com.iplacex.qa.reservas.servicio;

import com.iplacex.qa.reservas.modelo.Reserva;
import com.iplacex.qa.reservas.repositorio.RepositorioReservas;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Caso de uso central del sistema: orquesta la creación, consulta y
 * cancelación de reservas.
 *
 * <p>Recibe sus colaboradores por constructor (<i>inyección de dependencias</i>),
 * lo que permite que las pruebas unitarias sustituyan el repositorio por un
 * doble de prueba y la calculadora por una instancia real, verificando la
 * lógica de orquestación de forma aislada.</p>
 */
public class ServicioReservas {

    private final RepositorioReservas repositorio;
    private final CalculadoraTarifas calculadora;

    public ServicioReservas(RepositorioReservas repositorio, CalculadoraTarifas calculadora) {
        this.repositorio = repositorio;
        this.calculadora = calculadora;
    }

    /**
     * Crea y persiste una reserva aplicando todas las reglas de negocio.
     *
     * @throws IllegalArgumentException si los datos son inválidos
     * @throws IllegalStateException    si el cliente ya tiene una reserva vigente
     *                                  para esa misma fecha
     */
    public Reserva crearReserva(String cliente,
                                String destino,
                                LocalDate fechaInicio,
                                int noches,
                                int personas) {

        if (cliente == null || cliente.isBlank()) {
            throw new IllegalArgumentException("El nombre del cliente es obligatorio");
        }
        if (destino == null || destino.isBlank()) {
            throw new IllegalArgumentException("El destino es obligatorio");
        }
        if (repositorio.existeReservaDuplicada(cliente, fechaInicio)) {
            throw new IllegalStateException(
                    "El cliente " + cliente + " ya posee una reserva vigente para esa fecha");
        }

        double total = calculadora.calcularTotal(noches, personas, fechaInicio);

        Reserva reserva = new Reserva(
                generarId(), cliente, destino, fechaInicio, noches, personas, total);

        return repositorio.guardar(reserva);
    }

    /** Cancela una reserva existente y persiste el nuevo estado. */
    public Reserva cancelarReserva(String id) {
        Reserva reserva = repositorio.buscarPorId(id)
                .orElseThrow(() -> new IllegalArgumentException("No existe la reserva " + id));
        reserva.cancelar();
        return repositorio.guardar(reserva);
    }

    /** Recupera una reserva por identificador. */
    public Reserva obtener(String id) {
        return repositorio.buscarPorId(id)
                .orElseThrow(() -> new IllegalArgumentException("No existe la reserva " + id));
    }

    /** Lista todas las reservas registradas. */
    public List<Reserva> listar() {
        return repositorio.listarTodas();
    }

    /** Suma el valor de todas las reservas vigentes. */
    public double calcularIngresosVigentes() {
        return repositorio.listarTodas().stream()
                .filter(Reserva::estaVigente)
                .mapToDouble(Reserva::getTarifaTotal)
                .sum();
    }

    private String generarId() {
        return "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
