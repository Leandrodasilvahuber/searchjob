package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.Select;

/** Select no valor de espera é detectado; select já escolhido é ignorado; a opção escolhida é marcada. */
class SelectsFirefoxTest {
    @Test
    void espera() {
        assertTrue(Selects.ehEspera("", "Selecione"));
        assertTrue(Selects.ehEspera("0", "Selecione uma opção"));
        assertFalse(Selects.ehEspera("sp", "São Paulo"));
    }

    @Test
    void escolhePelaResposta() {
        NavegadorFirefox nav = new NavegadorFirefox();
        try {
            WebDriver d;
            try {
                d = nav.driver();
            } catch (IllegalStateException e) {
                Assumptions.abort("Firefox indisponível: " + e.getMessage());
                return;
            }
            d.get("data:text/html;charset=utf-8,<div>Possui CNH?<select id=a><option value=''>Selecione</option>"
                    + "<option value=s>Sim</option><option value=n>Não</option></select></div>");
            var opcoes = new Select(d.findElement(By.id("a"))).getOptions().stream().map(o -> o.getText()).toList();
            assertEquals(3, opcoes.size());
            assertEquals(1, Radios.casar("Sim", opcoes.subList(1, 3)) + 1);
        } finally {
            nav.fechar();
        }
    }

    @Test
    void preencheSelectComARespostaDoServico() {
        NavegadorFirefox nav = new NavegadorFirefox();
        try {
            WebDriver d;
            try {
                d = nav.driver();
            } catch (IllegalStateException e) {
                Assumptions.abort("Firefox indisponível: " + e.getMessage());
                return;
            }
            d.get("data:text/html;charset=utf-8,<div>Possui CNH?<select id=a><option value=''>Selecione</option>"
                    + "<option value=s>Sim</option><option value=n>Não</option></select></div>"
                    + "<div>UF<select id=b><option value=sp selected>SP</option><option value=rj>RJ</option></select></div>");
            SituacaoService svc = org.mockito.Mockito.mock(SituacaoService.class);
            org.mockito.Mockito.when(svc.resolver(org.mockito.ArgumentMatchers.eq("Gupy"), org.mockito.ArgumentMatchers.contains("CNH"),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new SituacaoService.Resultado(SituacaoService.Tipo.APLICADA, "Não", 1));
            assertEquals(null, Selects.preencher(d, svc, "Gupy", "", null, () -> true));
            assertEquals("n", d.findElement(By.id("a")).getAttribute("value"));
            assertEquals("sp", d.findElement(By.id("b")).getAttribute("value")); // já escolhido: intocado
        } finally {
            nav.fechar();
        }
    }
}
