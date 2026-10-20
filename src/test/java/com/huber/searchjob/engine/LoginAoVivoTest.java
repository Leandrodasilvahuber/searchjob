package com.huber.searchjob.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

/**
 * Teste manual do login nos portais reais, com o perfil do Firefox do robô (o SearchJob não pode estar com sessão rodando):
 * mvn test -Dtest=LoginAoVivoTest -Dlive=true -Dportais=catho,gupy [-Dsair=false] [-Dcapturas=/pasta].
 * Por portal: confere a sessão, desloga (apaga os cookies dos domínios do portal), entra com o LOGIN_EMAIL/LOGIN_SENHA do
 * .env pelo formulário e-mail/senha e confere de novo.
 */
@EnabledIfSystemProperty(named = "live", matches = "true")
class LoginAoVivoTest {
    /** Páginas de cada domínio onde o portal guarda a sessão. */
    private static final Map<String, List<String>> DOMINIOS = Map.of(
            "gupy", List.of("https://login.gupy.io/", "https://portal.gupy.io/"),
            "catho", List.of("https://www.catho.com.br/", "https://seguro.catho.com.br/"),
            "solides", List.of("https://auth.vagas.solides.com.br/", "https://perfil.vagas.solides.com.br/", "https://vagas.solides.com.br/"),
            "linkedin", List.of("https://www.linkedin.com/"),
            "indeed", List.of("https://secure.indeed.com/", "https://myjobs.indeed.com/", "https://br.indeed.com/"),
            "programathor", List.of("https://programathor.com.br/"));

    @Test
    void entraEmCadaPortal() throws Exception {
        Properties env = new Properties();
        try (var in = Files.newBufferedReader(Path.of(".env"))) {
            env.load(in);
        }
        Path capturas = Path.of(System.getProperty("capturas", "target/login-ao-vivo"));
        Files.createDirectories(capturas);
        NavegadorFirefox nav = new NavegadorFirefox();
        try {
            for (String p : System.getProperty("portais").split(",")) {
                String portal = p.trim();
                PortalDriver driver = criar(portal, nav);
                WebDriver d = nav.driver();
                log(portal, "logado antes: " + driver.logado() + " · " + d.getCurrentUrl());
                if ("true".equals(System.getProperty("conferir"))) continue; // só confere a sessão
                if (!"false".equals(System.getProperty("sair"))) {
                    for (String url : DOMINIOS.get(portal)) {
                        d.get(url);
                        d.manage().deleteAllCookies();
                        ((JavascriptExecutor) d).executeScript("try{localStorage.clear();sessionStorage.clear();}catch(e){}");
                    }
                    log(portal, "logado depois de sair: " + driver.logado());
                    if ("true".equals(System.getProperty("soSair"))) continue; // só desloga
                }
                log(portal, "abriu formulário: " + driver.abrirFormularioLogin() + " · " + driver.diagnostico());
                capturar(d, capturas.resolve(portal + "-1-formulario.png"));
                boolean enviou = driver.entrar(env.getProperty("LOGIN_EMAIL"), env.getProperty("LOGIN_SENHA"));
                log(portal, "enviou: " + enviou + " · captcha: " + driver.pedeCaptcha() + " · " + driver.diagnostico());
                capturar(d, capturas.resolve(portal + "-2-depois-do-envio.png"));
                Boolean logado = driver.logado();
                log(portal, "logado no fim: " + logado);
                if (!Boolean.TRUE.equals(logado)) { // como o Orquestrador: e-mail/senha não entrou → "Entrar com LinkedIn"
                    log(portal, "clicou em Entrar com LinkedIn: " + driver.abrirLogin() + " · " + driver.diagnostico());
                    capturar(d, capturas.resolve(portal + "-4-linkedin.png"));
                    logado = driver.logado();
                    log(portal, "logado pelo LinkedIn: " + logado + " · celular: " + driver.pedeCelular());
                    if (!Boolean.TRUE.equals(logado)) {
                        driver.abrirFormularioLogin();
                        log(portal, "botões sociais: " + ((JavascriptExecutor) d).executeScript(
                                "return [...document.querySelectorAll('button,a,[role=button]')].filter(e=>/google/i.test(e.outerHTML))"
                                        + ".map(e=>e.outerHTML.slice(0,300)).join(' ### ')"));
                        log(portal, "clicou em Entrar com Google: " + driver.entrarComGoogle(env.getProperty("LOGIN_EMAIL"))
                                + " · " + driver.diagnostico());
                        capturar(d, capturas.resolve(portal + "-5-google.png"));
                        log(portal, "celular: " + driver.pedeCelular() + " · logado pelo Google: " + driver.logado());
                    }
                }
                capturar(d, capturas.resolve(portal + "-3-fim.png"));
            }
        } finally {
            if (!"true".equals(System.getProperty("manter"))) nav.fechar();
        }
    }

    private static PortalDriver criar(String portal, NavegadorFirefox nav) {
        return switch (portal) {
            case "gupy" -> new GupyDriver(nav, null);
            case "catho" -> new CathoDriver(nav, null);
            case "solides" -> new SolidesDriver(nav, null);
            case "linkedin" -> new LinkedInDriver(nav, null);
            case "indeed" -> new IndeedDriver(nav, null, 14);
            case "programathor" -> new ProgramaThorDriver(nav, null);
            default -> throw new IllegalArgumentException("portal desconhecido: " + portal);
        };
    }

    private static void capturar(WebDriver d, Path arquivo) {
        try {
            Files.write(arquivo, ((TakesScreenshot) d).getScreenshotAs(OutputType.BYTES));
        } catch (Exception e) {
            log("captura", arquivo.getFileName() + " falhou: " + e.getMessage());
        }
    }

    private static void log(String portal, String linha) {
        System.out.println("LOGIN-AO-VIVO [" + portal + "] " + linha);
    }
}
