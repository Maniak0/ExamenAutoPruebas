package com.iplacex.qa.reservas.api;

import com.iplacex.qa.reservas.modelo.Reserva;
import com.iplacex.qa.reservas.repositorio.RepositorioEnMemoria;
import com.iplacex.qa.reservas.repositorio.RepositorioReservas;
import com.iplacex.qa.reservas.servicio.CalculadoraTarifas;
import com.iplacex.qa.reservas.servicio.ServicioReservas;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Servidor HTTP de la aplicación, construido sobre el servidor embebido del JDK.
 *
 * <p>Expone tanto la API REST como un portal web mínimo, de modo que el mismo
 * artefacto pueda ser validado por los cuatro niveles de prueba del proyecto:
 * unitario, integración, aceptación (HTTP) y E2E (Selenium sobre el portal).</p>
 *
 * <p>El endpoint {@code /health} es el que consultan los <i>health checks</i> de
 * las estrategias Blue-Green y Canary para decidir entre promover una versión o
 * ejecutar un rollback.</p>
 */
public class ServidorReservas {

    private final int puerto;
    private final String version;
    private final String color;
    private final boolean simularFallo;
    private final int segundosHastaFallo;
    private final long instanteArranque;
    private final ServicioReservas servicio;

    private HttpServer servidor;

    public ServidorReservas(int puerto, String version, String color,
                            boolean simularFallo, int segundosHastaFallo) {
        this.puerto = puerto;
        this.version = version;
        this.color = color;
        this.simularFallo = simularFallo;
        this.segundosHastaFallo = segundosHastaFallo;
        this.instanteArranque = System.currentTimeMillis();
        RepositorioReservas repositorio = new RepositorioEnMemoria();
        this.servicio = new ServicioReservas(repositorio, new CalculadoraTarifas());
    }

    public ServidorReservas(int puerto, String version, String color, boolean simularFallo) {
        this(puerto, version, color, simularFallo, 0);
    }

    /** Constructor simplificado usado por las pruebas de integración. */
    public ServidorReservas(int puerto) {
        this(puerto, "1.0.0", "azul", false);
    }

    /** Inicia el servidor y registra todas las rutas. */
    public void iniciar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(puerto), 0);
        servidor.setExecutor(Executors.newFixedThreadPool(8));

        servidor.createContext("/health", this::manejarHealth);
        servidor.createContext("/version", this::manejarVersion);
        servidor.createContext("/api/reservas", this::manejarReservas);
        servidor.createContext("/", this::manejarPortal);

        servidor.start();
        log("Servidor iniciado en puerto " + puerto
                + " | version=" + version + " | color=" + color
                + (simularFallo ? " | MODO FALLO ACTIVADO" : ""));
    }

    /** Detiene el servidor liberando el puerto. */
    public void detener() {
        if (servidor != null) {
            servidor.stop(0);
            log("Servidor detenido");
        }
    }

    public int getPuerto() {
        return puerto;
    }

    public ServicioReservas getServicio() {
        return servicio;
    }

    // ==================== Manejadores de rutas ====================

    /**
     * Health check consumido por el pipeline de despliegue.
     *
     * <p>Responde 200 cuando la instancia está sana y 503 cuando se activó la
     * simulación de defecto, permitiendo demostrar el rollback automático con
     * una falla real y no con un resultado inventado.</p>
     */
    private void manejarHealth(HttpExchange intercambio) throws IOException {
        if (simularFallo || defectoLatenteActivado()) {
            responder(intercambio, 503, String.format(
                    "{\"estado\":\"DOWN\",\"version\":\"%s\",\"color\":\"%s\","
                            + "\"motivo\":\"Fallo critico detectado en el modulo de tarifas\"}",
                    version, color));
            return;
        }
        responder(intercambio, 200, String.format(
                "{\"estado\":\"UP\",\"version\":\"%s\",\"color\":\"%s\",\"reservas\":%d}",
                version, color, servicio.listar().size()));
    }

    /**
     * Simula un <b>defecto latente</b>: la instancia arranca sana, supera el
     * health check previo al despliegue y comienza a fallar transcurridos unos
     * segundos.
     *
     * <p>Reproduce el escenario más peligroso de la entrega continua, aquel en
     * que "el pipeline pasa correctamente" pero la versión degrada una vez que
     * recibe tráfico real. Es precisamente el caso que justifica mantener una
     * ventana de monitoreo posterior al switch y un rollback automático.</p>
     */
    private boolean defectoLatenteActivado() {
        return segundosHastaFallo > 0
                && (System.currentTimeMillis() - instanteArranque) > (segundosHastaFallo * 1000L);
    }

    private void manejarVersion(HttpExchange intercambio) throws IOException {
        responder(intercambio, 200, String.format(
                "{\"version\":\"%s\",\"color\":\"%s\"}", version, color));
    }

    /** CRUD de reservas: GET lista, POST crea, DELETE cancela. */
    private void manejarReservas(HttpExchange intercambio) throws IOException {
        String metodo = intercambio.getRequestMethod();
        String ruta = intercambio.getRequestURI().getPath();

        try {
            if ("GET".equals(metodo) && "/api/reservas".equals(ruta)) {
                List<Reserva> reservas = servicio.listar();
                String json = reservas.stream()
                        .map(Reserva::aJson)
                        .collect(Collectors.joining(",", "[", "]"));
                responder(intercambio, 200, json);

            } else if ("POST".equals(metodo)) {
                Map<String, String> datos = parsearJsonPlano(leerCuerpo(intercambio));
                Reserva reserva = servicio.crearReserva(
                        datos.get("cliente"),
                        datos.get("destino"),
                        LocalDate.parse(datos.getOrDefault("fechaInicio", LocalDate.now().toString())),
                        Integer.parseInt(datos.getOrDefault("noches", "1")),
                        Integer.parseInt(datos.getOrDefault("personas", "1")));
                responder(intercambio, 201, reserva.aJson());

            } else if ("GET".equals(metodo)) {
                String id = ruta.substring("/api/reservas/".length());
                responder(intercambio, 200, servicio.obtener(id).aJson());

            } else if ("DELETE".equals(metodo)) {
                String id = ruta.substring("/api/reservas/".length());
                responder(intercambio, 200, servicio.cancelarReserva(id).aJson());

            } else {
                responder(intercambio, 405, "{\"error\":\"Metodo no permitido\"}");
            }

        } catch (IllegalArgumentException e) {
            responder(intercambio, 400, "{\"error\":\"" + escapar(e.getMessage()) + "\"}");
        } catch (IllegalStateException e) {
            responder(intercambio, 409, "{\"error\":\"" + escapar(e.getMessage()) + "\"}");
        } catch (RuntimeException e) {
            responder(intercambio, 500, "{\"error\":\"Error interno\"}");
        }
    }

    /**
     * Portal web del sistema y superficie de prueba de la suite E2E.
     *
     * <p>El entorno que atiende la petición (azul, verde o canary) se muestra
     * con un distintivo de color. No es un adorno: durante un despliegue
     * Blue-Green o Canary permite comprobar visualmente, desde el navegador, a
     * qué instancia está llegando el tráfico en cada momento.</p>
     */
    private void manejarPortal(HttpExchange intercambio) throws IOException {
        String colorEntorno = switch (color) {
            case "verde"  -> "#1d8a4e";
            case "canary" -> "#b8860b";
            case "staging" -> "#5b5bd6";
            default       -> "#1f5fa9";
        };

        String html = """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Portal de Reservas Turisticas</title>
                  <style>
                    * { box-sizing: border-box; }
                    body {
                      margin: 0; padding: 32px 24px;
                      font-family: -apple-system, "Segoe UI", Roboto, Arial, sans-serif;
                      background: #f4f6f8; color: #1a1a1a;
                    }
                    .contenedor { max-width: 760px; margin: 0 auto; }
                    .cabecera {
                      display: flex; align-items: center; justify-content: space-between;
                      gap: 16px; flex-wrap: wrap; margin-bottom: 24px;
                    }
                    h1#titulo { font-size: 26px; margin: 0; font-weight: 650; letter-spacing: -0.2px; }
                    #version-app {
                      margin: 0; padding: 7px 14px; border-radius: 999px;
                      background: %s; color: #fff;
                      font-size: 13px; font-weight: 600; white-space: nowrap;
                    }
                    .tarjeta {
                      background: #fff; border: 1px solid #e2e6ea; border-radius: 10px;
                      padding: 22px; box-shadow: 0 1px 3px rgba(0,0,0,.05);
                    }
                    .tarjeta h2 { font-size: 15px; margin: 0 0 18px; color: #4a5560; font-weight: 600; }
                    .campos { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
                    .campo { display: flex; flex-direction: column; gap: 5px; }
                    .campo label { font-size: 12px; color: #5c6670; font-weight: 600; }
                    input {
                      padding: 9px 11px; border: 1px solid #ccd3da; border-radius: 6px;
                      font-size: 14px; font-family: inherit; background: #fff;
                    }
                    input:focus { outline: 2px solid %s; outline-offset: -1px; border-color: transparent; }
                    button#btn-reservar {
                      margin-top: 18px; padding: 10px 22px; border: 0; border-radius: 6px;
                      background: %s; color: #fff; font-size: 14px; font-weight: 600;
                      font-family: inherit; cursor: pointer;
                    }
                    #mensaje:not(:empty) {
                      margin-top: 18px; padding: 12px 14px; border-radius: 6px;
                      font-size: 14px; font-weight: 500;
                      background: #e8f5ee; color: #14663a; border: 1px solid #b7e0c8;
                    }
                    #mensaje.error { background: #fdecea; color: #8a1c13; border-color: #f5c2bd; }
                    .pie { margin-top: 20px; font-size: 12px; color: #78838d; }
                  </style>
                </head>
                <body>
                  <div class="contenedor">
                    <div class="cabecera">
                      <h1 id="titulo">Sistema de Reservas Turisticas</h1>
                      <p id="version-app">Version: %s | Entorno: %s</p>
                    </div>

                    <div class="tarjeta">
                      <h2>Registrar una nueva reserva</h2>
                      <form id="form-reserva">
                        <div class="campos">
                          <div class="campo">
                            <label for="cliente">Nombre del cliente</label>
                            <input type="text" id="cliente" name="cliente" placeholder="Ej: Ana Diaz">
                          </div>
                          <div class="campo">
                            <label for="destino">Destino</label>
                            <input type="text" id="destino" name="destino" placeholder="Ej: Torres del Paine">
                          </div>
                          <div class="campo">
                            <label for="noches">Noches</label>
                            <input type="number" id="noches" name="noches" value="3" min="1">
                          </div>
                          <div class="campo">
                            <label for="personas">Personas</label>
                            <input type="number" id="personas" name="personas" value="2" min="1" max="8">
                          </div>
                        </div>
                        <button type="button" id="btn-reservar">Reservar</button>
                      </form>

                      <div id="mensaje"></div>
                      <table id="tabla-reservas"><tbody id="cuerpo-tabla"></tbody></table>
                    </div>

                    <p class="pie">
                      Tarifa base $45.000 por noche y persona &middot; recargo 25%% en temporada alta
                      &middot; descuento 10%% desde 7 noches &middot; maximo 8 personas
                    </p>
                  </div>

                  <script>
                    document.getElementById('btn-reservar').addEventListener('click', async () => {
                      const cuerpo = {
                        cliente:  document.getElementById('cliente').value,
                        destino:  document.getElementById('destino').value,
                        noches:   document.getElementById('noches').value,
                        personas: document.getElementById('personas').value,
                        fechaInicio: new Date().toISOString().slice(0,10)
                      };
                      const resp = await fetch('/api/reservas', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(cuerpo)
                      });
                      const datos = await resp.json();
                      const caja = document.getElementById('mensaje');
                      caja.classList.toggle('error', !resp.ok);
                      caja.textContent =
                        resp.ok ? 'Reserva confirmada: ' + datos.id : 'Error: ' + datos.error;
                    });
                  </script>
                </body>
                </html>
                """.formatted(colorEntorno, colorEntorno, colorEntorno, version, color);

        byte[] salida = html.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        intercambio.sendResponseHeaders(200, salida.length);
        try (var os = intercambio.getResponseBody()) {
            os.write(salida);
        }
    }

    // ==================== Utilidades ====================

    private void responder(HttpExchange intercambio, int codigo, String cuerpo) throws IOException {
        byte[] salida = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        intercambio.getResponseHeaders().add("X-App-Version", version);
        intercambio.getResponseHeaders().add("X-App-Color", color);
        intercambio.sendResponseHeaders(codigo, salida.length);
        try (var os = intercambio.getResponseBody()) {
            os.write(salida);
        }
    }

    private String leerCuerpo(HttpExchange intercambio) throws IOException {
        try (InputStream is = intercambio.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Parser JSON plano suficiente para objetos de un nivel, sin dependencias. */
    private Map<String, String> parsearJsonPlano(String json) {
        Map<String, String> mapa = new HashMap<>();
        String limpio = json.trim().replaceAll("^\\{|}$", "");
        if (limpio.isBlank()) {
            return mapa;
        }
        for (String par : limpio.split(",(?=\\s*\")")) {
            String[] partes = par.split(":", 2);
            if (partes.length == 2) {
                mapa.put(limpiar(partes[0]), limpiar(partes[1]));
            }
        }
        return mapa;
    }

    private String limpiar(String valor) {
        return valor.trim().replaceAll("^\"|\"$", "").trim();
    }

    private String escapar(String texto) {
        return texto == null ? "" : texto.replace("\"", "'");
    }

    private void log(String mensaje) {
        System.out.println("[" + java.time.LocalDateTime.now() + "] [SRT] " + mensaje);
    }
}
