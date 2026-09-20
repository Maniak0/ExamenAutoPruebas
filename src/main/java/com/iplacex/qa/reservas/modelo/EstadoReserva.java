package com.iplacex.qa.reservas.modelo;

/**
 * Estados posibles del ciclo de vida de una reserva.
 *
 * <p>Modelar el estado como enumeración (y no como texto libre) elimina una
 * clase completa de defectos y permite que las pruebas automatizadas verifiquen
 * transiciones de forma exhaustiva.</p>
 */
public enum EstadoReserva {

    /** Reserva creada y vigente. */
    CONFIRMADA,

    /** Reserva anulada por el cliente o por el operador. */
    CANCELADA,

    /** Reserva cuya fecha de inicio ya transcurrió. */
    FINALIZADA
}
