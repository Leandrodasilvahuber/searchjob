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
            if (d.getCurrentUrl().contains("linkedin.com") && d.getCurrentUrl().contains("login")) {
                // cai na tela de login do LinkedIn (/uas/login ou /login): preenche com o login salvo
                if (!LoginAutomatico.entrar(d, usuario, senha)) return false;
                pausa(6000);
            }
            return !paginaDeLogin(d, d.getCurrentUrl()) && !d.getCurrentUrl().contains("linkedin.com");
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
        out.sort(Vaga.MAIS_RECENTE);
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
                if (b.isDisplayed() && !b.getText().isBlank()) botoes.append('[').append(b.getText().trim()).append(b.isEnabled() ? "" : " (desabilitado)").append(']');
            int radios = d.findElements(By.cssSelector("input[type=radio]")).size();
            int marcados = d.findElements(By.cssSelector("input[type=radio]:checked")).size();
            String t = texto(d).replaceAll("\\s+", " ");
            return "url=" + d.getCurrentUrl() + " botões=" + botoes + " radios=" + marcados + "/" + radios
                    + " inválidos=" + camposInvalidos(d) + " captura=" + capturar(d)
                    + " texto=…" + (t.length() > 300 ? t.substring(t.length() - 300) : t);
        } catch (Exception e) {
            return "diagnóstico indisponível";
        }
    }

    /** Campos visíveis que o navegador/a página marcam como inválidos (obrigatórios vazios): é o que impede o "Salvar e continuar". */
    private static String camposInvalidos(WebDriver d) {
        try {
            Object o = ((JavascriptExecutor) d).executeScript(
                    "var r=[];document.querySelectorAll('input,select,textarea').forEach(function(e){"
                            + "if(e.type==='hidden'||e.type==='radio'&&e.checked)return;"
                            + "var inv=e.getAttribute('aria-invalid')==='true'||(e.required&&!e.value&&e.type!=='radio'&&e.type!=='checkbox')||(e.type==='checkbox'&&e.required&&!e.checked);"
                            + "if(!inv||e.getClientRects().length===0&&!(e.labels&&e.labels[0]&&e.labels[0].getClientRects().length))return;"
                            + "var l=(e.labels&&e.labels[0])?e.labels[0].innerText:(e.getAttribute('aria-label')||e.name||e.id);"
                            + "r.push(e.tagName.toLowerCase()+'['+(e.type||'')+']:'+String(l).trim().substring(0,80));});"
                            + "return r.join(' | ');");
            return o == null || o.toString().isBlank() ? "nenhum" : o.toString();
        } catch (Exception e) {
            return "indisponível";
        }
    }

    /** Foto da janela do robô no momento do erro, em data/erros (só a janela do Firefox dirigido, não a sua tela). Usada também pelos outros portais. */
    static String capturar(WebDriver d) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("data", "erros");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path png = dir.resolve("gupy-" + System.currentTimeMillis() + ".png");
            java.nio.file.Files.write(png, ((org.openqa.selenium.TakesScreenshot) d).getScreenshotAs(org.openqa.selenium.OutputType.BYTES));
            return png.toString();
        } catch (Exception e) {
            return "indisponível";
        }
    }

    /** Página "/reproved": a Gupy eliminou a candidatura por uma pergunta eliminatória ou pela triagem. Não é falha do robô. */
    private static Desfecho reprovada(String corpo) {
        String t = corpo.replaceAll("\\s+", " ");
        int i = t.indexOf("Pergunta eliminatória:");
        String pergunta = i < 0 ? "" : t.substring(i + 22, Math.min(t.length(), i + 22 + 200)).trim();
        return new Desfecho(Status.PULADA, "reprovado pelo Gupy" + (pergunta.isEmpty() ? " na triagem automática" : " · eliminatória: " + pergunta));
    }

    /** Um passo do formulário. Devolve o desfecho final, ou null para seguir ao próximo passo. */
    private Desfecho passo(WebDriver d, Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar, String contexto,
                           int passo) {
        {
            String corpo = texto(d);
            String url = d.getCurrentUrl();
            if (corpo.contains("Candidatura finalizada")) return new Desfecho(Status.ENVIADA, "Candidatura finalizada");
            if (jaCandidatou(corpo)) return new Desfecho(Status.PULADA, "já candidatada nesta conta");
            if (url.contains("/reproved")) return reprovada(corpo);
            if (paginaDeLogin(d, url))
                return new Desfecho(Status.PRECISA_USUARIO, "login necessário no Gupy");
            if (corpo.toLowerCase().contains("captcha") || corpo.contains("verificação de segurança"))
                return new Desfecho(Status.PRECISA_USUARIO, "CAPTCHA/verificação");

            Desfecho r = preencherCampos(d, vaga, contexto, cv, continuar);
            if (r != null) return r;

            WebElement botao = null;
            for (int t = 0; t < 10 && botao == null; t++) {
                botao = proximoBotao(d);
                if (botao == null) {
                    fecharCookies(d);
                    pausa(1000); // o botão pode estar desabilitado enquanto a etapa carrega
                }
            }
            if (botao == null && d.getCurrentUrl().contains("/reproved")) return reprovada(texto(d));
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
            if (Texto.perguntaEmIngles(pergunta)) return new Desfecho(Status.PULADA, "vaga em inglês: " + pergunta);
            boolean area = "textarea".equalsIgnoreCase(el.getTagName());
            var res = situacoes.resolver("Gupy", pergunta, contexto, cv, area, PortalWebDriver.limiteDoCampo(el, pergunta), continuar);
            switch (res.tipo()) {
                case SENSIVEL -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "dado sensível: " + pergunta);
                }
                case SEM_RESPOSTA -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "sem resposta para: " + pergunta);
                }
                default -> escrever(d, el, PortalWebDriver.ajustar(el, pergunta, res.resposta()));
            }
        }
        return Radios.preencher(d, situacoes, "Gupy", contexto, cv, continuar);
    }

    /**
     * Escreve por JavaScript (setter nativo + eventos de input/change, que o React do Gupy enxerga): digitar tecla a tecla
     * com {@code sendKeys} chegou a levar 2 minutos numa página pesada. Se o valor não grudar, digita.
     */
    private static void escrever(WebDriver d, WebElement el, String valor) {
        try {
            ((JavascriptExecutor) d).executeScript(
                    "var e=arguments[0];e.scrollIntoView({block:'center'});e.focus();"
                            + "var set=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value').set;set.call(e,arguments[1]);"
                            + "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));"
                            + "e.dispatchEvent(new Event('blur',{bubbles:true}));", el, valor);
            if (valor.equals(valor(el))) return;
        } catch (org.openqa.selenium.WebDriverException e) {
            // cai no teclado abaixo
        }
        el.sendKeys(valor);
    }

    /**
     * Pergunta do campo: o label (mesmo curto, como "UF") se não for só uma dica ("Máx. 1000 caracteres"); senão sobe pelos
     * ancestrais, parando antes de um que contenha mais de um campo (para não pegar a pergunta de outro).
     */
    static String rotulo(WebDriver d, WebElement el) {
        Object o = ((JavascriptExecutor) d).executeScript(
                "var e=arguments[0];var dica=/^(m[aá]x\\.?|min\\.?|obrigat|opcional|\\*|\\d+\\s*\\/\\s*\\d+)|caracteres\\s*$/i;"
                        + "function bom(t,min){t=(t||'').trim();return t.length>=min&&!dica.test(t);}"
                        + "var l=e.labels&&e.labels[0];if(l&&bom(l.innerText,1))return l.innerText;"
                        + "var a=e.parentElement;for(var i=0;i<6&&a;i++){"
                        + "if(a.querySelectorAll('input:not([type=hidden]):not([type=radio]),textarea,select').length>1)break;"
                        + "var ls=a.innerText.split('\\n').map(function(s){return s.trim();}).filter(function(s){return bom(s,3);});"
                        + "if(ls.length)return ls[0];a=a.parentElement;}"
                        + "var p=e.closest('label,div');return p?p.innerText:'';", el);
        return o == null ? "" : o.toString().trim();
    }

    private static String valor(WebElement el) {
        String v = el.getAttribute("value");
        return v == null ? "" : v;
    }

    private static WebElement proximoBotao(WebDriver d) {
        for (String t : BOTOES) {
            String alvo = Texto.normalizar(t);
            for (WebElement b : d.findElements(By.tagName("button"))) {
                if (b.isDisplayed() && b.isEnabled() && Texto.normalizar(b.getText()).equals(alvo)
                        && !"true".equals(b.getAttribute("aria-disabled"))) return b;
            }
        }
        return null;
    }

    /** O banner de cookies pode cobrir a página e atrapalhar a etapa: recusa se estiver visível. */
    private static void fecharCookies(WebDriver d) {
        try {
            for (WebElement b : d.findElements(By.tagName("button")))
                if (b.isDisplayed() && Texto.normalizar(b.getText()).matches("reject all|rejeitar todos|recusar")) {
                    ((JavascriptExecutor) d).executeScript("arguments[0].click()", b);
                    return;
                }
        } catch (RuntimeException e) {
            // banner sumiu no meio: ignora
        }
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
