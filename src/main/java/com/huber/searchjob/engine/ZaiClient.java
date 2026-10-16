package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.db.KvRepo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Cliente da z.ai (API compatível com OpenAI chat/completions). URL, modelo e chave ficam na tabela config. */
@Component
public class ZaiClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final KvRepo kv;

    public ZaiClient(KvRepo kv) {
        this.kv = kv;
    }

    public boolean configurado() {
        return !kv.get(KvRepo.CONFIG, "zai_chave", "").isBlank();
    }

    /** Envia system + user e devolve o texto da resposta. */
    public String conversar(String system, String user) {
        try {
            String corpo = JSON.writeValueAsString(Map.of(
                    "model", kv.get(KvRepo.CONFIG, "zai_modelo", "glm-4.6"),
                    "temperature", 0.1,
                    "messages", List.of(Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(kv.get(KvRepo.CONFIG, "zai_url", "")))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + kv.get(KvRepo.CONFIG, "zai_chave", ""))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corpo)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                throw new IllegalStateException("z.ai HTTP " + resp.statusCode() + ": " + resp.body());
            return JSON.readTree(resp.body()).path("choices").path(0).path("message").path("content").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao chamar a z.ai: " + e.getMessage(), e);
        }
    }

    /** Pede JSON e extrai o primeiro objeto {...} da resposta. */
    public JsonNode conversarJson(String system, String user) {
        String txt = conversar(system, user);
        int a = txt.indexOf('{');
        int b = txt.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("Resposta da z.ai sem JSON: " + txt);
        try {
            return JSON.readTree(txt.substring(a, b + 1));
        } catch (Exception e) {
            throw new IllegalStateException("JSON inválido da z.ai: " + txt, e);
        }
    }
}
