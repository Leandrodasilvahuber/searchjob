package com.huber.searchjob.engine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/**
 * Indeed: bloqueia HTTP simples (Cloudflare), então a busca também roda no Firefox.
 * Só candidata em "Candidatura simplificada"/Indeed Apply; vaga que leva ao site da empresa é pulada.
 */
public class IndeedDriver extends PortalWebDriver {
    private final int recenciaDias;

    public IndeedDriver(NavegadorFirefox navegador, SituacaoService situacoes, int recenciaDias) {
        super(navegador, situacoes);
        this.recenciaDias = recenciaDias;
    }

    @Override
    public String nome() {
        return "Indeed";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Vaga> buscar(String termo, int max) {
        WebDriver d = navegador.driver();
        d.get("https://br.indeed.com/jobs?q=" + URLEncoder.encode(termo, StandardCharsets.UTF_8)
                + "&fromage=" + recenciaDias + "&sort=date");
        pausa(3500);
        String corpo = Texto.normalizar(d.findElement(By.tagName("body")).getText());
        if (corpo.contains("security check") || corpo.contains("just a moment") || corpo.contains("captcha"))
            throw new IllegalStateException("CAPTCHA/verificação no Indeed — resolva na janela do Firefox e inicie de novo");
        if (pedeLoginGoogle(corpo) || menuAcessarVisivel(d)
                || !d.findElements(By.cssSelector("iframe[src*='accounts.google.com']")).isEmpty())
            throw new IllegalStateException("LOGIN necessário no Indeed (menu Acessar/modal do Google) — entre na janela do Firefox e responda \"continuar\"");
        Object r = lerCards(d);
        if (((List<Object>) r).isEmpty())
            throw new IllegalStateException("Indeed sem vagas na página · título: " + d.getTitle() + " · url: "
                    + d.getCurrentUrl() + " · texto: " + corpo.substring(0, Math.min(corpo.length(), 220)));
        List<Vaga> out = new ArrayList<>();
        for (Object o : (List<Object>) r) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (out.size() >= max) break;
            String id = String.valueOf(m.get("id"));
            // fromage=<dias> já restringe a recência; vaga sem data própria passa pelo filtro
            out.add(new Vaga("Indeed", id, String.valueOf(m.get("empresa")), String.valueOf(m.get("titulo")),
                    id.isBlank() ? String.valueOf(m.get("url")) : "https://br.indeed.com/viewjob?jk=" + id,
                    String.valueOf(m.get("local")), "", (LocalDate) null, ""));
        }
        return out;
    }

    /** A página do Indeed costuma trocar (modal/redirecionamento) logo após carregar: repete a leitura uma vez. */
    private static Object lerCards(WebDriver d) {
        try {
            return lerCardsUmaVez(d);
        } catch (org.openqa.selenium.StaleElementReferenceException e) {
            pausa(2000);
            return lerCardsUmaVez(d);
        }
    }

    private static Object lerCardsUmaVez(WebDriver d) {
        return ((JavascriptExecutor) d).executeScript(
                "return [...document.querySelectorAll('a.jcs-JobTitle, h2.jobTitle a')].map(a=>{"
                        + "var c=a.closest('.job_seen_beacon,.cardOutline,li')||a.parentElement;"
                        + "var e=c.querySelector('[data-testid=company-name]');var l=c.querySelector('[data-testid=text-location]');"
                        + "return {id:a.getAttribute('data-jk')||'',titulo:a.innerText.trim(),url:a.href,"
                        + "empresa:e?e.innerText.trim():'',local:l?l.innerText.trim():''};});");
    }

    /** Sessão deslogada: o Indeed mostra "Acessar" no topo da página (e às vezes um modal "Continuar com o Google"). */
    static boolean pedeLoginGoogle(String corpoNormalizado) {
        return corpoNormalizado.contains("continuar com o google") || corpoNormalizado.contains("continue with google")
                || corpoNormalizado.contains("fazer login com o google") || corpoNormalizado.contains("sign in with google");
    }

    private static boolean menuAcessarVisivel(WebDriver d) {
        Object r = ((JavascriptExecutor) d).executeScript(
                "return [...document.querySelectorAll('header a, header button, nav a, nav button, [data-gnav-element-name] a')]"
                        + ".some(e=>{var t=(e.innerText||e.getAttribute('aria-label')||'').trim().toLowerCase();"
                        + "return e.offsetParent!==null&&(t==='acessar'||t==='fazer login'||t==='sign in');});");
        return Boolean.TRUE.equals(r);
    }

    /** Deslogado, redireciona para secure.indeed.com/auth. */
    @Override
    protected String paginaLogada() {
        return "https://myjobs.indeed.com/";
    }

    @Override
    protected String paginaLogin() {
        return "https://secure.indeed.com/auth";
    }

    @Override
    protected Optional<Desfecho> casoEspecial(WebDriver d, String corpo) {
        boolean simplificada = !d.findElements(By.cssSelector("#indeedApplyButton, [id*=indeedApply], button[aria-label*='Candidatura simplificada' i]")).isEmpty()
                || corpo.contains("candidatura simplificada") || corpo.contains("indeed apply");
        boolean externa = corpo.contains("candidate se no site da empresa") || corpo.contains("candidatar se no site da empresa")
                || corpo.contains("apply on company site");
        if (externa && !simplificada) return Optional.of(new Desfecho(Status.PULADA, "candidatura externa (site da empresa)"));
        return Optional.empty();
    }

    @Override
    protected String[] botoesAvanco() {
        return new String[] {"Candidatura simplificada", "Candidate-se agora", "Continuar", "Continue", "Aplicar", "Apply now"};
    }

    @Override
    protected String[] botoesEnvio() {
        return new String[] {"Enviar candidatura", "Enviar sua candidatura", "Submit your application"};
    }

    @Override
    protected String[] textosSucesso() {
        return new String[] {"candidatura foi enviada", "sua candidatura foi enviada", "application has been submitted", "candidatura enviada"};
    }
}
