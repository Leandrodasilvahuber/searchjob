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
    private static final String[] BOTOES = {"Finalizar candidatura", "Continuar", "Salvar e continuar", "Responder agora", "Próximo"};

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
    public boolean pedeCaptcha() {
        return LoginAutomatico.pedeCaptcha(navegador.driver());
    }

    @Override
    public String diagnostico() {
        return LoginAutomatico.diagnostico(navegador.driver());
    }

    @Override
    public String[] lerLogin() {
        return LoginAutomatico.ler(navegador.driver());
    }

    @Override
    public boolean entrar(String usuario, String senha) {
        WebDriver d = navegador.driver();
        // A conta do Gupy entra pelo LinkedIn: a senha do formulário e-mail/senha não vale, então clica no botão do LinkedIn
        WebElement linkedin = botaoLinkedin(d);
        if (linkedin != null) {
            ((JavascriptExecutor) d).executeScript("arguments[0].click()", linkedin);
            pausa(6000);
            return !paginaDeLogin(d, d.getCurrentUrl()) && !d.getCurrentUrl().contains("linkedin.com/login");
        }
        return LoginAutomatico.entrar(d, usuario, senha);
    }

    private static WebElement botaoLinkedin(WebDriver d) {
        for (WebElement e : d.findElements(By.cssSelector("button,a,[role=button]"))) {
            try {
                if (e.isDisplayed() && e.isEnabled() && (e.getText() + " " + e.getAttribute("aria-label")).toLowerCase().contains("linkedin"))
                    return e;
            } catch (org.openqa.selenium.StaleElementReferenceException ignored) {
                // elemento trocado durante a leitura
            }
        }
        return null;
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
        String anterior = null;
        int parados = 0;
        for (int passo = 0; passo < 25 && continuar.getAsBoolean(); passo++) {
            try {
                String assinatura = d.getCurrentUrl() + "|" + texto(d).hashCode();
                parados = assinatura.equals(anterior) ? parados + 1 : 0;
                anterior = assinatura;
                if (parados >= 3)
                    return new Desfecho(Status.ERRO, "sem progresso: o botão não avança a página (campo obrigatório sem resposta?) · "
                            + diagnosticoPagina(d));
                Desfecho fim = passo(d, vaga, cv, dryRun, continuar, contexto, passo);
                if (fim != null) return fim;
            } catch (org.openqa.selenium.StaleElementReferenceException e) {
                pausa(1000); // a página mudou no meio do passo: reavalia do começo
            }
        }
        return new Desfecho(Status.ERRO, "fluxo não terminou em 25 passos · " + diagnosticoPagina(d));
    }

    /** URL, botões visíveis e fim do texto da página, para entender onde o fluxo travou. */
    private static String diagnosticoPagina(WebDriver d) {
        try {
            StringBuilder botoes = new StringBuilder();
            for (WebElement b : d.findElements(By.tagName("button")))
                if (b.isDisplayed() && !b.getText().isBlank()) botoes.append('[').append(b.getText().trim()).append(']');
            int radios = d.findElements(By.cssSelector("input[type=radio]")).size();
            int marcados = d.findElements(By.cssSelector("input[type=radio]:checked")).size();
            String t = texto(d).replaceAll("\\s+", " ");
            return "url=" + d.getCurrentUrl() + " botões=" + botoes + " radios=" + marcados + "/" + radios
                    + " texto=…" + (t.length() > 300 ? t.substring(t.length() - 300) : t);
        } catch (Exception e) {
            return "diagnóstico indisponível";
        }
    }

    /** Um passo do formulário. Devolve o desfecho final, ou null para seguir ao próximo passo. */
    private Desfecho passo(WebDriver d, Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar, String contexto,
                           int passo) {
        {
            String corpo = texto(d);
            String url = d.getCurrentUrl();
            if (corpo.contains("Candidatura finalizada")) return new Desfecho(Status.ENVIADA, "Candidatura finalizada");
            if (jaCandidatou(corpo)) return new Desfecho(Status.PULADA, "já candidatada nesta conta");
            if (paginaDeLogin(d, url))
                return new Desfecho(Status.PRECISA_USUARIO, "login necessário no Gupy");
            if (corpo.toLowerCase().contains("captcha") || corpo.contains("verificação de segurança"))
                return new Desfecho(Status.PRECISA_USUARIO, "CAPTCHA/verificação");

            Desfecho r = preencherCampos(d, vaga, contexto, cv, continuar);
            if (r != null) return r;

            WebElement botao = proximoBotao(d);
            if (botao == null) return new Desfecho(Status.ERRO, "nenhum botão de avanço encontrado (passo " + passo + ") · " + diagnosticoPagina(d));
            if (botao.getText().trim().equals("Finalizar candidatura") && dryRun)
                return new Desfecho(Status.PRONTA_DRY_RUN, "dry-run: formulário completo, não enviado");
            ((JavascriptExecutor) d).executeScript("arguments[0].click()", botao);
            pausa(2000);
        }
        return null;
    }

    /** A página de uma vaga já candidatada não tem botão de avanço: avisa em vez disso. */
    static boolean jaCandidatou(String corpo) {
        String n = Texto.normalizar(corpo);
        return n.contains("voce ja se candidatou") || n.contains("ja se candidatou") || n.contains("candidatura ja enviada")
                || n.contains("candidatura enviada") || n.contains("voce ja participa") || n.contains("acompanhar candidatura");
    }

    /**
     * Página de login = caminho /login ou /signin, ou campo de senha visível. Não usa o texto "Entrar na sua conta":
     * ele aparece também no cabeçalho de páginas com a sessão já ativa (falso positivo que prendia a retomada).
     */
    private static boolean paginaDeLogin(WebDriver d, String url) {
        String caminho = java.net.URI.create(url).getPath();
        if (caminho != null && (caminho.contains("/login") || caminho.contains("/signin"))) return true;
        return d.findElements(By.cssSelector("input[type=password]")).stream().anyMatch(WebElement::isDisplayed);
    }

    /** Preenche campos vazios e habilitados usando o rótulo como pergunta. Devolve desfecho se precisar parar. */
    private Desfecho preencherCampos(WebDriver d, Vaga vaga, String contexto, Cv cv, BooleanSupplier continuar) {
        for (WebElement el : d.findElements(By.cssSelector("input[type=text],input[type=number],input:not([type]),textarea"))) {
            if (!el.isDisplayed() || !el.isEnabled() || !valor(el).isBlank()) continue;
            String pergunta = rotulo(d, el);
            if (pergunta.isBlank()) continue;
            boolean area = "textarea".equalsIgnoreCase(el.getTagName());
            var res = situacoes.resolver("Gupy", pergunta, contexto, cv, area, continuar);
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
