package com.iplacex.qa.reservas.repositorio;

import com.iplacex.qa.reservas.modelo.Reserva;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de persistencia de reservas.
 *
 * <p>Definir la persistencia como interfaz permite sustituirla por un
 * <i>mock</i> en las pruebas unitarias (Mockito) y por una implementación real
 * en producción. Es la separación que hace posible probar el servicio sin
 * levantar infraestructura.</p>
 */
public interface RepositorioReservas {

    /** Persiste una reserva nueva o actualiza una existente. */
    Reserva guardar(Reserva reserva);

    /** Recupera una reserva por su identificador. */
    Optional<Reserva> buscarPorId(String id);

    /** Devuelve todas las reservas registradas. */
    List<Reserva> listarTodas();

    /** Indica si ya existe una reserva para el cliente en la fecha señalada. */
    boolean existeReservaDuplicada(String cliente, java.time.LocalDate fechaInicio);

    /** Elimina todos los registros. Utilizado por los <i>fixtures</i> de prueba. */
    void limpiar();
}
