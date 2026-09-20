package com.iplacex.qa.reservas.api;

import java.io.IOException;

/**
 * Punto de entrada del artefacto desplegable.
 *
 * <p>Toda la configuración se recibe por variables de entorno y no está
 * embebida en el código. Es la <b>configuración de entorno</b> descrita en el
 * Módulo de Estudio 2: el mismo {@code .jar} construido una sola vez se
 * despliega en staging, en el entorno azul o en el verde cambiando únicamente
 * sus variables, nunca su contenido.</p>
 *
 * <p>Variables reconocidas:</p>
 * <ul>
 *   <li>{@code SRT_PUERTO}         - puerto de escucha (por defecto 8080)</li>
 *   <li>{@code SRT_VERSION}        - versión reportada en /health y /version</li>
 *   <li>{@code SRT_COLOR}          - identifica el entorno: azul | verde | canary</li>
 *   <li>{@code SRT_SIMULAR_FALLO}  - si es "true", /health responde 503</li>
 *   <li>{@code SRT_FALLO_TRAS_SEGUNDOS} - defecto latente: la instancia arranca
 *       sana y comienza a fallar pasados N segundos, permitiendo demostrar el
 *       rollback automático posterior al cambio de tráfico</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int puerto = Integer.parseInt(variable("SRT_PUERTO", "8080"));
        String version = variable("SRT_VERSION", "1.0.0");
        String color = variable("SRT_COLOR", "azul");
        boolean simularFallo = Boolean.parseBoolean(variable("SRT_SIMULAR_FALLO", "false"));
        int falloTrasSegundos = Integer.parseInt(variable("SRT_FALLO_TRAS_SEGUNDOS", "0"));

        ServidorReservas servidor = new ServidorReservas(
                puerto, version, color, simularFallo, falloTrasSegundos);
        servidor.iniciar();

        // Apagado ordenado: permite que el pipeline detenga instancias sin
        // dejar puertos ocupados entre despliegues sucesivos.
        Runtime.getRuntime().addShutdownHook(new Thread(servidor::detener));
    }

    private static String variable(String nombre, String porDefecto) {
        String valor = System.getenv(nombre);
        return (valor == null || valor.isBlank()) ? porDefecto : valor;
    }
}
