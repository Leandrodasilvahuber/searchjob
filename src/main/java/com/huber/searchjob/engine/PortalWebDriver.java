package com.huber.searchjob.engine;

import com.huber.searchjob.db.Cv;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Base dos portais que candidatam pelo Firefox seguindo um laço genérico:
 * detecta sucesso/login/CAPTCHA → preenche campos de texto via {@link SituacaoService} → clica no próximo botão.
 * Botões de "envio" encerram o fluxo: em dry-run o laço para antes de clicar neles.
 */
public abstract class PortalWebDriver implements PortalDriver {
    protected final NavegadorFirefox navegador;
    protected final SituacaoService situacoes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    protected PortalWebDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        this.navegador = navegador;
        this.situacoes = situacoes;
    }

    /** Botões que apenas avançam o fluxo (Continuar, Próximo...). */
    protected abstract String[] botoesAvanco();

    /** Botões que enviam a candidatura. */
    protected abstract String[] botoesEnvio();

    /** Trechos (sem acento, minúsculos) que confirmam a candidatura. */
    protected abstract String[] textosSucesso();

    /** Trechos que indicam candidatura já feita por esta conta. */
    protected String[] textosJaCandidatou() {
        return new String[] {"voce ja se candidatou", "candidatura ja enviada", "ja se candidatou"};
    }

    /** Ponto de extensão para casos próprios do portal (ex.: vaga externa). */
    protected Optional<Desfecho> casoEspecial(WebDriver d, String corpoNormalizado) {
        return Optional.empty();
    }

    protected String get(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0")
                .header("Accept-Language", "pt-BR,pt;q=0.9").timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return "";
        if (r.statusCode() / 100 != 2) throw new IllegalStateException(nome() + " busca HTTP " + r.statusCode());
        return r.body();
    }

    @Override
    public Desfecho candidatar(Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar) {
        WebDriver d = navegador.driver();
        d.get(vaga.url());
        pausa(2500);
        String contexto = vaga.empresa() + " — " + vaga.cargo() + "\n" + vaga.descricao();
        for (int passo = 0; passo < 20 && continuar.getAsBoolean(); passo++) {
            String corpo = Texto.normalizar(d.findElement(By.tagName("body")).getText());
            for (String s : textosSucesso())
                if (corpo.contains(s)) return new Desfecho(Status.ENVIADA, s);
            for (String s : textosJaCandidatou())
                if (corpo.contains(s)) return new Desfecho(Status.PULADA, "já candidatada nesta conta");
            if (corpo.contains("captcha") || corpo.contains("security check") || corpo.contains("just a moment")
                    || corpo.contains("verifique que voce e humano") || corpo.contains("verificacao de seguranca"))
                return new Desfecho(Status.PRECISA_USUARIO, "CAPTCHA/verificação em " + nome());
            if (d.findElements(By.cssSelector("input[type=password]")).stream().anyMatch(WebElement::isDisplayed))
                return new Desfecho(Status.PRECISA_USUARIO, "login necessário em " + nome());
            Optional<Desfecho> especial = casoEspecial(d, corpo);
            if (especial.isPresent()) return especial.get();

            Desfecho r = preencherCampos(d, contexto, cv, continuar);
            if (r != null) return r;

            WebElement envio = achar(d, botoesEnvio());
            WebElement botao = envio != null ? envio : achar(d, botoesAvanco());
            if (botao == null) return new Desfecho(Status.ERRO, "nenhum botão de avanço/envio encontrado (passo " + passo + ")");
            if (envio != null && dryRun)
                return new Desfecho(Status.PRONTA_DRY_RUN, "dry-run: formulário pronto, não enviado");
            ((JavascriptExecutor) d).executeScript("arguments[0].click()", botao);
            pausa(2500);
        }
        return new Desfecho(Status.ERRO, "fluxo não terminou em 20 passos");
    }

    private Desfecho preencherCampos(WebDriver d, String contexto, Cv cv, BooleanSupplier continuar) {
        for (WebElement el : d.findElements(By.cssSelector("input[type=text],input[type=number],input:not([type]),textarea"))) {
            try {
                if (!el.isDisplayed() || !el.isEnabled()) continue;
                String atual = el.getAttribute("value");
                if (atual != null && !atual.isBlank()) continue;
                String pergunta = rotulo(d, el);
                if (pergunta.isBlank() || ehBusca(pergunta)) continue;
                boolean area = "textarea".equalsIgnoreCase(el.getTagName());
                var res = situacoes.resolver(nome(), pergunta, contexto, cv, area, continuar);
                switch (res.tipo()) {
                    case SENSIVEL -> {
                        return new Desfecho(Status.PRECISA_USUARIO, "dado sensível: " + pergunta);
                    }
                    case SEM_RESPOSTA -> {
                        return new Desfecho(Status.PRECISA_USUARIO, "sem resposta para: " + pergunta);
                    }
                    default -> el.sendKeys(res.resposta());
                }
            } catch (org.openqa.selenium.StaleElementReferenceException e) {
                return null; // a página mudou; o laço principal reavalia
            }
        }
        return null;
    }

    private static boolean ehBusca(String rotulo) {
        String n = Texto.normalizar(rotulo);
        return n.equals("buscar") || n.contains("pesquis") || n.contains("search");
    }

    private static String rotulo(WebDriver d, WebElement el) {
        Object o = ((JavascriptExecutor) d).executeScript(
                "var e=arguments[0];var l=e.labels&&e.labels[0];if(l)return l.innerText;"
                        + "return e.getAttribute('aria-label')||e.getAttribute('placeholder')||"
                        + "(e.closest('label,div')?e.closest('label,div').innerText:'');", el);
        return o == null ? "" : o.toString().trim();
    }

    private static WebElement achar(WebDriver d, String[] textos) {
        for (String t : textos) {
            String alvo = Texto.normalizar(t);
            for (WebElement b : d.findElements(By.cssSelector("button,a.btn,input[type=submit],[role=button]"))) {
                try {
                    String rot = b.getText();
                    if (rot == null || rot.isBlank()) rot = b.getAttribute("aria-label") == null ? b.getAttribute("value") : b.getAttribute("aria-label");
                    if (b.isDisplayed() && b.isEnabled() && rot != null && Texto.normalizar(rot).equals(alvo)) return b;
                } catch (org.openqa.selenium.StaleElementReferenceException ignored) {
                    // elemento trocado durante a leitura: segue para o próximo
                }
            }
        }
        return null;
    }

    protected static void pausa(long ms) {
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
