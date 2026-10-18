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
              if(!visivel(r))return; // duplicatas ocultas (mesmo name) não entram no grupo
              var chave=r.name;
              if(!chave){var c=r.closest('fieldset,[role=radiogroup]');
                if(c){if(!c.getAttribute('data-sj-c'))c.setAttribute('data-sj-c','c'+(sem++));chave='c:'+c.getAttribute('data-sj-c');}
                else chave='solto';}
              if(!grupos[chave]){grupos[chave]=[];ordem.push(chave);}
              grupos[chave].push(r);});
            ordem.forEach(function(chave){
              var g=grupos[chave];
              if(g.some(function(x){return x.checked;}))return;
              g.forEach(function(x){x.setAttribute('data-sj',chave);});
              var r=g[0];
              var opts=g.map(function(x){
                var lb=(x.labels&&x.labels[0])||(x.id?document.querySelector('label[for="'+x.id+'"]'):null);
                var l=lb?(lb.innerText||lb.textContent):'';
                var rr=x.closest('[role=radio]'); // radio customizado: o texto fica ao lado do input, dentro do papel radio
                if(!(l||'').trim()&&rr)l=rr.innerText||rr.textContent;
                if(!(l||'').trim())l=x.getAttribute('aria-label')||'';
                if(!(l||'').trim()&&x.parentElement)l=x.parentElement.innerText||x.parentElement.textContent;
                l=(l||'').trim();
                return (l&&l!=='on'?l:(x.value&&x.value!=='on'?x.value:l||'')).trim();});
              var rr0=r.closest('[role=radio]');
              var qb=rr0?(rr0.getAttribute('aria-label')||''):'';
              if(qb&&opts.indexOf(qb.trim())>=0)qb='';
              if(opts.some(function(o){return !o;})){ // rótulo não associado ao input: lê as opções das últimas linhas do bloco
                var b=r.parentElement;
                for(var j=0;j<8&&b&&!g.every(function(x){return b.contains(x);});j++)b=b.parentElement;
                var ls=b?b.innerText.split('\\n').map(function(s){return s.trim();}).filter(function(s){return s;}):[];
                if(ls.length>g.length){opts=ls.slice(ls.length-g.length);ls=ls.slice(0,ls.length-g.length);}
                if(!qb)qb=ls.length?ls[ls.length-1]:'';}
              var q=qb;
              var fs=r.closest('fieldset'); if(!q&&fs&&fs.querySelector('legend')) q=fs.querySelector('legend').innerText;
              if(!q){var a=r.parentElement;
                for(var i=0;i<8&&a;i++){
                  if(g.every(function(x){return a.contains(x);})){
                    var linhas=a.innerText.split('\\n').map(function(s){return s.trim();}).filter(function(s){return s&&opts.indexOf(s)<0;});
                    if(linhas.length){q=linhas[0];break;}
                  }
                  a=a.parentElement;}}
              var html='';
              if(opts.some(function(o){return !o;})){var h=r.parentElement;
                for(var k=0;k<4&&h&&h.parentElement;k++)h=h.parentElement;
                html=h?h.outerHTML.substring(0,6000):'';}
              out.push({name:chave,pergunta:(q||'').trim(),opcoes:opts,html:html});});
            return JSON.stringify(out);
            """;

    private Radios() {}

    /** Responde os grupos de rádio sem marcação. Depois dos rádios, responde os selects. Devolve null se tudo foi respondido. */
    static Desfecho preencher(WebDriver d, SituacaoService situacoes, String portal, String contexto, Cv cv,
                              BooleanSupplier continuar) {
        return preencher(d, situacoes, portal, contexto, cv, continuar, null);
    }

    /** @param vistas perguntas já feitas nesta vaga (null = sem controle): a mesma pergunta duas vezes é formulário que reinicia */
    static Desfecho preencher(WebDriver d, SituacaoService situacoes, String portal, String contexto, Cv cv,
                              BooleanSupplier continuar, java.util.Set<String> vistas) {
        List<Grupo> grupos = ler(d);
        for (Grupo g : grupos) {
            if (g.pergunta.isBlank())
                return new Desfecho(Status.PRECISA_USUARIO, "pergunta de múltipla escolha ilegível (opções: " + g.opcoes + ")");
            List<String> ordenadas = new ArrayList<>(g.opcoes);
            java.util.Collections.sort(ordenadas); // a ordem das opções muda entre vagas; a pergunta salva tem que ser a mesma
            if (Texto.perguntaEmIngles(g.pergunta)) return new Desfecho(Status.PULADA, "vaga em inglês: " + g.pergunta);
            String chave = g.pergunta + " [opções: " + String.join(" | ", ordenadas) + "]";
            if (vistas != null && !vistas.add(chave))
                return new Desfecho(Status.PULADA, "formulário reinicia e repete a pergunta: " + g.pergunta);
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
                    if (!marcar(d, g.name, i) && !remarcar(d, g, i))
                        return new Desfecho(Status.ERRO, "não consegui marcar \"" + g.opcoes.get(i) + "\" em: " + g.pergunta);
                }
            }
        }
        return Checkboxes.preencher(d, situacoes, portal, contexto, cv, continuar);
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

    /**
     * Marca a opção e confere: clique em JS no input, depois clique nativo no rótulo/input (radio estilizado ignora o clique sintético).
     * O React pode recriar o input após o clique, então o elemento é localizado de novo a cada conferência.
     */
    private static boolean marcar(WebDriver d, String name, int indice) {
        JavascriptExecutor js = (JavascriptExecutor) d;
        WebElement alvo = achar(d, name, indice);
        if (alvo == null) return false;
        js.executeScript("arguments[0].scrollIntoView({block:'center'});arguments[0].click()", alvo);
        if (marcado(d, name, indice)) return true;
        alvo = achar(d, name, indice);
        if (alvo == null) return false;
        js.executeScript("var r=arguments[0].closest('[role=radio]');if(r)r.click()", alvo); // radio customizado: o clique é tratado no papel radio
        if (marcado(d, name, indice)) return true;
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            try {
                alvo = achar(d, name, indice);
                if (alvo == null) return false;
                WebElement el = tentativa == 0
                        ? (WebElement) js.executeScript("return arguments[0].closest('[role=radio]')||(arguments[0].labels&&arguments[0].labels[0])||arguments[0].closest('label')||arguments[0].parentElement", alvo)
                        : alvo;
                el.click();
            } catch (org.openqa.selenium.WebDriverException ex) {
                continue; // oculto, coberto ou recriado: tenta o próximo
            }
            if (marcado(d, name, indice)) return true;
        }
        alvo = achar(d, name, indice);
        if (alvo == null) return false;
        js.executeScript("var l=arguments[0].labels&&arguments[0].labels[0];(l||arguments[0].parentElement).click()", alvo);
        return marcado(d, name, indice);
    }

    /**
     * Marcar a pergunta anterior fez o formulário redesenhar (LinkedIn) e os rádios desta perderam o carimbo data-sj:
     * relê os grupos, acha o desta pergunta pelo texto e marca a mesma opção pelo texto.
     */
    private static boolean remarcar(WebDriver d, Grupo antigo, int indice) {
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            pausaCurta();
            boolean pendente = false;
            for (Grupo g : ler(d)) { // ler só traz grupos sem opção marcada
                if (!g.pergunta.equals(antigo.pergunta)) continue;
                pendente = true;
                int i = g.opcoes.indexOf(antigo.opcoes.get(indice));
                if (i >= 0 && marcar(d, g.name, i)) return true;
            }
            if (!pendente) return true; // o clique anterior valeu: o grupo já aparece marcado
        }
        return false;
    }

    private static void pausaCurta() {
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static WebElement achar(WebDriver d, String name, int indice) {
        List<WebElement> g = new ArrayList<>();
        for (WebElement r : d.findElements(By.cssSelector("input[type=radio][data-sj]"))) {
            try {
                if (name.equals(r.getAttribute("data-sj"))) g.add(r);
            } catch (org.openqa.selenium.StaleElementReferenceException e) {
                // recriado durante a leitura: ignora, a próxima busca pega o novo
            }
        }
        if (g.isEmpty() && !name.startsWith("c:") && !name.equals("solto")) // o React recriou os inputs e perdeu o carimbo: acha pelo name
            g.addAll(d.findElements(By.cssSelector("input[type=radio][name=\"" + name.replace("\"", "\\\"") + "\"]")));
        return indice < g.size() ? g.get(indice) : null;
    }

    private static boolean marcado(WebDriver d, String name, int indice) {
        for (int i = 0; i < 3; i++) {
            try {
                WebElement e = achar(d, name, indice);
                return e != null && (e.isSelected() || Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                        "var r=arguments[0].closest('[role=radio]');return !!r&&r.getAttribute('aria-checked')==='true'", e)));
            } catch (org.openqa.selenium.StaleElementReferenceException ex) {
                // tenta de novo com o elemento novo
            }
        }
        return false;
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
                String html = n.path("html").asText();
                if (!html.isEmpty()) java.nio.file.Files.writeString(java.nio.file.Path.of("data/erros/radio-" + System.currentTimeMillis() + ".html"), html);
            }
        } catch (java.io.IOException | org.openqa.selenium.WebDriverException e) {
            // página sem rádios legíveis: segue o fluxo normal
        }
        return out;
    }

    private record Grupo(String name, String pergunta, List<String> opcoes) {}
}
