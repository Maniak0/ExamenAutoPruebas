package com.iplacex.qa.reservas.repositorio;

import com.iplacex.qa.reservas.modelo.Reserva;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementación en memoria del repositorio de reservas.
 *
 * <p>Sustituye a una base de datos real durante las pruebas de integración y
 * en el ambiente de staging, garantizando que cada ejecución del pipeline
 * parta desde un estado limpio y reproducible (entornos efímeros, ME_5 §1.2.2).</p>
 *
 * <p>Se sincroniza mediante {@code synchronized} porque el servidor HTTP atiende
 * peticiones concurrentes.</p>
 */
public class RepositorioEnMemoria implements RepositorioReservas {

    private final Map<String, Reserva> almacen = new LinkedHashMap<>();

    @Override
    public synchronized Reserva guardar(Reserva reserva) {
        almacen.put(reserva.getId(), reserva);
        return reserva;
    }

    @Override
    public synchronized Optional<Reserva> buscarPorId(String id) {
        return Optional.ofNullable(almacen.get(id));
    }

    @Override
    public synchronized List<Reserva> listarTodas() {
        return new ArrayList<>(almacen.values());
    }

    @Override
    public synchronized boolean existeReservaDuplicada(String cliente, LocalDate fechaInicio) {
        return almacen.values().stream()
                .filter(Reserva::estaVigente)
                .anyMatch(r -> r.getCliente().equalsIgnoreCase(cliente)
                        && r.getFechaInicio().equals(fechaInicio));
    }

    @Override
    public synchronized void limpiar() {
        almacen.clear();
    }
}
