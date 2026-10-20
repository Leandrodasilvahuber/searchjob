package com.huber.searchjob.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/**
 * Teste manual: abre o Firefox do robô numa página com os links de login dos portais e registra o que o usuário faz
 * (URLs, botões clicados, login digitado) até existir o arquivo de parada ou passar o tempo:
 * mvn test -Dtest=MonitorLoginAoVivoTest -Dlive=true -Dsaida=/pasta [-Dminutos=40].
 */
@EnabledIfSystemProperty(named = "live", matches = "true")
class MonitorLoginAoVivoTest {
    /** Guarda os cliques em window.name, que sobrevive à troca de página na mesma aba. */
    private static final String GRAVAR_CLIQUES = """
            if(!window.__monitor){window.__monitor=1;document.addEventListener('click',function(e){
              var b=e.target.closest('button,a,[role=button],input[type=submit],label');if(!b)return;
              var t=(b.innerText||b.getAttribute('aria-label')||b.value||'').trim().replace(/\\s+/g,' ').slice(0,60);
              window.name=(window.name||'')+'\\n'+location.host+' :: '+b.tagName+' :: '+t;},true);}
            var n=window.name||'';window.name='';return n;""";

    private static final String INICIO = """
            <h2>SearchJob — login nos portais</h2><p>Entre em cada um (marque "lembrar-me" / "manter conectado"):</p><ol>
            <li><a href="https://www.linkedin.com/login">LinkedIn</a></li>
            <li><a href="https://login.gupy.io/candidates/signin">Gupy</a></li>
            <li><a href="https://auth.vagas.solides.com.br/sign-in">Sólides</a></li>
            <li><a href="https://www.catho.com.br/signin/">Catho</a></li>
            <li><a href="https://secure.indeed.com/auth">Indeed</a></li>
            <li><a href="https://programathor.com.br/users/sign_in">ProgramaThor (já logado)</a></li></ol>""";

    @Test
    void monitora() throws Exception {
        Path saida = Path.of(System.getProperty("saida", "target/monitor-login"));
        Files.createDirectories(saida);
        Path log = saida.resolve("monitor.log");
        Path parar = saida.resolve("parar");
        Files.deleteIfExists(parar);
        long fim = System.currentTimeMillis() + Long.getLong("minutos", 40) * 60_000;
        NavegadorFirefox nav = new NavegadorFirefox();
        try {
            WebDriver d = nav.driver();
            // o Firefox do snap não lê arquivos fora da home: a página de início vai como data: URL
            d.get("data:text/html;charset=utf-8;base64," + java.util.Base64.getEncoder()
                    .encodeToString(("<meta charset=utf-8>" + INICIO).getBytes(StandardCharsets.UTF_8)));
            String urlAnterior = "";
            String loginAnterior = "";
            int abas = 1;
            escrever(log, "monitor iniciado");
            while (System.currentTimeMillis() < fim && !Files.exists(parar)) {
                Thread.sleep(1000);
                try {
                    Set<String> handles = d.getWindowHandles();
                    if (handles.size() != abas) {
                        escrever(log, "janelas/abas: " + abas + " → " + handles.size());
                        abas = handles.size();
                    }
                    String url = d.getCurrentUrl();
                    if (!url.equals(urlAnterior)) escrever(log, "URL " + url);
                    urlAnterior = url;
                    Object cliques = ((JavascriptExecutor) d).executeScript(GRAVAR_CLIQUES);
                    for (String c : String.valueOf(cliques).split("\n")) if (!c.isBlank()) escrever(log, "CLIQUE " + c);
                    String[] lido = LoginAutomatico.ler(d);
                    String login = lido[0] + " | " + lido[1];
                    if ((lido[0] != null || lido[1] != null) && !login.equals(loginAnterior))
                        escrever(log, "DIGITADO " + java.net.URI.create(url).getHost() + " :: usuario=" + lido[0] + " :: senha=" + lido[1]);
                    loginAnterior = login;
                } catch (org.openqa.selenium.NoSuchWindowException e) {
                    escrever(log, "a aba controlada foi fechada — voltando para outra");
                    d.switchTo().window(d.getWindowHandles().iterator().next());
                } catch (org.openqa.selenium.WebDriverException e) {
                    // página trocando no meio da leitura
                }
            }
            escrever(log, "monitor encerrado");
        } finally {
            nav.fechar();
        }
    }

    private static void escrever(Path log, String linha) throws java.io.IOException {
        Files.writeString(log, LocalTime.now().withNano(0) + " " + linha + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
