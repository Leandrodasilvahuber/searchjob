package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.engine.PortalDriver.Desfecho;
import com.huber.searchjob.engine.PortalDriver.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Perguntas de múltipla escolha (grupos de input[type=radio] sem nenhuma opção marcada).
 * A pergunta vai para o SituacaoService com as opções anexadas: resposta salva → IA com o CV → chama o usuário.
 * A resposta (texto livre) é casada com uma das opções; sem casamento, devolve PRECISA_USUARIO.
 */
final class Radios {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Agrupa por name (ou, sem name, pelo fieldset/radiogroup mais próximo), ignora grupos invisíveis (cookies, modais
     * fechados) e carimba data-sj em cada rádio do grupo para o Java achá-lo depois.
     */
    private static final String LER_GRUPOS = """
            var out=[],grupos={},ordem=[],sem=0;
            function visivel(x){var l=(x.labels&&x.labels[0])||x;return x.getClientRects().length>0||l.getClientRects().length>0;}
            document.querySelectorAll('input[type=radio]').forEach(function(r){
              var chave=r.name;
              if(!chave){var c=r.closest('fieldset,[role=radiogroup]');
                if(c){if(!c.getAttribute('data-sj-c'))c.setAttribute('data-sj-c','c'+(sem++));chave='c:'+c.getAttribute('data-sj-c');}
                else chave='solto';}
              if(!grupos[chave]){grupos[chave]=[];ordem.push(chave);}
              grupos[chave].push(r);});
            ordem.forEach(function(chave){
              var g=grupos[chave];
              if(g.some(function(x){return x.checked;}))return;
              if(!g.some(visivel))return;
              g.forEach(function(x){x.setAttribute('data-sj',chave);});
              var r=g[0];
              var opts=g.map(function(x){
                var l=(x.labels&&x.labels[0])?x.labels[0].innerText:(x.parentElement?x.parentElement.innerText:x.value);
                return (l||x.value||'').trim();});
              var q='';
              var fs=r.closest('fieldset'); if(fs&&fs.querySelector('legend')) q=fs.querySelector('legend').innerText;
              if(!q){var a=r.parentElement;
                for(var i=0;i<8&&a;i++){
                  if(g.every(function(x){return a.contains(x);})){
                    var linhas=a.innerText.split('\\n').map(function(s){return s.trim();}).filter(function(s){return s&&opts.indexOf(s)<0;});
                    if(linhas.length){q=linhas[0];break;}
                  }
                  a=a.parentElement;}}
              out.push({name:chave,pergunta:(q||'').trim(),opcoes:opts});});
            return JSON.stringify(out);
            """;

    private Radios() {}

    /** Responde os grupos de rádio sem marcação. Depois dos rádios, responde os selects. Devolve null se tudo foi respondido. */
    static Desfecho preencher(WebDriver d, SituacaoService situacoes, String portal, String contexto, Cv cv,
                              BooleanSupplier continuar) {
        List<Grupo> grupos = ler(d);
        for (Grupo g : grupos) {
            if (g.pergunta.isBlank())
                return new Desfecho(Status.PRECISA_USUARIO, "pergunta de múltipla escolha ilegível (opções: " + g.opcoes + ")");
            List<String> ordenadas = new ArrayList<>(g.opcoes);
            java.util.Collections.sort(ordenadas); // a ordem das opções muda entre vagas; a pergunta salva tem que ser a mesma
            String chave = g.pergunta + " [opções: " + String.join(" | ", ordenadas) + "]";
            var res = situacoes.resolver(portal, chave, contexto, cv, false, continuar);
            switch (res.tipo()) {
                case SENSIVEL -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "dado sensível: " + g.pergunta);
                }
                case SEM_RESPOSTA -> {
                    return new Desfecho(Status.PRECISA_USUARIO, "sem resposta para: " + chave);
                }
                default -> {
                    int i = casar(res.resposta(), g.opcoes);
                    if (i < 0)
                        return new Desfecho(Status.PRECISA_USUARIO, "a resposta \"" + res.resposta()
                                + "\" não corresponde a nenhuma opção de: " + chave);
                    marcar(d, g.name, i);
                }
            }
        }
        return Selects.preencher(d, situacoes, portal, contexto, cv, continuar);
    }

    /** Índice da opção que corresponde à resposta (igual, depois contida, depois sim/não), ou -1. */
    static int casar(String resposta, List<String> opcoes) {
        if (resposta == null) return -1;
        String r = Texto.normalizar(resposta).trim();
        if (r.isEmpty()) return -1;
        for (int i = 0; i < opcoes.size(); i++) if (Texto.normalizar(opcoes.get(i)).trim().equals(r)) return i;
        int melhor = -1;
        int pontos = -1;
        for (int i = 0; i < opcoes.size(); i++) {
            String o = Texto.normalizar(opcoes.get(i)).trim();
            if (o.isEmpty()) continue;
            // opção citada na resposta vale mais quanto mais específica (maior); resposta dentro da opção vale mais quanto mais curta a opção
            int p = r.contains(o) ? 1000 + o.length() : o.contains(r) ? 1000 - o.length() : -1;
            if (p > pontos) {
                pontos = p;
                melhor = i;
            }
        }
        if (melhor >= 0) return melhor;
        String p = r.split("[\\s,.;:!-]+")[0];
        for (int i = 0; i < opcoes.size(); i++) {
            String o = Texto.normalizar(opcoes.get(i)).trim();
            if (!p.isEmpty() && (o.equals(p) || o.startsWith(p + " "))) return i;
        }
        return -1;
    }

    private static void marcar(WebDriver d, String name, int indice) {
        List<WebElement> g = new ArrayList<>();
        for (WebElement r : d.findElements(By.cssSelector("input[type=radio]")))
            if (name.equals(r.getAttribute("data-sj"))) g.add(r);
        WebElement alvo = g.get(indice);
        JavascriptExecutor js = (JavascriptExecutor) d;
        js.executeScript("arguments[0].scrollIntoView({block:'center'});arguments[0].click()", alvo);
        if (!alvo.isSelected()) // radio estilizado: o clique vale no rótulo
            js.executeScript("var l=arguments[0].labels&&arguments[0].labels[0];(l||arguments[0].parentElement).click()", alvo);
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
            // página sem rádios legíveis: segue o fluxo normal
        }
        return out;
    }

    private record Grupo(String name, String pergunta, List<String> opcoes) {}
}
