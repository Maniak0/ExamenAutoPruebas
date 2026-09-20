package com.iplacex.qa.reservas.servicio;

import java.time.LocalDate;
import java.time.Month;

/**
 * Cálculo de tarifas de una reserva turística.
 *
 * <p>Clase deliberadamente pura y sin dependencias: cada método depende
 * únicamente de sus parámetros, por lo que sus pruebas unitarias son
 * <b>idempotentes</b> y ejecutables sin ambiente, red ni base de datos,
 * cumpliendo el principio de aislamiento del Módulo de Estudio 3.</p>
 */
public class CalculadoraTarifas {

    /** Valor base por noche y por persona, expresado en pesos chilenos. */
    public static final double TARIFA_BASE_NOCHE = 45_000.0;

    /** Recargo aplicado en temporada alta (diciembre, enero y febrero). */
    public static final double RECARGO_TEMPORADA_ALTA = 0.25;

    /** Descuento por estadías prolongadas (7 noches o más). */
    public static final double DESCUENTO_ESTADIA_LARGA = 0.10;

    /** Máximo de personas admitidas en una única reserva. */
    public static final int MAXIMO_PERSONAS = 8;

    /**
     * Calcula el valor total de una reserva.
     *
     * @param noches           cantidad de noches, debe ser mayor que cero
     * @param personas         cantidad de huéspedes, entre 1 y {@value #MAXIMO_PERSONAS}
     * @param fechaInicio      fecha de inicio de la estadía
     * @return el total a pagar, con recargos y descuentos aplicados
     * @throws IllegalArgumentException si los parámetros violan las reglas de negocio
     */
    public double calcularTotal(int noches, int personas, LocalDate fechaInicio) {
        validar(noches, personas, fechaInicio);

        double total = TARIFA_BASE_NOCHE * noches * personas;

        if (esTemporadaAlta(fechaInicio)) {
            total += total * RECARGO_TEMPORADA_ALTA;
        }
        if (aplicaDescuentoEstadiaLarga(noches)) {
            total -= total * DESCUENTO_ESTADIA_LARGA;
        }
        return redondear(total);
    }

    /** Temporada alta en Chile: diciembre, enero y febrero. */
    public boolean esTemporadaAlta(LocalDate fecha) {
        Month mes = fecha.getMonth();
        return mes == Month.DECEMBER || mes == Month.JANUARY || mes == Month.FEBRUARY;
    }

    /** El descuento por estadía larga aplica desde la séptima noche. */
    public boolean aplicaDescuentoEstadiaLarga(int noches) {
        return noches >= 7;
    }

    private void validar(int noches, int personas, LocalDate fechaInicio) {
        if (fechaInicio == null) {
            throw new IllegalArgumentException("La fecha de inicio es obligatoria");
        }
        if (noches <= 0) {
            throw new IllegalArgumentException("La cantidad de noches debe ser mayor que cero");
        }
        if (personas <= 0) {
            throw new IllegalArgumentException("Debe reservarse para al menos una persona");
        }
        if (personas > MAXIMO_PERSONAS) {
            throw new IllegalArgumentException(
                    "No se admiten más de " + MAXIMO_PERSONAS + " personas por reserva");
        }
    }

    private double redondear(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }
}
