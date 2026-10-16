package com.huber.searchjob.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** Cliente HTTP mínimo dos testes e2e: age como o navegador do usuário (mesma origem, sem seguir redirects). */
final class Http {
    record Resp(int status, String corpo) {}

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;

    Http(int porta) {
        this.base = "http://127.0.0.1:" + porta;
    }

    Resp get(String caminho) throws IOException, InterruptedException {
        var r = http.send(HttpRequest.newBuilder(URI.create(base + caminho)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), r.body());
    }

    Resp post(String caminho, Map<String, String> campos) throws IOException, InterruptedException {
        String corpo = campos.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var r = http.send(HttpRequest.newBuilder(URI.create(base + caminho))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "http://localhost:8787")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), r.body());
    }
}
