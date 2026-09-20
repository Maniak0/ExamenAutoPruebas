package com.iplacex.qa.reservas.soporte;

import com.iplacex.qa.reservas.api.ServidorReservas;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Gestiona el ambiente contra el cual se ejecutan las pruebas de aceptación.
 *
 * <p>Resuelve un problema práctico del deployment pipeline: las mismas pruebas
 * de aceptación deben poder ejecutarse en dos contextos distintos.</p>
 *
 * <ol>
 *   <li><b>Localmente</b> ({@code mvn verify}): no hay ambiente desplegado, por
 *       lo que la clase levanta una instancia embebida y la detiene al terminar.</li>
 *   <li><b>Desde el Acceptance Gate</b>
 *       ({@code mvn verify -Paceptacion -Dtest.baseUrl=http://staging:8080}):
 *       el ambiente ya fue desplegado por el pipeline, de modo que las pruebas
 *       se limitan a consumirlo sin levantar nada.</li>
 * </ol>
 *
 * <p>Esta única decisión evita duplicar la suite de aceptación y garantiza que
 * el gate valide exactamente los mismos escenarios que el desarrollador ejecuta
 * en su equipo.</p>
 */
public final class AmbientePruebas {

    private static ServidorReservas servidorEmbebido;
    private static String baseUrl;

    private AmbientePruebas() {
    }

    /**
     * Devuelve la URL base del ambiente, levantando uno embebido si es necesario.
     */
    public static synchronized String baseUrl() throws IOException {
        if (baseUrl != null) {
            return baseUrl;
        }

        String urlExterna = System.getProperty("test.baseUrl");

        // La decisión NO se toma por el nombre del host: un ambiente de staging
        // desplegado por el pipeline puede perfectamente vivir en localhost.
        // Se sondea el endpoint /health y, si alguien responde, se reutiliza.
        if (urlExterna != null && !urlExterna.isBlank() && ambienteResponde(urlExterna)) {
            baseUrl = urlExterna;
            System.out.println("[AmbientePruebas] Ambiente ya desplegado, se reutiliza: " + baseUrl);
            return baseUrl;
        }

        int puerto = puertoLibre();
        servidorEmbebido = new ServidorReservas(puerto, "1.0.0", "pruebas", false);
        servidorEmbebido.iniciar();
        baseUrl = "http://localhost:" + puerto;
        System.out.println("[AmbientePruebas] Ambiente embebido levantado en " + baseUrl);

        Runtime.getRuntime().addShutdownHook(new Thread(AmbientePruebas::detener));
        return baseUrl;
    }

    /** Detiene el ambiente embebido si fue esta clase quien lo inició. */
    public static synchronized void detener() {
        if (servidorEmbebido != null) {
            servidorEmbebido.detener();
            servidorEmbebido = null;
            baseUrl = null;
        }
    }

    /** Cliente HTTP ya apuntado al ambiente vigente. */
    public static ClienteHttp cliente() throws IOException {
        return new ClienteHttp(baseUrl());
    }

    /**
     * Comprueba si en la URL indicada ya hay una instancia atendiendo.
     *
     * <p>Un {@code /health} que responde significa que el pipeline desplegó el
     * ambiente y las pruebas deben validarlo tal cual. Cualquier error de
     * conexión significa que no hay nada desplegado y corresponde levantar la
     * instancia embebida.</p>
     */
    private static boolean ambienteResponde(String url) {
        try {
            var cliente = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .proxy(java.net.http.HttpClient.Builder.NO_PROXY)
                    .build();
            var peticion = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url + "/health"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET()
                    .build();
            int codigo = cliente.send(peticion,
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            return codigo > 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Reserva un puerto libre del sistema, evitando colisiones entre ejecuciones. */
    public static int puertoLibre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
