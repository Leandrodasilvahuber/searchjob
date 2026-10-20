package com.huber.searchjob.engine;

import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Preenche o formulário de login que está aberto no Firefox do robô com o login salvo no cofre.
 * Cobre o formato comum (usuário + senha na mesma página) e o de duas etapas (usuário → continuar → senha).
 * Não resolve CAPTCHA nem verificação em duas etapas: nesses casos o login não entra e o robô chama você.
 */
public final class LoginAutomatico {
    private static final String CAMPO_USUARIO = "input[type=email], input[autocomplete=username], input[name*=mail i], "
            + "input[name*=user i], input[name*=login i], input[id*=mail i], input[id*=user i], input[id*=login i], input[type=text]";
    private static final String[] BOTOES_ENVIO = {"Entrar", "Acessar", "Fazer login", "Login", "Continuar", "Avançar", "Próximo", "Sign in", "Log in"};

    private LoginAutomatico() {}

    /** Devolve true se preencheu e enviou o formulário (não garante que o portal aceitou a senha). */
    public static boolean entrar(WebDriver d, String usuario, String senha) {
        WebElement campoUsuario = visivel(d, CAMPO_USUARIO);
        WebElement campoSenha = visivel(d, "input[type=password]");
        if (campoUsuario == null && campoSenha == null) return false;
        if (campoUsuario != null) digitar(campoUsuario, usuario);
        if (campoSenha == null) { // duas etapas: avança e espera a senha aparecer
            enviar(d, campoUsuario);
            for (int i = 0; i < 20 && campoSenha == null; i++) {
                dormir(500);
                campoSenha = visivel(d, "input[type=password]");
            }
            if (campoSenha == null) return false;
        }
        digitar(campoSenha, senha);
        lembrar(d);
        String url = d.getCurrentUrl();
        enviar(d, campoSenha);
        aguardarResposta(d, url);
        return true;
    }

    /**
     * Espera o portal responder ao envio (até 20 s): sai quando a página troca, o campo de senha some ou aparece a
     * mensagem de senha inválida. Uma espera fixa curta faz a conferência do login navegar e cortar o login em andamento (Gupy).
     */
    private static void aguardarResposta(WebDriver d, String url) {
        dormir(2000);
        for (int i = 0; i < 18; i++) {
            try {
                if (!String.valueOf(d.getCurrentUrl()).equals(url) || visivel(d, "input[type=password]") == null) break;
                if (senhaRecusada(d)) return;
            } catch (org.openqa.selenium.WebDriverException e) {
                break; // página trocando no meio da leitura
            }
            dormir(1000);
        }
        dormir(2000); // a página nova ainda grava o cookie da sessão
    }

    /**
     * Alguma janela está na verificação do LinkedIn (checkpoint, "Check your LinkedIn app") ou do Google (desafio no
     * celular): só o usuário confirma no telefone.
     */
    public static boolean pedeCelular(WebDriver d) {
        String atual = null;
        try {
            atual = d.getWindowHandle();
        } catch (org.openqa.selenium.WebDriverException ignored) {
            // a janela controlada fechou
        }
        try {
            for (String h : d.getWindowHandles()) {
                d.switchTo().window(h);
                String url = String.valueOf(d.getCurrentUrl());
                if (url.contains("linkedin.com/checkpoint") || url.matches(".*accounts\\.google\\.com/.*challenge.*")) return true;
                String t = Texto.normalizar(d.findElement(By.tagName("body")).getText());
                if (t.contains("check your linkedin app") || t.contains("verifique seu app") || t.contains("confira seu celular")
                        || t.contains("check your phone") || t.contains("verifique seu telefone")) return true;
            }
            return false;
        } catch (org.openqa.selenium.WebDriverException e) {
            return false;
        } finally {
            try {
                if (atual != null) d.switchTo().window(atual);
            } catch (org.openqa.selenium.WebDriverException ignored) {
                // a janela original fechou: fica na que estiver
            }
        }
    }

    /** A tela de login mostra "usuário ou senha inválido" (ou equivalente). */
    public static boolean senhaRecusada(WebDriver d) {
        try {
            String t = Texto.normalizar(d.findElement(By.tagName("body")).getText());
            return t.contains("senha invalid") || t.contains("senha incorret") || t.contains("invalid password")
                    || t.contains("incorrect password") || t.contains("invalid credentials")
                    || t.contains("e-mail e/ou senha") || t.contains("usuario ou senha");
        } catch (org.openqa.selenium.WebDriverException e) {
            return false;
        }
    }

    /**
     * Lê o que está digitado agora nos campos de login visíveis: {usuário, senha}, com null onde o campo não existe
     * ou está vazio. Usado enquanto o usuário entra manualmente, para salvar o login no cofre.
     */
    public static String[] ler(WebDriver d) {
        try {
            return new String[] {valor(visivel(d, CAMPO_USUARIO)), valor(visivel(d, "input[type=password]"))};
        } catch (org.openqa.selenium.WebDriverException e) {
            return new String[] {null, null}; // página mudou no meio da leitura (o usuário acabou de enviar o formulário)
        }
    }

    /** URL atual e mensagem de erro/aviso visível na página (para o log depois de um login automático). */
    public static String diagnostico(WebDriver d) {
        try {
            StringBuilder b = new StringBuilder("página: ").append(d.getCurrentUrl());
            for (WebElement e : d.findElements(By.cssSelector(
                    ".alert, .flash, [role=alert], .invalid-feedback, .error, .errors, .notice, #flash_alert"))) {
                String t = e.getText().trim();
                if (e.isDisplayed() && !t.isEmpty()) b.append(" · aviso: ").append(t.length() > 160 ? t.substring(0, 160) : t);
            }
            String corpo = d.findElement(By.tagName("body")).getText().toLowerCase();
            if (corpo.contains("captcha") || corpo.contains("verify you are human") || corpo.contains("verificação"))
                b.append(" · a página pede verificação/CAPTCHA");
            return b.toString();
        } catch (org.openqa.selenium.WebDriverException e) {
            return "página indisponível após o login";
        }
    }

    private static String valor(WebElement e) {
        if (e == null) return null;
        String v = e.getAttribute("value");
        return v == null || v.isEmpty() ? null : v;
    }

    /** Marca "lembrar-me / manter conectado": sem isso o cookie de login some quando o Firefox do robô fecha. */
    private static void lembrar(WebDriver d) {
        for (WebElement c : d.findElements(By.cssSelector("input[type=checkbox]"))) {
            String id = (c.getAttribute("name") + " " + c.getAttribute("id")).toLowerCase();
            if (id.contains("remember") || id.contains("lembr") || id.contains("manter") || id.contains("keep")) {
                try {
                    if (!c.isSelected()) ((JavascriptExecutor) d).executeScript("arguments[0].click()", c);
                } catch (org.openqa.selenium.WebDriverException ignored) {
                    // checkbox opcional: segue sem marcar
                }
            }
        }
    }

    /**
     * Abre {@code url} (página da área logada) e confere se o portal mantém o navegador nela: deslogado, ele redireciona
     * para o login ou para a home. Espera até 5 s porque em SPAs (Gupy) o redirecionamento só vem depois do JS carregar.
     */
    public static Boolean ficaNaAreaLogada(WebDriver d, String url) {
        String alvo = url.replaceFirst("^https?://", "").replaceFirst("/$", "");
        try {
            d.get(url);
            for (int i = 0; i < 5; i++) {
                dormir(1000);
                String atual = String.valueOf(d.getCurrentUrl()).replaceFirst("^https?://", "");
                if (!atual.startsWith(alvo)) return false;
            }
            return true;
        } catch (org.openqa.selenium.WebDriverException e) {
            return null;
        }
    }

    /**
     * Abre a tela de login do portal e, se ela oferece "Entrar com LinkedIn", clica nisso (a sessão do LinkedIn costuma
     * estar ativa no Firefox do robô, então às vezes já entra direto). Devolve true se foi pelo LinkedIn.
     */
    public static boolean abrirLogin(WebDriver d, String url) {
        return abrirLogin(d, url, true);
    }

    /** Abre a tela de login do portal; com {@code linkedin} false fica no formulário e-mail/senha do próprio portal. */
    public static boolean abrirLogin(WebDriver d, String url, boolean viaLinkedin) {
        try {
            d.get(url);
            dormir(3000); // SPAs (Gupy, Catho) desenham os botões depois do JS
            if (!viaLinkedin || url.contains("linkedin.com")) return false; // no próprio LinkedIn qualquer logo casaria com o botão
            WebElement linkedin = botaoLinkedin(d);
            if (linkedin == null) return false;
            ((JavascriptExecutor) d).executeScript("arguments[0].click()", linkedin);
            dormir(6000);
            return true;
        } catch (org.openqa.selenium.WebDriverException e) {
            return false;
        }
    }

    /** Botão/link "Entrar com LinkedIn" visível; ignora os links de rede social do rodapé (perfil da empresa, nova aba). */
    public static WebElement botaoLinkedin(WebDriver d) {
        return botaoSocial(d, "linkedin");
    }

    /**
     * "Entrar com Google" na tela de login aberta (Catho): clica no botão, escolhe a conta {@code email} na tela do
     * Google (popup ou mesma aba) e confirma. Devolve true se clicou no botão do Google.
     */
    public static boolean entrarComGoogle(WebDriver d, String email) {
        WebElement google = botaoSocial(d, "google");
        if (google == null) return false;
        String principal = d.getWindowHandle();
        java.util.Set<String> antes = d.getWindowHandles();
        ((JavascriptExecutor) d).executeScript("arguments[0].click()", google);
        dormir(4000);
        for (String h : d.getWindowHandles())
            if (!antes.contains(h)) d.switchTo().window(h); // o Google abre num popup
        try {
            for (int passo = 0; passo < 6 && String.valueOf(d.getCurrentUrl()).contains("accounts.google.com"); passo++) {
                WebElement conta = visivel(d, "[data-identifier='" + email + "' i], [data-email='" + email + "' i]");
                // a Catho abre duas confirmações seguidas depois da conta
                WebElement botao = conta != null ? conta
                        : botaoComTexto(d, "Continuar", "Continue", "Confirmar", "Confirm", "Permitir", "Allow");
                if (botao == null) break; // pede senha/2FA: fica para você
                ((JavascriptExecutor) d).executeScript("arguments[0].click()", botao);
                dormir(3000);
            }
        } catch (org.openqa.selenium.NoSuchWindowException e) {
            // o popup fechou sozinho depois de escolher a conta
        }
        if (d.getWindowHandles().contains(principal)) d.switchTo().window(principal);
        // o portal troca o retorno do Google pela sessão ("Validating your information" na Gupy leva uns segundos)
        for (int i = 0; i < 20; i++) {
            dormir(1000);
            try {
                String url = String.valueOf(d.getCurrentUrl());
                String t = Texto.normalizar(d.findElement(By.tagName("body")).getText());
                if (!url.contains("/auth/google") && !url.contains("accounts.google.com") && !t.contains("validating your information")
                        && !t.contains("validando suas informacoes")) break;
            } catch (org.openqa.selenium.WebDriverException e) {
                // página trocando
            }
        }
        dormir(2000);
        return true;
    }

    private static WebElement botaoComTexto(WebDriver d, String... textos) {
        for (WebElement b : d.findElements(By.cssSelector("button,[role=button]"))) {
            try {
                if (!b.isDisplayed()) continue;
                for (String t : textos) if (b.getText().trim().equalsIgnoreCase(t)) return b;
            } catch (org.openqa.selenium.StaleElementReferenceException ignored) {
                // botão recriado durante a leitura
            }
        }
        return null;
    }

    /** Botão/link de login social ({@code rede} = linkedin, google) visível; ignora links do rodapé (nova aba, perfil). */
    private static WebElement botaoSocial(WebDriver d, String rede) {
        for (WebElement e : d.findElements(By.cssSelector("button,a,[role=button]"))) {
            try {
                String rotulo = (e.getText() + " " + e.getAttribute("aria-label") + " " + e.getAttribute("class")
                        + " " + e.getAttribute("title") + " " + e.getAttribute("data-testid")).toLowerCase();
                String href = String.valueOf(e.getAttribute("href")).toLowerCase();
                if (!rotulo.contains(rede) || !e.isDisplayed() || !e.isEnabled()) continue;
                if ("_blank".equals(e.getAttribute("target")) || href.matches(".*linkedin\\.com/(company|in|school)/.*")
                        || href.contains("play.google.com")) continue;
                return e;
            } catch (org.openqa.selenium.StaleElementReferenceException ignored) {
                // elemento trocado durante a leitura
            }
        }
        return null;
    }

    /** True quando a página (depois de enviar o login) mostra CAPTCHA/reCAPTCHA. */
    public static boolean pedeCaptcha(WebDriver d) {
        try {
            // Cloudflare Turnstile (Gupy) e reCAPTCHA desenham o desafio dentro de um iframe: o texto não aparece no body
            if (!d.findElements(By.cssSelector("iframe[src*='challenges.cloudflare.com'], .cf-turnstile, "
                    + "iframe[src*='recaptcha/api2/bframe'], iframe[title*='challenge' i]")).isEmpty()) return true;
            String t = d.findElement(By.tagName("body")).getText().toLowerCase();
            return t.contains("captcha") || t.contains("verify you are human") || t.contains("não sou um robô");
        } catch (org.openqa.selenium.WebDriverException e) {
            return false;
        }
    }

    private static void digitar(WebElement campo, String texto) {
        campo.click();
        campo.clear();
        campo.sendKeys(texto);
    }

    private static void enviar(WebDriver d, WebElement campo) {
        WebElement botao = visivel(d, "button[type=submit], input[type=submit]");
        if (botao == null) {
            for (WebElement b : d.findElements(By.cssSelector("button, [role=button]"))) {
                String t = b.getText().trim();
                if (!b.isDisplayed() || !b.isEnabled()) continue;
                for (String alvo : BOTOES_ENVIO) {
                    if (t.equalsIgnoreCase(alvo)) {
                        botao = b;
                        break;
                    }
                }
                if (botao != null) break;
            }
        }
        if (botao != null) ((JavascriptExecutor) d).executeScript("arguments[0].click()", botao);
        else campo.sendKeys(Keys.ENTER);
    }

    private static WebElement visivel(WebDriver d, String css) {
        List<WebElement> todos = d.findElements(By.cssSelector(css));
        for (WebElement e : todos) if (e.isDisplayed() && e.isEnabled()) return e;
        return null;
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
