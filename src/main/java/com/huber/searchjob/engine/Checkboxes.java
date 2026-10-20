package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.engine.PortalDriver.Desfecho;
import com.huber.searchjob.engine.PortalDriver.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Perguntas obrigatórias de caixas de seleção (2+ input[type=checkbox] sob a mesma pergunta, nenhuma marcada).
 * Mesmo fluxo dos rádios, mas a resposta pode citar várias opções separadas por "|", ";" ou quebra de linha.
 * Caixa avulsa obrigatória (aceite de termos ou consentimento, ex.: "Yes" do LinkedIn) é marcada direto; grupos opcionais não entram aqui.
 */
final class Checkboxes {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String MARCADOR = "(múltipla escolha: pode marcar várias; responda com as opções exatas separadas por \" | \") ";

    private static final String LER_GRUPOS = """
            var out=[],n=0;
            function visivel(x){var l=(x.labels&&x.labels[0])||x;return x.getClientRects().length>0||l.getClientRects().length>0;}
            function rot(x){ // com dois rótulos para o mesmo input (caixa vazia + texto), vale o que tem texto
              var l=Array.prototype.filter.call(x.labels||[],function(e){return (e.innerText||'').trim();})[0]||(x.labels&&x.labels[0]);
              return (l?l.innerText:(x.parentElement?x.parentElement.innerText:x.value)||'').trim();}
            var vistos=[];
            document.querySelectorAll('input[type=checkbox]').forEach(function(c){
              if(vistos.indexOf(c)>=0||!visivel(c))return;
              var a=c.parentElement,g=null;
              for(var i=0;i<8&&a;i++){
                var cs=Array.prototype.filter.call(a.querySelectorAll('input[type=checkbox]'),visivel);
                if(cs.length>=2){
                  var opts=cs.map(rot);
                  var linhas=a.innerText.split('\\n').map(function(s){return s.trim();}).filter(function(s){return s&&opts.indexOf(s)<0;});
                  if(linhas.length){g={cs:cs,opts:opts,q:linhas[0],txt:a.innerText};break;}
                }
                a=a.parentElement;}
              if(!g)return;
              g.cs.forEach(function(x){vistos.push(x);});
              if(g.cs.some(function(x){return x.checked;}))return;
              if(!/\\*|obrigat/i.test(g.txt))return;
              var id='k'+(n++);
              g.cs.forEach(function(x){x.setAttribute('data-sjk',id);});
              out.push({name:id,pergunta:g.q.replace(/\\s*\\*\\s*$/,'').trim(),opcoes:g.opts});});
            return JSON.stringify(out);
            """;

    /** Caixa obrigatória sozinha sob a pergunta (aceite/consentimento) e desmarcada: marca, pelo rótulo se for estilizada. */
    private static final String MARCAR_AVULSAS = """
            function visivel(x){var l=(x.labels&&x.labels[0])||x;return x.getClientRects().length>0||l.getClientRects().length>0;}
            var textos=[];
            document.querySelectorAll('input[type=checkbox]').forEach(function(c){
              if(c.checked||c.disabled||!visivel(c))return;
              var a=c.parentElement,txt='';
              for(var i=0;i<6&&a;i++){
                if(a.querySelectorAll('input[type=checkbox]').length>1)return;
                if(a.querySelectorAll('input,textarea,select').length>1)break;
                txt=a.innerText||'';a=a.parentElement;}
              if(!c.required&&c.getAttribute('aria-required')!=='true'&&!/\\*|obrigat|required/i.test(txt))return;
              textos.push(txt);if(!arguments[0])return;
              c.scrollIntoView({block:'center'});c.click();
              if(!c.checked){var l=c.labels&&c.labels[0];(l||c.parentElement).click();}});
            return textos;
            """;

    private Checkboxes() {}

    /** Responde os grupos de caixas obrigatórios sem marcação; depois segue para os selects. Devolve null se tudo foi respondido. */
    static Desfecho preencher(WebDriver d, SituacaoService situacoes, String portal, String contexto, Cv cv,
                              BooleanSupplier continuar) {
        for (Grupo g : ler(d)) {
            if (g.pergunta.isBlank())
                return new Desfecho(Status.PRECISA_USUARIO, "pergunta de caixas de seleção ilegível (opções: " + g.opcoes + ")");
            List<String> ordenadas = new ArrayList<>(g.opcoes);
            java.util.Collections.sort(ordenadas);
            if (Texto.perguntaEmIngles(g.pergunta)) return new Desfecho(Status.PULADA, "vaga em inglês: " + g.pergunta);
            String chave = MARCADOR + g.pergunta + " [opções: " + String.join(" | ", ordenadas) + "]";
            var res = situacoes.resolver(portal, chave, contexto, cv, false, continuar);
            switch (res.tipo()) {
                case SENSIVEL -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "dado sensível: " + g.pergunta);
                }
                case SEM_RESPOSTA -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "sem resposta para: " + chave);
                }
                default -> {
                    TreeSet<Integer> marcar = casarVarias(res.resposta(), g.opcoes);
                    if (marcar.isEmpty())
                        return new Desfecho(Status.PRECISA_USUARIO, "a resposta \"" + res.resposta()
                                + "\" não corresponde a nenhuma opção de: " + chave);
                    marcar(d, g.name, marcar);
                }
            }
        }
        try {
            // aceite em inglês ("has my consent to...") é de empresa estrangeira: pula em vez de marcar
            Object textos = ((JavascriptExecutor) d).executeScript(MARCAR_AVULSAS, false);
            if (textos instanceof List<?> l)
                for (Object t : l)
                    if (Texto.perguntaEmIngles(String.valueOf(t))) return new Desfecho(Status.PULADA, "vaga em inglês: " + t);
            ((JavascriptExecutor) d).executeScript(MARCAR_AVULSAS, true);
        } catch (org.openqa.selenium.WebDriverException e) {
            // página mudou: o laço principal reavalia
        }
        return Selects.preencher(d, situacoes, portal, contexto, cv, continuar);
    }

    /** Índices das opções citadas na resposta (partes separadas por "|", ";" ou quebra de linha). */
    static TreeSet<Integer> casarVarias(String resposta, List<String> opcoes) {
        TreeSet<Integer> out = new TreeSet<>();
        if (resposta == null) return out;
        for (String parte : resposta.split("[|;\\n]")) {
            int i = Radios.casar(parte, opcoes);
            if (i >= 0) out.add(i);
        }
        return out;
    }

    private static void marcar(WebDriver d, String name, TreeSet<Integer> indices) {
        List<WebElement> g = new ArrayList<>();
        for (WebElement c : d.findElements(By.cssSelector("input[type=checkbox]")))
            if (name.equals(c.getAttribute("data-sjk"))) g.add(c);
        JavascriptExecutor js = (JavascriptExecutor) d;
        for (int i : indices) {
            WebElement alvo = g.get(i);
            js.executeScript("arguments[0].scrollIntoView({block:'center'});arguments[0].click()", alvo);
            if (!alvo.isSelected()) // caixa estilizada: o clique vale no rótulo
                js.executeScript("var l=arguments[0].labels&&arguments[0].labels[0];(l||arguments[0].parentElement).click()", alvo);
        }
    }

    private static List<Grupo> ler(WebDriver d) {
        List<Grupo> out = new ArrayList<>();
        try {
            Object o = ((JavascriptExecutor) d).executeScript(LER_GRUPOS);
            if (o == null) return out;
            for (JsonNode n : JSON.readTree(o.toString())) {
                List<String> ops = new ArrayList<>();
                n.path("opcoes").forEach(x -> ops.add(x.asText()));
                out.add(new Grupo(n.path("name").asText(), n.path("pergunta").asText(), ops));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | org.openqa.selenium.WebDriverException e) {
            // página sem caixas legíveis: segue o fluxo normal
        }
        return out;
    }

    private record Grupo(String name, String pergunta, List<String> opcoes) {}
}
