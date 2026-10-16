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
        enviar(d, campoSenha);
        dormir(4000);
        return true;
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

    /** True quando a página (depois de enviar o login) mostra CAPTCHA/reCAPTCHA. */
    public static boolean pedeCaptcha(WebDriver d) {
        try {
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
