package com.huber.searchjob.engine;

import java.util.Optional;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * LinkedIn no Full search: busca sem o filtro de Candidatura simplificada. A simplificada segue o fluxo normal; na
 * externa, clica em "Candidatar-se", passa para a aba do site da empresa e o {@link AgenteExterno} (IA) conduz o
 * formulário até o fim.
 */
public class LinkedInFullDriver extends LinkedInDriver {
    public LinkedInFullDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        super(navegador, situacoes);
    }

    @Override
    protected String filtroBusca() {
        return "";
    }

    static boolean externa(String htmlVaga) {
        return htmlVaga.contains("apply-link-offsite");
    }

    /** Na página da vaga, o botão "Candidatar-se" (site externo): abre o site da empresa e entrega para a IA. */
    @Override
    protected Optional<Desfecho> casoEspecial(WebDriver d, String corpo) {
        if (!d.getCurrentUrl().contains("linkedin.com/jobs/view")) return Optional.empty();
        WebElement botao = botaoCandidatar(d);
        if (botao == null) return Optional.empty();
        java.util.Set<String> abas = d.getWindowHandles();
        String url = d.getCurrentUrl();
        ((JavascriptExecutor) d).executeScript("arguments[0].scrollIntoView({block:'center'})", botao);
        botao.click();
        int cliquesModal = 0;
        for (int espera = 0; espera < 12; espera++) {
            pausa(1000);
            for (String aba : d.getWindowHandles())
                if (!abas.contains(aba)) {
                    d.switchTo().window(aba);
                    pausa(4000); // o site da empresa costuma redirecionar algumas vezes até o formulário
                    return Optional.of(new Desfecho(Status.PULADA, MOTIVO_EXTERNA + ": site da empresa"));
                }
            if (!url.equals(d.getCurrentUrl()) && !d.getCurrentUrl().contains("linkedin.com")) {
                pausa(4000);
                return Optional.of(new Desfecho(Status.PULADA, MOTIVO_EXTERNA + ": site da empresa"));
            }
            // "Compartilhar seu perfil com a empresa?" / "Lembrete de segurança": confirma
            try {
                confirmarModal(d, cliquesModal++);
            } catch (org.openqa.selenium.WebDriverException e) {
                // modal recriado: tenta na próxima volta
            }
        }
        return Optional.of(new Desfecho(Status.ERRO, "o Candidatar-se do LinkedIn não abriu o site da empresa · "
                + LoginAutomatico.diagnostico(d) + " · modal: " + diagnosticoModal(d) + " · foto em " + GupyDriver.capturar(d)));
    }

    /**
     * Confirma o modal com clique nativo: o clique por JS num link target=_blank não tem gesto de usuário e o
     * bloqueador de pop-up do Firefox engole a aba nova. Se o clique já foi tentado e nada abriu, segue o href do
     * link na própria aba.
     */
    private static void confirmarModal(WebDriver d, int tentativa) {
        Object achado = ((JavascriptExecutor) d).executeScript(CONFIRMAR_MODAL);
        if (!(achado instanceof WebElement continuar)) return;
        String href = continuar.getAttribute("href");
        if (tentativa >= 2 && href != null && href.startsWith("http")) {
            d.get(destinoRedirect(href));
            return;
        }
        try {
            continuar.click();
        } catch (org.openqa.selenium.WebDriverException e) {
            ((JavascriptExecutor) d).executeScript("arguments[0].click()", continuar);
        }
    }

    /** Link de redirecionamento do LinkedIn (…/redir/redirect?url=…): o destino real vem no parâmetro url. */
    static String destinoRedirect(String href) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]url=([^&]+)").matcher(href);
        if (!href.contains("linkedin.com") || !m.find()) return href;
        return java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Onde está o "Continuar" do modal (tag, papel, shadow DOM, iframe): para entender quando o clique não acontece. */
    private static String diagnosticoModal(WebDriver d) {
        try {
            return String.valueOf(((JavascriptExecutor) d).executeScript("""
                    var out=[];
                    function ver(raiz,onde){raiz.querySelectorAll('*').forEach(function(e){
                      if(e.shadowRoot)ver(e.shadowRoot,onde+'>'+e.tagName.toLowerCase()+'#shadow');
                      var t=(e.innerText||e.textContent||'').trim();
                      if(e.children.length==0&&/continuar/i.test(t)&&t.length<60){var p=e.closest('a,button,[role=button]');
                        out.push(onde+': '+t+' <'+e.tagName.toLowerCase()+'> clicável='+(p?p.tagName.toLowerCase()+(p.getAttribute('role')?'['+p.getAttribute('role')+']':''):'nenhum'));}});}
                    ver(document,'doc');
                    out.push('iframes='+document.querySelectorAll('iframe').length);
                    return out.slice(0,10).join(' | ');"""));
        } catch (org.openqa.selenium.WebDriverException e) {
            return "indisponível";
        }
    }

    /**
     * Clica no "Continuar" / "Continuar para o site" / "Continuar candidatura" do modal que o LinkedIn abre antes do
     * site da empresa. Procura também em shadow DOM; o "Continuar candidatura" do lembrete de segurança é um link num
     * modal sem role=dialog. Só devolve o elemento: quem clica é o {@link #confirmarModal}.
     */
    static final String CONFIRMAR_MODAL = """
            function achar(raiz){
              var els=raiz.querySelectorAll('button,a,[role=button]');
              for(var i=0;i<els.length;i++){var e=els[i],r=e.getBoundingClientRect();
                var t=(e.innerText||'').toLowerCase().replace(/\\s+/g,' ').trim();
                if(r.width<=0||r.height<=0)continue;
                // o lembrete de segurança não tem role=dialog: "Continuar candidatura" vale em qualquer lugar
                if(t.indexOf('continuar candidatura')==0||t=='continue applying')return e;
                if(e.closest('[role=dialog],[role=alertdialog],.artdeco-modal')&&
                   (t=='continuar'||t.indexOf('continuar para')==0||t=='continue'||t.indexOf('continue to')==0))return e;}
              var todos=raiz.querySelectorAll('*');
              for(var j=0;j<todos.length;j++)if(todos[j].shadowRoot){var a=achar(todos[j].shadowRoot);if(a)return a;}
              return null;}
            return achar(document);
            """;

    /**
     * Procura o "Candidatar-se" numa só chamada JS: a página da vaga tem mais de mil botões/links, e ler cada um pelo
     * Selenium (isDisplayed + getText + getAttribute) levava minutos e derrubava a conexão com o Firefox.
     */
    private static final String ACHAR_CANDIDATAR = """
            function n(s){return String(s||'').normalize('NFD').replace(/[\\u0300-\\u036f]+/g,'').toLowerCase()
                .replace(/[^a-z0-9]+/g,' ').trim();}
            var els=document.querySelectorAll('.jobs-apply-button,button,a');
            for(var i=0;i<els.length;i++){var b=els[i];
              if(!b.getClientRects().length||getComputedStyle(b).visibility==='hidden')continue;
              var t=n(b.innerText),rot=n(b.getAttribute('aria-label'));
              if(t.indexOf('simplificada')>=0||t.indexOf('easy apply')>=0)continue;
              if(t=='candidatar se'||t=='apply'||rot.indexOf('candidatar se')==0&&rot.indexOf('site')>=0)return b;}
            return null;
            """;

    /** O botão "Candidatar-se" (com ícone de link externo); nunca o de Candidatura simplificada. */
    private static WebElement botaoCandidatar(WebDriver d) {
        try {
            return ((JavascriptExecutor) d).executeScript(ACHAR_CANDIDATAR) instanceof WebElement b ? b : null;
        } catch (org.openqa.selenium.WebDriverException e) {
            return null;
        }
    }
}
