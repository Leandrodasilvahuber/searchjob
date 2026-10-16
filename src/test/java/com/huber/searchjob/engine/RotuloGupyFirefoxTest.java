package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

/** Rótulo do campo no Gupy: pergunta atrás da dica, label curto preservado e sem pegar a pergunta de outro campo. */
class RotuloGupyFirefoxTest {
    private static String pagina(String html) {
        return "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    void rotulos() {
        NavegadorFirefox nav = new NavegadorFirefox();
        try {
            WebDriver d;
            try {
                d = nav.driver();
            } catch (IllegalStateException e) {
                Assumptions.abort("Firefox indisponível: " + e.getMessage());
                return;
            }
            d.get(pagina("<form><h2>Dados</h2>"
                    + "<div><div>Conte sobre sua experiência com filas</div><div><span>Máx. 1000 caracteres</span><textarea id=t></textarea></div></div>"
                    + "<label for=u>UF</label><input id=u>"
                    + "<div><label for=n>Nome completo</label><input id=n></div>"
                    + "<div><input id=x><input id=y></div></form>"));
            assertEquals("Conte sobre sua experiência com filas", GupyDriver.rotulo(d, d.findElement(By.id("t"))));
            assertEquals("UF", GupyDriver.rotulo(d, d.findElement(By.id("u"))));
            assertEquals("Nome completo", GupyDriver.rotulo(d, d.findElement(By.id("n"))));
            String semPergunta = GupyDriver.rotulo(d, d.findElement(By.id("x")));
            org.junit.jupiter.api.Assertions.assertFalse(semPergunta.contains("filas"), "não pode pegar a pergunta de outro campo: " + semPergunta);
        } finally {
            nav.fechar();
        }
    }
}
