import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Balanceador de carga del ambiente de pruebas.
 *
 * <p>Cumple el mismo rol que el <i>router</i> o <i>Ingress controller</i> que
 * describe el Módulo de Estudio 6: es el único punto de entrada estable al que
 * apuntan los usuarios, mientras que por detrás el tráfico puede redirigirse
 * entre distintas instancias sin que el consumidor lo note.</p>
 *
 * <p>Habilita las dos estrategias de despliegue progresivo:</p>
 * <ul>
 *   <li><b>Blue-Green:</b> se cambia el destino {@code primario} de un puerto a
 *       otro. El switch es instantáneo y sin downtime, y el entorno anterior
 *       queda en stand-by para un rollback inmediato.</li>
 *   <li><b>Canary:</b> se define un {@code peso} entre 0 y 100 que determina
 *       qué porcentaje de las peticiones llega a la versión nueva,
 *       equivalente a la anotación {@code nginx.ingress.kubernetes.io/canary-weight}.</li>
 * </ul>
 *
 * <p>La configuración se relee en <b>cada petición</b> desde
 * {@code infra/estado/router.json}, de modo que los scripts del pipeline pueden
 * ajustar el enrutamiento en caliente, sin reiniciar el balanceador ni
 * interrumpir el servicio.</p>
 *
 * <p>Se ejecuta con el lanzador de archivo único del JDK, sin compilación previa:</p>
 * <pre>java infra/Router.java 9080 infra/estado/router.json</pre>
 */
public class Router {

    private static Path archivoConfig;

    public static void main(String[] args) throws IOException {
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 9080;
        archivoConfig = Path.of(args.length > 1 ? args[1] : "infra/estado/router.json");

        HttpServer servidor = HttpServer.create(new InetSocketAddress(puerto), 0);
        servidor.setExecutor(Executors.newFixedThreadPool(8));
        servidor.createContext("/", Router::enrutar);
        servidor.start();

        System.out.println("[ROUTER] Balanceador escuchando en el puerto " + puerto);
        System.out.println("[ROUTER] Configuración dinámica: " + archivoConfig.toAbsolutePath());
    }

    /** Reenvía la petición a la instancia elegida y devuelve su respuesta tal cual. */
    private static void enrutar(HttpExchange intercambio) throws IOException {
        Config config = leerConfig();

        // Decisión de enrutamiento: sorteo ponderado equivalente al reparto de
        // tráfico de un despliegue Canary.
        boolean haciaCanary = config.puertoCanary > 0
                && ThreadLocalRandom.current().nextInt(100) < config.pesoCanary;

        int destino = haciaCanary ? config.puertoCanary : config.puertoPrimario;
        String etiqueta = haciaCanary ? "canary" : "primario";

        byte[] cuerpoEntrada;
        try (InputStream is = intercambio.getRequestBody()) {
            cuerpoEntrada = is.readAllBytes();
        }

        try {
            HttpClient cliente = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .build();

            HttpRequest.Builder constructor = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + destino
                            + intercambio.getRequestURI().toString()))
                    .timeout(Duration.ofSeconds(10));

            String metodo = intercambio.getRequestMethod();
            if ("POST".equals(metodo) || "PUT".equals(metodo)) {
                constructor.method(metodo, HttpRequest.BodyPublishers.ofByteArray(cuerpoEntrada))
                        .header("Content-Type", "application/json");
            } else {
                constructor.method(metodo, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<byte[]> respuesta = cliente.send(
                    constructor.build(), HttpResponse.BodyHandlers.ofByteArray());

            // Cabecera de trazabilidad: permite auditar a qué instancia llegó
            // cada petición y medir el reparto real del tráfico.
            intercambio.getResponseHeaders().add("X-Ruteado-A", etiqueta);
            intercambio.getResponseHeaders().add("X-Puerto-Destino", String.valueOf(destino));
            respuesta.headers().firstValue("X-App-Version").ifPresent(
                    v -> intercambio.getResponseHeaders().add("X-App-Version", v));
            respuesta.headers().firstValue("Content-Type").ifPresent(
                    v -> intercambio.getResponseHeaders().add("Content-Type", v));

            intercambio.sendResponseHeaders(respuesta.statusCode(), respuesta.body().length);
            try (var os = intercambio.getResponseBody()) {
                os.write(respuesta.body());
            }

        } catch (IOException | InterruptedException e) {
            // La instancia destino no responde: el balanceador devuelve 502,
            // señal que el pipeline interpreta como fallo del despliegue.
            byte[] error = ("{\"error\":\"Instancia " + etiqueta + " no disponible en el puerto "
                    + destino + "\"}").getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(502, error.length);
            try (var os = intercambio.getResponseBody()) {
                os.write(error);
            }
        }
    }

    /** Relee la configuración en cada petición para permitir cambios en caliente. */
    private static Config leerConfig() {
        Config config = new Config();
        try {
            String json = Files.readString(archivoConfig);
            config.puertoPrimario = extraerEntero(json, "puertoPrimario", 9081);
            config.puertoCanary = extraerEntero(json, "puertoCanary", 0);
            config.pesoCanary = extraerEntero(json, "pesoCanary", 0);
        } catch (IOException e) {
            config.puertoPrimario = 9081;
        }
        return config;
    }

    private static int extraerEntero(String json, String campo, int porDefecto) {
        String patron = "\"" + campo + "\"";
        int inicio = json.indexOf(patron);
        if (inicio < 0) {
            return porDefecto;
        }
        inicio = json.indexOf(':', inicio) + 1;
        int fin = inicio;
        while (fin < json.length() && (Character.isDigit(json.charAt(fin))
                || Character.isWhitespace(json.charAt(fin)))) {
            fin++;
        }
        try {
            return Integer.parseInt(json.substring(inicio, fin).trim());
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    /** Configuración vigente del enrutamiento. */
    private static class Config {
        int puertoPrimario = 9081;
        int puertoCanary = 0;
        int pesoCanary = 0;
    }
}
