package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.db.CadastroRepo;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.engine.PortalDriver.Desfecho;
import com.huber.searchjob.engine.PortalDriver.Status;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Candidatura no site da empresa (vaga externa no Full search): cada site tem um formulário diferente, então a IA decide
 * o passo. A cada passo o agente lê a página (texto e elementos numerados, inclusive dentro de iframes), pede à IA as
 * ações em JSON e as executa: preencher, selecionar, clicar, anexar o CV, enviar. Conta nova é criada com o e-mail do
 * candidato e uma senha aleatória, gravada em Cadastros para entrar no mesmo site da próxima vez.
 */
public class AgenteExterno {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String SENHA = "{{SENHA}}";
    static final String CPF = "{{CPF}}";
    private static final int MAX_PASSOS = 30;
    private static final int MAX_ELEMENTOS = 160;

    private final IaClient ia;
    private final CadastroRepo cadastros;
    private final CvContexto cvContexto;
    private final KvRepo kv;
    private Consumer<String> log = s -> { };

    /** Estado da vaga em andamento: sobrevive a um chamado ao usuário (a vaga continua de onde parou). */
    private final List<String> historico = new ArrayList<>();
    /** Senha nova por plataforma nesta vaga (o fluxo pode pular do site da empresa para o ATS). */
    private final Map<String, String> senhasNovas = new HashMap<>();
    /** Último botão clicado e quantas vezes seguidas: a IA free insiste no mesmo clique que não muda nada. */
    private String ultimoClique = "";
    private int cliquesIguais;

    public AgenteExterno(IaClient ia, CadastroRepo cadastros, CvContexto cvContexto, KvRepo kv) {
        this.ia = ia;
        this.cadastros = cadastros;
        this.cvContexto = cvContexto;
        this.kv = kv;
    }

    public void observar(Consumer<String> log) {
        this.log = log;
    }

    /** Começa uma vaga nova: esquece os passos e as senhas da anterior. */
    public void reiniciar() {
        historico.clear();
        senhasNovas.clear();
        ultimoClique = "";
        cliquesIguais = 0;
    }

    /** Elemento numerado da página; {@code frame} é o índice do iframe (-1 = página principal). */
    record Elemento(String id, int frame, String tag, String tipo, String rotulo, String valor, boolean marcado,
                    boolean obrigatorio, List<String> opcoes) {}

    public Desfecho candidatar(WebDriver d, Vaga v, Cv cv, boolean dryRun, BooleanSupplier continuar) {
        String anterior = null;
        int parados = 0;
        for (int passo = 0; passo < MAX_PASSOS && continuar.getAsBoolean(); passo++) {
            List<Elemento> els = ler(d);
            // SPA ainda desenhando (nenhum elemento): espera em vez de gastar uma chamada à IA, que leva minutos
            for (int espera = 0; espera < 5 && els.isEmpty() && continuar.getAsBoolean(); espera++) {
                pausa(3000);
                els = ler(d);
            }
            String texto = textoDaPagina(d);
            String assinatura = d.getCurrentUrl() + "|" + texto.hashCode() + "|" + els.hashCode();
            parados = assinatura.equals(anterior) ? parados + 1 : 0;
            anterior = assinatura;
            if (parados >= 3) {
                log.accept("site externo · página parada, foto em " + GupyDriver.capturar(d));
                return new Desfecho(Status.PRECISA_USUARIO, motivo("a página não avança (" + d.getCurrentUrl()
                        + "): confira no Firefox o que falta e clique em Resolvido"));
            }
            JsonNode r;
            long inicioIa = System.currentTimeMillis();
            try {
                r = ia.conversarJson(SYSTEM, pedido(d, v, cv, texto, els));
            } catch (IaClient.IaIndisponivel e) {
                throw e;
            } catch (RuntimeException e) { // JSON inválido: conta como passo parado e pede de novo
                historico.add("passo " + passo + ": a IA devolveu resposta inválida (" + resumir(e.getMessage(), 80) + ")");
                continue;
            }
            String estado = r.path("estado").asText("agir");
            String motivoIa = r.path("motivo").asText("");
            log.accept("site externo · passo " + (passo + 1) + " (" + CadastroRepo.dominio(d.getCurrentUrl()) + ", IA "
                    + (System.currentTimeMillis() - inicioIa) / 1000 + "s " + ia.ultimoModelo() + "): " + estado + (motivoIa.isBlank() ? "" : " — " + resumir(motivoIa, 140)));
            switch (estado) {
                case "enviada" -> {
                    return new Desfecho(Status.ENVIADA, "site da empresa: " + resumir(motivoIa, 160));
                }
                case "pular" -> {
                    return new Desfecho(Status.PULADA, "site da empresa: " + resumir(motivoIa, 200));
                }
                case "usuario" -> {
                    return new Desfecho(Status.PRECISA_USUARIO, motivo(motivoIa));
                }
                default -> { }
            }
            if (r.path("cadastro").asBoolean(false)) salvarCadastro(d, v, r.path("metodo").asText("senha"));
            Desfecho fim = executar(d, v, cv, els, r.path("acoes"), dryRun, passo);
            if (fim != null) return fim;
            pausa(3000);
        }
        if (!continuar.getAsBoolean()) return new Desfecho(Status.ERRO, "sessão parada");
        return new Desfecho(Status.PRECISA_USUARIO, motivo("a candidatura não terminou em " + MAX_PASSOS
                + " passos: termine no Firefox e clique em Resolvido"));
    }

    /** Prefixo que o Orquestrador reconhece: o chamado é ação manual no Firefox, não login de portal nem pendência. */
    static String motivo(String texto) {
        return "site externo: " + (texto == null || texto.isBlank() ? "preciso de você na janela do Firefox" : texto);
    }

    private Desfecho executar(WebDriver d, Vaga v, Cv cv, List<Elemento> els, JsonNode acoes, boolean dryRun, int passo) {
        Map<String, Elemento> porId = new HashMap<>();
        els.forEach(e -> porId.put(e.id(), e));
        List<String> feito = new ArrayList<>();
        for (JsonNode a : acoes) {
            String tipo = a.path("tipo").asText("");
            String id = a.path("id").asText("");
            String valor = a.path("valor").asText("");
            Elemento e = porId.get(id);
            if (e == null) {
                feito.add(tipo + " " + id + ": elemento não existe");
                continue;
            }
            if (tipo.equals("clicar") && opcao(e) && e.marcado() && ("radio".equals(e.tipo()) || "option".equals(e.tipo()))) {
                feito.add("\"" + resumir(e.rotulo(), 40) + "\" já estava marcado");
                continue; // clicar de novo não muda nada (e num checkbox desmarcaria)
            }
            if (tipo.equals("enviar") && dryRun) {
                historico.add("passo " + passo + ": " + String.join("; ", feito));
                log.accept("site externo · " + resumir(String.join("; ", feito), 300) + " — dry-run: não clica em enviar");
                return new Desfecho(Status.PRONTA_DRY_RUN, "dry-run: formulário do site da empresa pronto, não enviado");
            }
            try {
                feito.add(switch (tipo) {
                    // "preencher" numa lista customizada (div/botão "Selecionar"): é escolher a opção
                    case "preencher" -> !editavel(e) ? selecionar(d, e, valor)
                            // autocomplete (input role=combobox): o texto digitado só filtra, a resposta é a opção clicada
                            : "combobox".equals(e.tipo()) ? preencher(d, e, valor) + "; " + escolherNaLista(d, e, valor)
                            : preencher(d, e, substituir(d, valor));
                    case "selecionar" -> selecionar(d, e, valor);
                    case "anexar_cv" -> anexar(d, e, cv);
                    case "clicar", "enviar" -> clicar(d, e);
                    default -> tipo + " " + id + ": ação desconhecida";
                });
            } catch (org.openqa.selenium.WebDriverException x) {
                feito.add(tipo + " " + id + " falhou: " + resumir(x.getMessage(), 100));
            } finally {
                d.switchTo().defaultContent();
            }
            // a página muda depois do clique em botão/link: lê de novo. Marcar opção não muda: segue com as outras ações
            if (tipo.equals("enviar") || tipo.equals("clicar") && !opcao(e)) {
                cliquesIguais = e.rotulo().equals(ultimoClique) ? cliquesIguais + 1 : 1;
                ultimoClique = e.rotulo();
                break;
            }
        }
        String resumo = feito.isEmpty() ? "nenhuma ação" : String.join("; ", feito);
        historico.add("passo " + passo + ": " + resumo);
        log.accept("site externo · " + resumir(resumo, 300));
        return null;
    }

    /** Placeholders da IA trocados aqui, para a senha e o CPF nunca irem ao modelo. */
    private String substituir(WebDriver d, String valor) {
        String r = valor;
        if (r.contains(SENHA)) r = r.replace(SENHA, senha(d.getCurrentUrl()));
        if (r.contains(CPF)) r = r.replace(CPF, kv.todos(KvRepo.DADO).getOrDefault("CPF", ""));
        return r;
    }

    /** Senha do site: a da conta salva ou, sem conta, uma nova (a mesma durante toda a vaga). */
    private String senha(String url) {
        return cadastros.doSite(url).map(CadastroRepo.Conta::senha).filter(s -> !s.isEmpty())
                .orElseGet(() -> senhasNovas.computeIfAbsent(chave(url), k -> CadastroRepo.senhaAleatoria()));
    }

    /** Senha nova vale para a plataforma: depois do cadastro o site pode seguir em outro subdomínio. */
    private static String chave(String url) {
        return CadastroRepo.plataforma(CadastroRepo.dominio(url));
    }

    private void salvarCadastro(WebDriver d, Vaga v, String metodo) {
        String url = d.getCurrentUrl();
        if (cadastros.doSite(url).isPresent() && !senhasNovas.containsKey(chave(url))) return; // já tinha conta: só entrou
        boolean comSenha = !"google".equals(metodo) && !"linkedin".equals(metodo);
        // a IA às vezes marca cadastro num clique comum: conta com senha só existe se a senha nova foi digitada
        if (comSenha && !senhasNovas.containsKey(chave(url))) return;
        String email = email();
        cadastros.salvar(v.empresa(), url, email, comSenha ? senha(url) : "", comSenha ? "senha" : metodo,
                v.empresa() + " — " + v.cargo() + " · " + v.url());
        log.accept("conta criada em " + CadastroRepo.dominio(url) + " (" + email + ", " + (comSenha ? "senha" : metodo)
                + ") — salva em Cadastros");
    }

    String email() {
        return kv.todos(KvRepo.DADO).getOrDefault("E-mail", "");
    }

    private static String preencher(WebDriver d, Elemento e, String valor) {
        WebElement el = achar(d, e);
        if (el == null) return "preencher " + e.id() + ": sumiu";
        rolar(d, el);
        if ("true".equals(el.getAttribute("contenteditable"))) el.click();
        else el.clear();
        el.sendKeys(valor);
        // campo com máscara (celular, CPF, data): digitar "(48) 98811-4351" duplica os símbolos da máscara; só os números
        String digitos = valor.replaceAll("\\D", "");
        if (digitos.isEmpty() || digitos.length() < valor.replaceAll("\\s", "").length() / 2)
            return "preencheu \"" + resumir(e.rotulo(), 40) + "\"" + (e.tipo().equals("password") ? " (senha)" : "");
        String nota = "";
        if (!aceito(valorDe(el), digitos)) {
            redigitar(el, digitos);
            nota = " só com os números (campo com máscara)";
        }
        // moeda que enche da direita ("R$ 0,01" → "R$ 100,00" para 10000): os dígitos batem, mas faltam os centavos
        String ficou = valorDe(el);
        if (moeda(ficou) && ficou.replaceAll("\\D", "").equals(digitos) && !valor.contains(",")) {
            redigitar(el, digitos + "00");
            nota = " com os centavos (campo de moeda)";
        }
        // celular com DDI ("+ __ (__) _____-____") sem o 55: o DDD vira código do país e o número fica curto
        if (telefone(e) && (digitos.length() == 10 || digitos.length() == 11) && precisaDdi(valorDe(el))) {
            redigitar(el, "55" + digitos);
            nota = " com o DDI 55 (campo de celular internacional)";
        }
        return "preencheu \"" + resumir(e.rotulo(), 40) + "\"" + nota;
    }

    /**
     * O campo ficou com os números digitados: o "+55" que o campo já traz fica na frente e a máscara de moeda põe os
     * centavos atrás ("10.000" vira "R$ 10.000,00"); redigitar só os números ali dividiria o valor por 100.
     */
    static boolean aceito(String ficou, String digitos) {
        String v = ficou.replaceAll("\\D", "");
        return v.endsWith(digitos) || v.startsWith(digitos) && v.substring(digitos.length()).matches("0{0,2}");
    }

    static boolean moeda(String valor) {
        return valor.contains("R$") || valor.trim().matches(".*\\d,\\d{2}");
    }

    private static String valorDe(WebElement el) {
        return String.valueOf(el.getAttribute("value"));
    }

    /** O campo mostra um código de país ("+48 (98)…") que não é o do Brasil. */
    static boolean precisaDdi(String valor) {
        return valor.trim().startsWith("+") && !valor.replaceAll("\\D", "").startsWith("55");
    }

    private static void redigitar(WebElement el, String digitos) {
        el.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.DELETE);
        digitar(el, digitos);
    }

    private static void digitar(WebElement el, String digitos) {
        for (char c : digitos.toCharArray()) {
            el.sendKeys(String.valueOf(c));
            pausa(40);
        }
    }

    /** Campo de digitar; o resto (select, div/botão "Selecionar" de lista customizada) se escolhe. */
    private static boolean editavel(Elemento e) {
        return e.tag().matches("input|textarea") || !e.tag().equals("select") && !"combobox".equals(e.tipo())
                && !e.tag().equals("button") && !e.tag().equals("a");
    }

    /** Radio, checkbox ou opção de lista: clicar marca a resposta sem trocar de página. */
    private static boolean opcao(Elemento e) {
        return java.util.Set.of("radio", "checkbox", "option", "switch").contains(e.tipo());
    }

    private static boolean telefone(Elemento e) {
        String r = Texto.normalizar(e.rotulo());
        return "tel".equals(e.tipo()) || r.contains("celular") || r.contains("telefone") || r.contains("whatsapp") || r.contains("phone");
    }

    /** {@code <select>} pela opção de texto igual (ou que contém); outro tipo de lista é clicado. */
    private static String selecionar(WebDriver d, Elemento e, String valor) {
        WebElement el = achar(d, e);
        if (el == null) return "selecionar " + e.id() + ": sumiu";
        if (!"select".equalsIgnoreCase(el.getTagName())) {
            rolar(d, el);
            try {
                el.click();
            } catch (org.openqa.selenium.WebDriverException x) {
                ((JavascriptExecutor) d).executeScript("arguments[0].click()", el);
            }
            if (valor.isBlank() || opcao(e)) return "clicou na opção \"" + resumir(e.rotulo(), 40) + "\"";
            return escolherNaLista(d, e, valor);
        }
        org.openqa.selenium.support.ui.Select s = new org.openqa.selenium.support.ui.Select(el);
        String alvo = Texto.normalizar(valor);
        List<WebElement> opcoes = s.getOptions();
        for (int fase = 0; fase < 2; fase++)
            for (int i = 0; i < opcoes.size(); i++) {
                String t = Texto.normalizar(opcoes.get(i).getText());
                if (fase == 0 ? t.equals(alvo) : !alvo.isEmpty() && (t.contains(alvo) || alvo.contains(t) && !t.isEmpty())) {
                    s.selectByIndex(i);
                    return "selecionou \"" + resumir(opcoes.get(i).getText(), 40) + "\" em \"" + resumir(e.rotulo(), 40) + "\"";
                }
            }
        return "selecionar \"" + valor + "\": opção não existe em \"" + resumir(e.rotulo(), 40) + "\"";
    }

    /**
     * Lista customizada (MUI, React Select): o clique abriu as opções; clica na de texto igual (ou que contém). Sem ela,
     * fecha a lista e devolve as opções que existem, para a IA escolher uma delas no próximo passo.
     */
    private static String escolherNaLista(WebDriver d, Elemento e, String valor) {
        pausa(700);
        String alvo = Texto.normalizar(valor);
        List<WebElement> opcoes = new ArrayList<>();
        for (WebElement o : d.findElements(By.cssSelector("[role=option],[role=menuitem],.MuiMenuItem-root,[class*=option]")))
            try {
                if (o.isDisplayed() && !o.getText().isBlank()) opcoes.add(o);
            } catch (org.openqa.selenium.StaleElementReferenceException ignorado) {
                // lista redesenhada
            }
        for (int fase = 0; fase < 2; fase++)
            for (WebElement o : opcoes) {
                String t = Texto.normalizar(o.getText());
                if (fase == 0 ? t.equals(alvo) : !alvo.isEmpty() && (t.contains(alvo) || alvo.contains(t))) {
                    String texto = o.getText();
                    ((JavascriptExecutor) d).executeScript("arguments[0].scrollIntoView({block:'center'});arguments[0].click()", o);
                    pausa(400);
                    return "selecionou \"" + resumir(texto, 40) + "\" em \"" + resumir(e.rotulo(), 40) + "\"";
                }
            }
        List<String> textos = opcoes.stream().limit(15).map(o -> resumir(o.getText(), 40)).toList();
        d.switchTo().activeElement().sendKeys(org.openqa.selenium.Keys.ESCAPE);
        return "selecionar \"" + valor + "\": opção não existe em \"" + resumir(e.rotulo(), 40) + "\""
                + (textos.isEmpty() ? " (a lista não abriu)" : "; opções: " + textos);
    }

    /** Anexa o PDF do CV no input de arquivo (o elemento indicado ou o primeiro input file do mesmo frame). */
    private static String anexar(WebDriver d, Elemento e, Cv cv) {
        if (cv == null || cv.arquivoPdf() == null || !new java.io.File(cv.arquivoPdf()).isFile())
            return "anexar CV: arquivo do CV não encontrado";
        WebElement el = achar(d, e);
        if (el == null || !"file".equals(el.getAttribute("type")))
            el = d.findElements(By.cssSelector("input[type=file]")).stream().findFirst().orElse(null);
        if (el == null) return "anexar CV: nenhum campo de arquivo";
        // inputs de arquivo costumam ficar escondidos atrás de um botão estilizado: o Selenium só envia para visíveis
        ((JavascriptExecutor) d).executeScript("arguments[0].style.display='block';arguments[0].style.visibility='visible';"
                + "arguments[0].style.opacity=1;arguments[0].removeAttribute('hidden');", el);
        el.sendKeys(cv.arquivoPdf());
        pausa(2000);
        return "anexou o CV " + cv.nome();
    }

    private static String clicar(WebDriver d, Elemento e) {
        WebElement el = achar(d, e);
        if (el == null) return "clicar " + e.id() + ": sumiu";
        java.util.Set<String> abas = d.getWindowHandles();
        rolar(d, el);
        // "você será redirecionado ao site da empresa" (Gupy e outros) em alert/confirm: aceita sem travar o Selenium
        // e bloqueia a janela nativa de escolher arquivo (clique na área de "anexar foto/CV"): ela trava o Firefox inteiro
        ((JavascriptExecutor) d).executeScript("window.alert=function(){};window.confirm=function(){return true;};"
                + "if(!window.__sjArquivo){window.__sjArquivo=1;document.addEventListener('click',function(e){"
                + "if(e.target&&e.target.type==='file')e.preventDefault();},true);}");
        if ("file".equals(el.getAttribute("type"))) return "clicar " + e.id() + ": campo de arquivo, use anexar_cv";
        try {
            el.click();
        } catch (org.openqa.selenium.WebDriverException x) { // coberto por banner/overlay: clique por JavaScript
            ((JavascriptExecutor) d).executeScript("arguments[0].click()", el);
        }
        pausa(1500);
        for (String aba : d.getWindowHandles()) // o botão abriu outra aba (login social, ATS): segue nela
            if (!abas.contains(aba)) {
                d.switchTo().window(aba);
                return "clicou em \"" + resumir(e.rotulo(), 40) + "\" (abriu nova aba)";
            }
        return "clicou em \"" + resumir(e.rotulo(), 40) + "\"";
    }

    private static void rolar(WebDriver d, WebElement el) {
        ((JavascriptExecutor) d).executeScript("arguments[0].scrollIntoView({block:'center'})", el);
    }

    private static WebElement achar(WebDriver d, Elemento e) {
        d.switchTo().defaultContent();
        if (e.frame() >= 0) {
            List<WebElement> frames = d.findElements(By.tagName("iframe"));
            if (e.frame() >= frames.size()) return null;
            d.switchTo().frame(frames.get(e.frame()));
        }
        return d.findElements(By.cssSelector("[data-sj='" + e.id() + "']")).stream().findFirst().orElse(null);
    }

    // numera os elementos interativos visíveis (inputs de arquivo entram mesmo escondidos) e devolve a descrição de cada um
    private static final String LER = """
            var pre=arguments[0],max=arguments[1],out=[],n=0;
            document.querySelectorAll('[data-sj]').forEach(function(e){e.removeAttribute('data-sj');});
            function vis(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);
              return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
            function limpa(t){return (t||'').replace(/\\s+/g,' ').trim().slice(0,120);}
            function rot(e){
              var t='';if(e.labels&&e.labels.length)t=e.labels[0].innerText;
              if(!t&&e.getAttribute('aria-labelledby')){var l=document.getElementById(e.getAttribute('aria-labelledby').split(' ')[0]);if(l)t=l.innerText;}
              if(!t)t=e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||'';
              if(!t&&/^(input|textarea|select)$/i.test(e.tagName)){var p=e.parentElement;
                for(var i=0;i<4&&p&&!t;i++,p=p.parentElement){if(p.querySelectorAll('input,textarea,select').length>1)break;t=p.innerText;}}
              if(!t)t=e.innerText||e.value||e.getAttribute('name')||'';
              var fs=e.closest('fieldset');if(fs&&/^(radio|checkbox)$/.test(e.type)){var lg=fs.querySelector('legend');if(lg)t=limpa(lg.innerText)+' → '+t;}
              return limpa(t);}
            var links=0;
            document.querySelectorAll('input,textarea,select,button,a[href],[role=button],[role=checkbox],[role=radio],[role=combobox],[role=option],[role=switch],[contenteditable=true]').forEach(function(e){
              if(out.length>=max)return;
              var tipo=(e.getAttribute('role')=='combobox'?'combobox':e.type||e.getAttribute('role')||'').toLowerCase();
              if(tipo=='hidden')return;
              if(tipo!='file'&&!vis(e))return;
              if(e.tagName=='A'){if(!limpa(e.innerText)||links>=50)return;links++;}
              var id=pre+(n++);e.setAttribute('data-sj',id);
              var o={id:id,tag:e.tagName.toLowerCase(),tipo:tipo,rotulo:rot(e),
                valor:tipo=='password'?(e.value?'(preenchida)':''):limpa(e.value||(e.isContentEditable?e.innerText:'')),
                marcado:!!(e.checked||e.getAttribute('aria-checked')=='true'||e.getAttribute('aria-selected')=='true'),
                obrigatorio:!!(e.required||e.getAttribute('aria-required')=='true'),opcoes:[]};
              if(e.tagName=='SELECT')for(var i=0;i<e.options.length&&i<40;i++)o.opcoes.push(limpa(e.options[i].text));
              if(e.tagName=='SELECT'&&e.selectedIndex>=0)o.valor=limpa(e.options[e.selectedIndex].text);
              out.push(o);});
            return JSON.stringify(out);
            """;

    /** Elementos da página e dos iframes visíveis (formulário embutido de ATS, como o Greenhouse). */
    List<Elemento> ler(WebDriver d) {
        List<Elemento> out = new ArrayList<>();
        d.switchTo().defaultContent();
        lerFrame(d, "e", -1, out);
        List<WebElement> frames = d.findElements(By.tagName("iframe"));
        for (int i = 0; i < frames.size() && out.size() < MAX_ELEMENTOS; i++) {
            try {
                WebElement f = frames.get(i);
                if (!f.isDisplayed() || f.getSize().getHeight() < 150 || f.getSize().getWidth() < 200) continue;
                d.switchTo().frame(f);
                lerFrame(d, "f" + i + "e", i, out);
            } catch (org.openqa.selenium.WebDriverException e) {
                // iframe recriado ou bloqueado: fica de fora
            } finally {
                d.switchTo().defaultContent();
            }
        }
        return out;
    }

    private static void lerFrame(WebDriver d, String prefixo, int frame, List<Elemento> out) {
        try {
            Object r = ((JavascriptExecutor) d).executeScript(LER, prefixo, MAX_ELEMENTOS - out.size());
            for (JsonNode n : JSON.readTree(String.valueOf(r))) {
                List<String> opcoes = new ArrayList<>();
                n.path("opcoes").forEach(o -> opcoes.add(o.asText()));
                out.add(new Elemento(n.path("id").asText(), frame, n.path("tag").asText(), n.path("tipo").asText(),
                        n.path("rotulo").asText(), n.path("valor").asText(), n.path("marcado").asBoolean(),
                        n.path("obrigatorio").asBoolean(), opcoes));
            }
        } catch (Exception e) {
            // página trocando no meio da leitura: o próximo passo lê de novo
        }
    }

    private static String textoDaPagina(WebDriver d) {
        try {
            d.switchTo().defaultContent();
            // innerText por JavaScript: o getText do Selenium confere a visibilidade nó a nó e estoura o prazo em página pesada
            Object r = ((JavascriptExecutor) d).executeScript("return document.body ? document.body.innerText : ''");
            String t = String.valueOf(r).replaceAll("\\s+", " ").trim();
            return t.length() > 3000 ? t.substring(0, 3000) : t;
        } catch (org.openqa.selenium.WebDriverException e) {
            return "";
        }
    }

    private String pedido(WebDriver d, Vaga v, Cv cv, String texto, List<Elemento> els) {
        String url = d.getCurrentUrl();
        var conta = cadastros.doSite(url);
        StringBuilder sb = new StringBuilder();
        sb.append("VAGA: ").append(v.empresa()).append(" — ").append(v.cargo()).append(" (").append(v.local()).append(")\n");
        String descr = v.descricao() == null ? "" : v.descricao();
        sb.append(descr.length() > 1500 ? descr.substring(0, 1500) + "…" : descr).append("\n\n");
        sb.append("CANDIDATO: ").append(cvContexto.dadosParaPrompt()).append('\n');
        sb.append("CPF: use ").append(CPF).append(" (o sistema troca pelo número)\n");
        var pref = kv.todos(KvRepo.PREF);
        sb.append("PRETENSÃO: CLT ").append(pref.getOrDefault("pretensao_clt", "")).append(", PJ ")
                .append(pref.getOrDefault("pretensao_pj", "")).append('\n');
        sb.append("CV ESCOLHIDO: ").append(cv == null ? "nenhum" : cv.nome()).append(" (anexe com anexar_cv)\n");
        sb.append("CONTA NESTE SITE (").append(CadastroRepo.dominio(url)).append("): ");
        if (conta.isPresent() && !conta.get().dominio().equals(CadastroRepo.dominio(url)))
            sb.append("(conta criada em ").append(conta.get().dominio()).append(", mesma plataforma; se o login não entrar, crie uma nova) ");
        if (conta.isPresent() && !"senha".equals(conta.get().metodo()))
            sb.append("já existe, entra com ").append(conta.get().metodo()).append(" (use \"Entrar com ").append(conta.get().metodo()).append("\")\n");
        else if (conta.isPresent())
            sb.append("já existe: login ").append(conta.get().login()).append(", senha ").append(SENHA).append(" — entre, não crie outra\n");
        else
            sb.append("nenhuma. Se o site exigir conta, crie com o e-mail ").append(email()).append(" e a senha ").append(SENHA)
                    .append(" (marque \"cadastro\": true no passo que envia o cadastro). Na tela de login, vá em")
                    .append(" Cadastre-se/Criar conta: não há conta para Entrar/Iniciar sessão\n");
        String cvTexto = cvContexto.textoCvs();
        sb.append("\nCURRÍCULO:\n").append(cvTexto.length() > 3500 ? cvTexto.substring(0, 3500) : cvTexto).append("\n\n");
        if (!historico.isEmpty()) {
            sb.append("PASSOS ANTERIORES (do mais antigo ao mais novo):\n");
            historico.subList(Math.max(0, historico.size() - 8), historico.size()).forEach(h -> sb.append("- ").append(h).append('\n'));
            sb.append('\n');
        }
        if (cliquesIguais >= 2)
            sb.append("ATENÇÃO: você já clicou ").append(cliquesIguais).append("x seguidas em \"").append(ultimoClique)
                    .append("\" e a candidatura não avançou. NÃO clique nele de novo: leia o erro na tela e faça outra coisa")
                    .append(" (preencher o campo que falta, Cadastre-se/Criar conta, outro botão).\n\n");
        sb.append("PÁGINA ATUAL: ").append(url).append(" — ").append(d.getTitle()).append('\n');
        sb.append("TEXTO: ").append(texto).append("\n\nELEMENTOS:\n");
        for (Elemento e : els) {
            sb.append(e.id()).append(" ").append(e.tag()).append(e.tipo().isEmpty() ? "" : "[" + e.tipo() + "]")
                    .append(" \"").append(e.rotulo()).append('"');
            if (!e.valor().isEmpty()) sb.append(" valor=\"").append(e.valor()).append('"');
            if (e.marcado()) sb.append(" marcado");
            if (e.obrigatorio()) sb.append(" obrigatório");
            if (!e.opcoes().isEmpty()) sb.append(" opções=").append(e.opcoes());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String resumir(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static void pausa(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessaoParada();
        }
    }

    static final String SYSTEM = """
            Você controla um navegador para se candidatar a uma vaga no site da empresa (ou no ATS dela: Gupy, Greenhouse,
            Lever, Workday, InHire, Kenoby, Sólides, Pandapé, etc.). A cada passo recebe a página atual (texto e elementos
            numerados) e devolve SOMENTE um JSON:
            {"estado":"agir|enviada|pular|usuario","motivo":"curto, em português","cadastro":false,"metodo":"senha|google|linkedin",
             "acoes":[{"tipo":"preencher|selecionar|clicar|anexar_cv|enviar","id":"e12","valor":"texto"}]}

            Como agir:
            - Objetivo: terminar a candidatura desta vaga. Em cada passo preencha TODOS os campos visíveis que ainda estão
              vazios (principalmente os obrigatórios) e termine com UM clique (clicar/enviar) no botão que avança. Nada depois do clique.
            - "enviar" é só o clique que envia a candidatura final (Enviar candidatura, Finalizar, Concluir, Submit application).
              Todo o resto (Candidatar-se, Avançar, Continuar, Próximo, Criar conta, Entrar, aceitar cookies) é "clicar".
            - "selecionar" para <select> (valor = texto exato de uma das opções); radio, checkbox e opções de listas
              customizadas: "clicar" no elemento da opção (não clique de novo no que já está marcado).
            - Campo de currículo/CV/anexo (input file): "anexar_cv" com o id dele. Nunca "clicar" em área de upload
              (foto, anexo): só "anexar_cv" no input file do currículo; foto é opcional, deixe sem. Se o site oferece preencher pelo
              LinkedIn ou importar o CV, pode usar.
            - Conta: se o site pede login/cadastro, siga a linha CONTA NESTE SITE. "Entrar com Google" é a melhor opção
              quando existe (o navegador já está logado na conta Google do candidato): nesse caso "cadastro": true e
              "metodo": "google" no passo do clique. Ao enviar um formulário de cadastro com senha: "cadastro": true,
              "metodo": "senha". Senha e confirmação de senha: escreva exatamente {{SENHA}}.
            - Aceite os termos de uso/privacidade/LGPD e feche banners de cookies que atrapalham.
            - Telefone com seletor de país (bandeira, +1): escolha Brasil (+55) antes de preencher o número.
            - Respostas como um dev sênior escreveria: curtas, precisas e naturais. Anos e valores: só o número
              (ex.: 15, 10.000). Pretensão salarial: CLT ou PJ conforme a vaga; se não disser, CLT.
            - Use só os dados do CANDIDATO e do CURRÍCULO. Não invente RG, data de nascimento, dados bancários nem números
              de documento: se forem obrigatórios, estado "usuario" dizendo qual campo falta.
            - Não clique em links que saem da candidatura (blog, outras vagas, redes sociais, "saiba mais").
            - Se a última ação não mudou nada (veja PASSOS ANTERIORES), tente outro caminho: erro de validação na tela,
              campo obrigatório esquecido, botão diferente.

            Estados:
            - "agir": há ações a executar.
            - "enviada": a página confirma que a candidatura foi recebida/concluída.
            - "pular": vaga encerrada, descrição da vaga em inglês (interface do site em inglês não conta), exige inglês fluente/conversação, candidatura só por
              e-mail, ou o site não tem como se candidatar. Motivo curto.
            - "usuario": CAPTCHA, código enviado por e-mail/SMS, confirmação de e-mail, verificação de identidade, ou
              pergunta que os dados não respondem. No motivo, diga exatamente o que a pessoa precisa fazer na janela.
            """;
}
