package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.db.Cv;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Gupy. Busca pela API pública (sem navegador, sem tokens). A candidatura roda no Firefox
 * seguindo o fluxo da skill: Continuar → Dados adicionais → perguntas → Apresente-se → Finalizar.
 */
public class GupyDriver implements PortalDriver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] BOTOES = {"Continuar", "Salvar e continuar", "Responder agora", "Próximo", "Finalizar candidatura"};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final NavegadorFirefox navegador;
    private final SituacaoService situacoes;

    public GupyDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        this.navegador = navegador;
        this.situacoes = situacoes;
    }

    @Override
    public String nome() {
        return "Gupy";
    }

    @Override
    public List<Vaga> buscar(String termo, int max) throws Exception {
        List<Vaga> out = new ArrayList<>();
        for (int offset = 0; offset < max; offset += 100) {
            String url = "https://portal.gupy.io/api/job-search/jobs?jobName="
                    + URLEncoder.encode(termo, StandardCharsets.UTF_8) + "&limit=100&offset=" + offset;
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0").timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("Gupy busca HTTP " + r.statusCode());
            JsonNode data = JSON.readTree(r.body()).path("data");
            if (!data.isArray() || data.isEmpty()) break;
            for (JsonNode j : data) out.add(paraVaga(j));
            if (data.size() < 100) break;
        }
        out.sort((a, b) -> b.publicada() == null ? -1 : a.publicada() == null ? 1 : b.publicada().compareTo(a.publicada()));
        return out.size() > max ? out.subList(0, max) : out;
    }

    static Vaga paraVaga(JsonNode j) {
        LocalDate pub = null;
        try {
            pub = OffsetDateTime.parse(j.path("publishedDate").asText()).toLocalDate();
        } catch (Exception ignored) {
            // data ausente ou em outro formato: vaga fica sem data e passa na recência
        }
        String local = (txt(j, "city") + " " + txt(j, "state") + " " + txt(j, "country")).trim();
        return new Vaga("Gupy", txt(j, "id"), txt(j, "careerPageName"), txt(j, "name"),
                txt(j, "jobUrl"), local, txt(j, "workplaceType"), pub, txt(j, "description"));
    }

    /** Texto do campo, ou vazio quando ausente/null (asText devolveria "null"). */
    private static String txt(JsonNode j, String campo) {
        JsonNode n = j.path(campo);
        return n.isMissingNode() || n.isNull() ? "" : n.asText();
    }

    @Override
    public Desfecho candidatar(Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar) {
        WebDriver d = navegador.driver();
        String applyUrl = vaga.url().replaceFirst("/job/.*$", "") + "/candidates/jobs/" + vaga.id() + "/apply";
        d.get(applyUrl);
        pausa(2500);
        String contexto = vaga.empresa() + " — " + vaga.cargo() + "\n" + vaga.descricao();
        for (int passo = 0; passo < 25 && continuar.getAsBoolean(); passo++) {
            String corpo = texto(d);
            String url = d.getCurrentUrl();
            if (corpo.contains("Candidatura finalizada")) return new Desfecho(Status.ENVIADA, "Candidatura finalizada");
            if (url.contains("login") || url.contains("signin") || corpo.contains("Entrar na sua conta"))
                return new Desfecho(Status.PRECISA_USUARIO, "login necessário no Gupy");
            if (corpo.toLowerCase().contains("captcha") || corpo.contains("verificação de segurança"))
                return new Desfecho(Status.PRECISA_USUARIO, "CAPTCHA/verificação");

            Desfecho r = preencherCampos(d, vaga, contexto, continuar);
            if (r != null) return r;

            WebElement botao = proximoBotao(d);
            if (botao == null) return new Desfecho(Status.ERRO, "nenhum botão de avanço encontrado (passo " + passo + ")");
            if (botao.getText().trim().equals("Finalizar candidatura") && dryRun)
                return new Desfecho(Status.PRONTA_DRY_RUN, "dry-run: formulário completo, não enviado");
            ((JavascriptExecutor) d).executeScript("arguments[0].click()", botao);
            pausa(2000);
        }
        return new Desfecho(Status.ERRO, "fluxo não terminou em 25 passos");
    }

    /** Preenche campos vazios e habilitados usando o rótulo como pergunta. Devolve desfecho se precisar parar. */
    private Desfecho preencherCampos(WebDriver d, Vaga vaga, String contexto, BooleanSupplier continuar) {
        for (WebElement el : d.findElements(By.cssSelector("input[type=text],input[type=number],input:not([type]),textarea"))) {
            if (!el.isDisplayed() || !el.isEnabled() || !valor(el).isBlank()) continue;
            String pergunta = rotulo(d, el);
            if (pergunta.isBlank()) continue;
            var res = situacoes.resolver("Gupy", pergunta, contexto, continuar);
            switch (res.tipo()) {
                case SENSIVEL -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "dado sensível: " + pergunta);
                }
                case SEM_RESPOSTA -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "sem resposta para: " + pergunta);
                }
                default -> el.sendKeys(res.resposta());
            }
        }
        // perguntas Sim/Não (radio) ficam para a próxima iteração com suporte dedicado
        return null;
    }

    private static String rotulo(WebDriver d, WebElement el) {
        Object o = ((JavascriptExecutor) d).executeScript(
                "var e=arguments[0];var l=e.labels&&e.labels[0];if(l)return l.innerText;"
                        + "var p=e.closest('label,div');return p?p.innerText:'';", el);
        return o == null ? "" : o.toString().trim();
    }

    private static String valor(WebElement el) {
        String v = el.getAttribute("value");
        return v == null ? "" : v;
    }

    private static WebElement proximoBotao(WebDriver d) {
        for (String t : BOTOES) {
            for (WebElement b : d.findElements(By.tagName("button"))) {
                if (b.isDisplayed() && b.isEnabled() && b.getText().trim().equals(t)) return b;
            }
        }
        return null;
    }

    private static String texto(WebDriver d) {
        return d.findElement(By.tagName("body")).getText();
    }

    private static void pausa(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void fechar() {
        navegador.fechar();
    }
}
