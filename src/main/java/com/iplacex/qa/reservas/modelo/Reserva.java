package com.iplacex.qa.reservas.modelo;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Entidad de dominio que representa una reserva turística.
 *
 * <p>Se implementa como objeto con estado controlado: los campos son privados y
 * la única transición permitida es {@code CONFIRMADA -> CANCELADA}, lo que hace
 * que las reglas de negocio sean verificables mediante pruebas unitarias.</p>
 */
public class Reserva {

    private final String id;
    private final String cliente;
    private final String destino;
    private final LocalDate fechaInicio;
    private final int noches;
    private final int cantidadPersonas;
    private final double tarifaTotal;
    private EstadoReserva estado;

    public Reserva(String id,
                   String cliente,
                   String destino,
                   LocalDate fechaInicio,
                   int noches,
                   int cantidadPersonas,
                   double tarifaTotal) {
        this.id = Objects.requireNonNull(id, "El id no puede ser nulo");
        this.cliente = Objects.requireNonNull(cliente, "El cliente no puede ser nulo");
        this.destino = Objects.requireNonNull(destino, "El destino no puede ser nulo");
        this.fechaInicio = Objects.requireNonNull(fechaInicio, "La fecha no puede ser nula");
        this.noches = noches;
        this.cantidadPersonas = cantidadPersonas;
        this.tarifaTotal = tarifaTotal;
        this.estado = EstadoReserva.CONFIRMADA;
    }

    /**
     * Cancela la reserva.
     *
     * @throws IllegalStateException si la reserva ya fue cancelada, impidiendo
     *         cancelaciones duplicadas que corromperían los reportes de ventas.
     */
    public void cancelar() {
        if (this.estado == EstadoReserva.CANCELADA) {
            throw new IllegalStateException("La reserva " + id + " ya se encuentra cancelada");
        }
        this.estado = EstadoReserva.CANCELADA;
    }

    public boolean estaVigente() {
        return this.estado == EstadoReserva.CONFIRMADA;
    }

    public String getId() {
        return id;
    }

    public String getCliente() {
        return cliente;
    }

    public String getDestino() {
        return destino;
    }

    public LocalDate getFechaInicio() {
        return fechaInicio;
    }

    public int getNoches() {
        return noches;
    }

    public int getCantidadPersonas() {
        return cantidadPersonas;
    }

    public double getTarifaTotal() {
        return tarifaTotal;
    }

    public EstadoReserva getEstado() {
        return estado;
    }

    /**
     * Serializa la reserva a JSON sin dependencias externas.
     *
     * <p>Mantener el proyecto libre de librerías de runtime reduce la superficie
     * de vulnerabilidades detectadas por el escaneo SAST del pipeline.</p>
     */
    public String aJson() {
        return String.format(java.util.Locale.US,
                "{\"id\":\"%s\",\"cliente\":\"%s\",\"destino\":\"%s\",\"fechaInicio\":\"%s\","
                        + "\"noches\":%d,\"cantidadPersonas\":%d,\"tarifaTotal\":%.2f,\"estado\":\"%s\"}",
                id, cliente, destino, fechaInicio, noches, cantidadPersonas, tarifaTotal, estado);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Reserva)) {
            return false;
        }
        return id.equals(((Reserva) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Reserva{" + id + ", " + cliente + ", " + destino + ", " + estado + '}';
    }
}
