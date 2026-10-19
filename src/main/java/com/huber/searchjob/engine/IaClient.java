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

/**
 * Cliente de chat/completions (API compatível com OpenAI): OpenRouter com fallback entre modelos free e, se ligada no painel,
 * Z.ai (glm-4.5-flash) como último fallback. URLs, modelos e chaves ficam na tabela config.
 */
@Component
public class IaClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final KvRepo kv;

    public IaClient(KvRepo kv) {
        this.kv = kv;
    }

    /** Uma chamada por vez em cada provedor (a Z.ai free devolve 429 com chamadas simultâneas) e o horário da última. */
    private final Map<String, Object> travas = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> ultimaChamada = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Provedor na ordem do fallback; prefixo é o das chaves na config (prefixo_url, prefixo_chave, prefixo_modelos,
     * prefixo_intervalo_ms, prefixo_timeout_s, prefixo_tentativas). prazoS é o prazo total de cada chamada e tentativas
     * quantas vezes cada modelo é chamado antes de passar adiante.
     */
    private record Provedor(String nome, String prefixo, int prazoS, int tentativas) {
    }

    static final Provedor OPENROUTER = new Provedor("OpenRouter", "openrouter", 120, 10);
    static final Provedor ZAI = new Provedor("Z.ai", "zai", 300, 1);

    /** Todos os provedores falharam (ou nenhum está configurado): quem chama pula a vaga em vez de travar. */
    public static final class IaIndisponivel extends IllegalStateException {
        public IaIndisponivel(String msg, Throwable causa) {
            super(msg, causa);
        }
    }

    private List<Provedor> provedores() {
        List<Provedor> lista = new java.util.ArrayList<>(List.of(OPENROUTER));
        if (kv.booleano(KvRepo.CONFIG, "fallback_zai", false)) lista.add(ZAI);
        return lista.stream().filter(p -> !chave(p).isBlank()).toList();
    }

    private String chave(Provedor p) {
        return kv.get(KvRepo.CONFIG, p.prefixo() + "_chave", "");
    }

    public boolean configurado() {
        return !provedores().isEmpty();
    }

    private final ThreadLocal<String> ultimoModelo = new ThreadLocal<>();

    /** Modelo que respondeu a última chamada desta thread (vazio se nenhuma IA foi usada desde {@link #esquecerModelo()}). */
    public String ultimoModelo() {
        String m = ultimoModelo.get();
        return m == null ? "" : m;
    }

    public void esquecerModelo() {
        ultimoModelo.remove();
    }

    /** Envia system + user e devolve o texto da resposta. */
    /** Envia system + user e devolve o texto: tenta cada provedor e, dentro dele, cada modelo (separados por vírgula) até um responder. */
    public String conversar(String system, String user) {
        TesteFullLog.ativar(kv.booleano(KvRepo.CONFIG, "relatorio_desktop", false));
        IllegalStateException ultima = null;
        for (Provedor p : provedores()) {
            for (String m : kv.get(KvRepo.CONFIG, p.prefixo() + "_modelos", "").split(",")) {
                if (m.isBlank()) continue;
                try {
                    String r = chamarModelo(p, m.trim(), system, user);
                    ultimoModelo.set(m.trim());
                    TesteFullLog.texto(m.trim(), user, r); // TEMP-TESTE-FULL
                    return r;
                } catch (IllegalStateException e) {
                    TesteFullLog.falha(m.trim(), String.valueOf(e.getMessage())); // TEMP-TESTE-FULL
                    ultima = e;
                }
            }
        }
        throw ultima == null ? new IaIndisponivel("Nenhuma IA configurada (chave do OpenRouter ou fallback Z.ai)", null)
                : new IaIndisponivel(ultima.getMessage(), ultima);
    }

    /**
     * Respeita o intervalo mínimo entre chamadas do provedor (prefixo_intervalo_ms) e, em qualquer falha, tenta de novo
     * até prefixo_tentativas vezes; no 429 espera antes (Retry-After se vier, senão 5s, 10s, 15s...).
     */
    private String chamarModelo(Provedor p, String modelo, String system, String user) {
        synchronized (travas.computeIfAbsent(p.prefixo(), k -> new Object())) {
            long intervalo = kv.inteiro(KvRepo.CONFIG, p.prefixo() + "_intervalo_ms", 3000);
            int tentativas = Math.max(1, kv.inteiro(KvRepo.CONFIG, p.prefixo() + "_tentativas", p.tentativas()));
            for (int tentativa = 1; ; tentativa++) {
                dormir(ultimaChamada.getOrDefault(p.prefixo(), 0L) + intervalo - System.currentTimeMillis());
                try {
                    return enviar(p, modelo, system, user);
                } catch (IllegalStateException e) {
                    if (tentativa >= tentativas || Thread.currentThread().isInterrupted()) throw e;
                    TesteFullLog.falha(modelo, "tentativa " + tentativa + "/" + tentativas + ": " + e.getMessage()); // TEMP-TESTE-FULL
                    if (e instanceof LimiteExcedido l) dormir(l.esperaMs > 0 ? l.esperaMs : 5000L * tentativa);
                } finally {
                    ultimaChamada.put(p.prefixo(), System.currentTimeMillis());
                }
            }
        }
    }

    private static void dormir(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chamada à IA interrompida", e);
        }
    }

    /** HTTP 429; esperaMs vem do Retry-After (0 se ausente). */
    private static final class LimiteExcedido extends IllegalStateException {
        final long esperaMs;

        LimiteExcedido(String msg, long esperaMs) {
            super(msg);
            this.esperaMs = esperaMs;
        }
    }

    private String enviar(Provedor p, String modelo, String system, String user) {
        try {
            Map<String, Object> pedido = new java.util.LinkedHashMap<>(Map.of(
                    "model", modelo,
                    "temperature", 0.1,
                    "messages", List.of(Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user))));
            // o raciocínio do GLM dobra o tempo de resposta e não melhora textos curtos de formulário
            if (p.prefixo().equals("zai")) pedido.put("thinking", Map.of("type", "disabled"));
            String corpo = JSON.writeValueAsString(pedido);
            long prazo = kv.inteiro(KvRepo.CONFIG, p.prefixo() + "_timeout_s", p.prazoS());
            HttpRequest req = HttpRequest.newBuilder(URI.create(kv.get(KvRepo.CONFIG, p.prefixo() + "_url", "")))
                    .timeout(Duration.ofSeconds(prazo))
                    .header("Authorization", "Bearer " + chave(p))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corpo)).build();
            // o timeout do HttpRequest só cobre a chegada dos cabeçalhos; o OpenRouter manda os cabeçalhos na hora e segura
            // o corpo com espaços de keep-alive enquanto o modelo pensa, então o prazo total é imposto aqui
            var futuro = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> resp;
            try {
                resp = futuro.get(prazo, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                futuro.cancel(true);
                throw new IllegalStateException(p.nome() + " " + modelo + " não respondeu em " + prazo + "s");
            } catch (InterruptedException e) {
                futuro.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("chamada à IA interrompida", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("Falha ao chamar " + p.nome() + " (" + modelo + "): " + e.getCause(), e.getCause());
            }
            if (resp.statusCode() == 429)
                throw new LimiteExcedido(p.nome() + " " + modelo + " HTTP 429: limite de requisições",
                        resp.headers().firstValue("Retry-After").map(IaClient::segundosEmMs).orElse(0L));
            if (resp.statusCode() / 100 != 2)
                throw new IllegalStateException(p.nome() + " " + modelo + " HTTP " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(300, resp.body().length())));
            String txt = JSON.readTree(resp.body()).path("choices").path(0).path("message").path("content").asText();
            if (txt.isBlank()) throw new IllegalStateException(p.nome() + " " + modelo + " devolveu resposta vazia");
            return txt;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao chamar " + p.nome() + " (" + modelo + "): " + e.getMessage(), e);
        }
    }

    private static long segundosEmMs(String v) {
        try {
            return Math.min(60_000L, Long.parseLong(v.trim()) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Pede JSON e extrai o primeiro objeto {...} da resposta. */
    public JsonNode conversarJson(String system, String user) {
        String txt = conversar(system, user);
        int a = txt.indexOf('{');
        int b = txt.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("Resposta da IA sem JSON: " + txt);
        try {
            return JSON.readTree(txt.substring(a, b + 1));
        } catch (Exception e) {
            throw new IllegalStateException("JSON inválido da IA: " + txt, e);
        }
    }
}
