package com.huber.searchjob.engine;

import com.huber.searchjob.db.Cv;
import com.huber.searchjob.engine.PortalDriver.Desfecho;
import com.huber.searchjob.engine.PortalDriver.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

/**
 * Listas suspensas (select) ainda no valor de espera ("Selecione…"). Mesmo fluxo dos rádios: a pergunta vai ao
 * SituacaoService com as opções anexadas e a resposta é casada com uma das opções.
 */
final class Selects {
    private static final Pattern ESPERA = Pattern.compile("^\\s*(selecion|escolh|select|choose|--|—|\\.\\.\\.|por favor)", Pattern.CASE_INSENSITIVE);

    private Selects() {}

    static boolean ehEspera(String valor, String texto) {
        return (valor == null || valor.isBlank()) || ESPERA.matcher(texto == null ? "" : texto).find();
    }

    /** Responde os selects sem escolha. Devolve null se tudo foi respondido (ou não há selects). */
    static Desfecho preencher(WebDriver d, SituacaoService situacoes, String portal, String contexto, Cv cv,
                              BooleanSupplier continuar) {
        for (WebElement el : d.findElements(By.tagName("select"))) {
            if (!el.isDisplayed() || !el.isEnabled()) continue;
            Select s = new Select(el);
            if (s.isMultiple() || s.getOptions().isEmpty()) continue;
            WebElement atual = s.getFirstSelectedOption();
            if (!ehEspera(atual.getAttribute("value"), atual.getText())) continue;
            List<WebElement> validas = new ArrayList<>();
            for (WebElement o : s.getOptions()) if (!ehEspera(o.getAttribute("value"), o.getText())) validas.add(o);
            if (validas.isEmpty()) continue;
            String pergunta = GupyDriver.rotulo(d, el);
            List<String> opcoes = validas.stream().map(o -> o.getText().trim()).toList();
            if (pergunta.isBlank())
                return new Desfecho(Status.PRECISA_USUARIO, "lista de seleção ilegível (opções: " + opcoes + ")");
            List<String> ordenadas = new ArrayList<>(opcoes);
            java.util.Collections.sort(ordenadas); // a ordem muda entre vagas; a pergunta salva tem que ser a mesma
            String chave = pergunta + " [opções: " + String.join(" | ", ordenadas) + "]";
            var res = situacoes.resolver(portal, chave, contexto, cv, false, continuar);
            switch (res.tipo()) {
                case SENSIVEL -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "dado sensível: " + pergunta);
                }
                case SEM_RESPOSTA -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "sem resposta para: " + chave);
                }
                default -> {
                    int i = Radios.casar(res.resposta(), opcoes);
                    if (i < 0)
                        return new Desfecho(Status.PRECISA_USUARIO, "a resposta \"" + res.resposta()
                                + "\" não corresponde a nenhuma opção de: " + chave);
                    ((JavascriptExecutor) d).executeScript("arguments[0].scrollIntoView({block:'center'})", el);
                    s.selectByVisibleText(validas.get(i).getText());
                }
            }
        }
        return null;
    }
}
