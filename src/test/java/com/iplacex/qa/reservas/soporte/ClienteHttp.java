package com.iplacex.qa.reservas.soporte;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente HTTP reutilizable para las pruebas de integración y aceptación.
 *
 * <p>Concentra la mecánica del protocolo en un único lugar. Es la aplicación
 * del principio de <b>separación de responsabilidades</b> del Módulo de Estudio 4:
 * las pruebas describen el "qué" se verifica, mientras esta clase resuelve el
 * "cómo" se comunica con el sistema bajo prueba.</p>
 */
public class ClienteHttp {

    private final HttpClient cliente;
    private final String baseUrl;

    public ClienteHttp(String baseUrl) {
        this.baseUrl = baseUrl;
        this.cliente = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Se ignora cualquier proxy del sistema: las pruebas apuntan
                // siempre al ambiente bajo prueba de forma directa.
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    public HttpResponse<String> get(String ruta) throws IOException, InterruptedException {
        HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + ruta))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String ruta, String cuerpoJson)
            throws IOException, InterruptedException {
        HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + ruta))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpoJson))
                .build();
        return cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> delete(String ruta) throws IOException, InterruptedException {
        HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + ruta))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        return cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
    }

    /** Extrae el valor de un campo de un JSON plano, sin dependencias externas. */
    public static String extraerCampo(String json, String campo) {
        String patron = "\"" + campo + "\":\"";
        int inicio = json.indexOf(patron);
        if (inicio < 0) {
            return null;
        }
        inicio += patron.length();
        int fin = json.indexOf('"', inicio);
        return json.substring(inicio, fin);
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
