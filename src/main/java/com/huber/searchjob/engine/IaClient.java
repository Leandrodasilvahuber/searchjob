package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.db.IaPromptRepo;
import com.huber.searchjob.db.IaProvedorRepo;
import com.huber.searchjob.db.IaProvedorRepo.Provedor;
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
 * Cliente de chat/completions (API compatível com OpenAI): tenta cada provedor da tabela ia_provedor na ordem e, dentro dele,
 * cada modelo. Provedor inativo, sem chave ou sem modelos fica de fora; com um só configurado, ele é o único da fila.
 */
@Component
public class IaClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final KvRepo kv;
    private final IaProvedorRepo repo;
    private final IaPromptRepo prompts;

    public IaClient(KvRepo kv, IaProvedorRepo repo, IaPromptRepo prompts) {
        this.kv = kv;
        this.repo = repo;
        this.prompts = prompts;
    }

    /** Uma chamada por vez em cada provedor (a Z.ai free devolve 429 com chamadas simultâneas) e o horário da última. */
    private final Map<String, Object> travas = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> ultimaChamada = new java.util.concurrent.ConcurrentHashMap<>();

    /** Todos os provedores falharam (ou nenhum está configurado): quem chama pula a vaga em vez de travar. */
    public static final class IaIndisponivel extends IllegalStateException {
        public IaIndisponivel(String msg, Throwable causa) {
            super(msg, causa);
        }
    }

    private List<Provedor> provedores() {
        return repo.todos().stream().filter(Provedor::utilizavel).toList();
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

    /** Envia system + user e devolve o texto: tenta cada provedor e, dentro dele, cada modelo (separados por vírgula) até um responder. */
    public String conversar(String system, String user) {
        TesteFullLog.ativar(kv.booleano(KvRepo.CONFIG, "relatorio_desktop", false));
        IllegalStateException ultima = null;
        for (Provedor p : provedores()) {
            for (String m : p.modelos()) {
                try {
                    String r = chamarModelo(p, m, system, user, true);
                    ultimoModelo.set(m);
                    TesteFullLog.texto(m, user, r); // TEMP-TESTE-FULL
                    return r;
                } catch (IllegalStateException e) {
                    TesteFullLog.falha(m, String.valueOf(e.getMessage())); // TEMP-TESTE-FULL
                    ultima = e;
                }
            }
        }
        throw ultima == null ? new IaIndisponivel("Nenhuma IA configurada (cadastre a chave de um provedor em Portais e IA)", null)
                : new IaIndisponivel(ultima.getMessage(), ultima);
    }

    /** Modelos de todos os provedores com chave (mesmo os inativos no fallback), para o teste de textos do painel. */
    public List<String> modelos() {
        return comChave().flatMap(p -> p.modelos().stream()).distinct().toList();
    }

    private java.util.stream.Stream<Provedor> comChave() {
        return repo.todos().stream().filter(p -> !p.chave().isBlank() && !p.url().isBlank());
    }

    /**
     * Como {@link #conversar}, mas só com o modelo pedido (sem fallback): o teste de textos compara os modelos.
     * No 429 não insiste: o teste avisa na hora que o provedor está esgotado.
     */
    public String conversarCom(String modelo, String system, String user) {
        Provedor p = comChave().filter(x -> x.modelos().contains(modelo)).findFirst().orElseThrow(() -> new IllegalArgumentException("modelo não configurado: " + modelo));
        return chamarModelo(p, modelo, system, user, false);
    }

    /**
     * Respeita o intervalo mínimo entre chamadas do provedor e, em qualquer falha, tenta de novo até o número de
     * tentativas do provedor; no 429 espera antes (Retry-After se vier, senão 5s, 10s, 15s...). Cota diária esgotada
     * (ou {@code insistirNo429} falso) sobe na hora: esperar segundos não adianta.
     */
    private String chamarModelo(Provedor p, String modelo, String system, String user, boolean insistirNo429) {
        synchronized (travas.computeIfAbsent(p.nome(), k -> new Object())) {
            long intervalo = p.intervaloMs();
            int tentativas = Math.max(1, p.tentativas());
            for (int tentativa = 1; ; tentativa++) {
                dormir(ultimaChamada.getOrDefault(p.nome(), 0L) + intervalo - System.currentTimeMillis());
                try {
                    return enviar(p, modelo, system, user);
                } catch (IllegalStateException e) {
                    if (tentativa >= tentativas || Thread.currentThread().isInterrupted()) throw e;
                    if (e instanceof LimiteExcedido l && (l.diario || !insistirNo429)) throw e;
                    TesteFullLog.falha(modelo, "tentativa " + tentativa + "/" + tentativas + ": " + e.getMessage()); // TEMP-TESTE-FULL
                    if (e instanceof LimiteExcedido l) dormir(l.esperaMs > 0 ? l.esperaMs : 5000L * tentativa);
                } finally {
                    ultimaChamada.put(p.nome(), System.currentTimeMillis());
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

    /** HTTP 429: provedor esgotado. esperaMs vem do Retry-After (0 se ausente); diario = cota do dia acabou. */
    public static final class LimiteExcedido extends IllegalStateException {
        final long esperaMs;
        final boolean diario;

        LimiteExcedido(String msg, long esperaMs, boolean diario) {
            super(msg);
            this.esperaMs = esperaMs;
            this.diario = diario;
        }
    }

    /** Monta o 429: a cota diária do OpenRouter (free-models-per-day) diz no corpo e a volta vem em X-RateLimit-Reset (ms). */
    static LimiteExcedido limite(String provedor, String modelo, HttpResponse<String> resp) {
        String corpo = resp.body() == null ? "" : resp.body().toLowerCase();
        boolean diario = corpo.contains("per-day") || corpo.contains("per day") || corpo.contains("daily");
        String volta = resp.headers().firstValue("X-RateLimit-Reset").map(IaClient::horario).orElse("");
        String msg = provedor + " " + modelo + " esgotado (HTTP 429): "
                + (diario ? "a cota diária de chamadas acabou" : "limite de requisições por minuto")
                + (volta.isEmpty() ? "" : ", libera às " + volta)
                + (diario ? " — use outro modelo ou ponha créditos no provedor" : "");
        return new LimiteExcedido(msg, resp.headers().firstValue("Retry-After").map(IaClient::segundosEmMs).orElse(0L), diario);
    }

    private static String horario(String epochMs) {
        try {
            return java.time.Instant.ofEpochMilli(Long.parseLong(epochMs.trim())).atZone(java.time.ZoneId.systemDefault())
                    .toLocalTime().withSecond(0).withNano(0).toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String enviar(Provedor p, String modelo, String system, String user) {
        try {
            Map<String, Object> pedido = new java.util.LinkedHashMap<>(Map.of(
                    "model", modelo,
                    "temperature", 0.1,
                    "messages", List.of(Map.of("role", "system", "content", comPromptDoModelo(modelo, system)),
                            Map.of("role", "user", "content", user))));
            // o raciocínio do GLM dobra o tempo de resposta e não melhora textos curtos de formulário
            if ((p.nome() + " " + p.url()).toLowerCase().contains("z.ai")) pedido.put("thinking", Map.of("type", "disabled"));
            String corpo = JSON.writeValueAsString(pedido);
            long prazo = Math.max(1, p.timeoutS());
            HttpRequest req = HttpRequest.newBuilder(URI.create(p.url()))
                    .timeout(Duration.ofSeconds(prazo))
                    .header("Authorization", "Bearer " + p.chave())
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
            if (resp.statusCode() == 429) throw limite(p.nome(), modelo, resp);
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

    /** Junta ao system a instrução extra do modelo (aba Prompts dos modelos), quando houver. */
    private String comPromptDoModelo(String modelo, String system) {
        String extra = prompts == null ? null : prompts.de(modelo);
        return extra == null || extra.isBlank() ? system : system + "\n\nINSTRUÇÕES EXTRAS PARA ESTE MODELO (valem sobre as anteriores):\n" + extra;
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
        return extrairJson(conversar(system, user));
    }

    static JsonNode extrairJson(String txt) {
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
