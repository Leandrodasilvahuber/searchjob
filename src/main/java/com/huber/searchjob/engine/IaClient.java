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

/** Cliente do OpenRouter (chat/completions) com fallback entre modelos free. URL, modelos e chave ficam na tabela config. */
@Component
public class IaClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    /** Depois de uma falha o OpenRouter fica pausado por este tempo: o fluxo segue pelas regras sem esperar timeout a cada pergunta. */
    static final Duration PAUSA_APOS_FALHA = Duration.ofMinutes(1);
    private final KvRepo kv;
    private volatile java.time.Instant pausadaAte = java.time.Instant.EPOCH;

    public IaClient(KvRepo kv) {
        this.kv = kv;
    }

    public boolean configurado() {
        return !kv.get(KvRepo.CONFIG, "openrouter_chave", "").isBlank();
    }

    /** Envia system + user e devolve o texto da resposta. */
    public String conversar(String system, String user) {
        if (java.time.Instant.now().isBefore(pausadaAte))
            throw new IllegalStateException("OpenRouter pausado após falha recente (volta às "
                    + java.time.LocalTime.ofInstant(pausadaAte, java.time.ZoneId.systemDefault()).withNano(0) + ")");
        try {
            return chamar(system, user);
        } catch (RuntimeException e) {
            pausadaAte = java.time.Instant.now().plus(PAUSA_APOS_FALHA);
            throw e;
        }
    }

    /** Tenta cada modelo da lista (openrouter_modelos, separados por vírgula) até um responder. */
    private String chamar(String system, String user) {
        TesteFullLog.ativo = kv.booleano(KvRepo.CONFIG, "relatorio_desktop", false);
        String[] modelos = kv.get(KvRepo.CONFIG, "openrouter_modelos", "").split(",");
        IllegalStateException ultima = new IllegalStateException("Nenhum modelo configurado em openrouter_modelos");
        for (String m : modelos) {
            if (m.isBlank()) continue;
            try {
                String r = chamarModelo(m.trim(), system, user);
                TesteFullLog.texto(m.trim(), user, r); // TEMP-TESTE-FULL
                return r;
            } catch (IllegalStateException e) {
                TesteFullLog.falha(m.trim(), String.valueOf(e.getMessage())); // TEMP-TESTE-FULL
                ultima = e;
            }
        }
        throw ultima;
    }

    private String chamarModelo(String modelo, String system, String user) {
        try {
            String corpo = JSON.writeValueAsString(Map.of(
                    "model", modelo,
                    "temperature", 0.1,
                    "messages", List.of(Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(kv.get(KvRepo.CONFIG, "openrouter_url", "")))
                    .timeout(Duration.ofSeconds(200))
                    .header("Authorization", "Bearer " + kv.get(KvRepo.CONFIG, "openrouter_chave", ""))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corpo)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                throw new IllegalStateException("OpenRouter " + modelo + " HTTP " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(300, resp.body().length())));
            String txt = JSON.readTree(resp.body()).path("choices").path(0).path("message").path("content").asText();
            if (txt.isBlank()) throw new IllegalStateException("OpenRouter " + modelo + " devolveu resposta vazia");
            return txt;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao chamar o OpenRouter (" + modelo + "): " + e.getMessage(), e);
        }
    }

    /** Pede JSON e extrai o primeiro objeto {...} da resposta. */
    public JsonNode conversarJson(String system, String user) {
        String txt = conversar(system, user);
        int a = txt.indexOf('{');
        int b = txt.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("Resposta do OpenRouter sem JSON: " + txt);
        try {
            return JSON.readTree(txt.substring(a, b + 1));
        } catch (Exception e) {
            throw new IllegalStateException("JSON inválido do OpenRouter: " + txt, e);
        }
    }
}
