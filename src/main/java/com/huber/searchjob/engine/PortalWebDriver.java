package com.huber.searchjob.engine;

import com.huber.searchjob.db.Cv;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Base dos portais que candidatam pelo Firefox seguindo um laço genérico:
 * detecta sucesso/login/CAPTCHA → preenche campos de texto via {@link SituacaoService} → clica no próximo botão.
 * Botões de "envio" encerram o fluxo: em dry-run o laço para antes de clicar neles.
 */
public abstract class PortalWebDriver implements PortalDriver {
    protected final NavegadorFirefox navegador;
    protected final SituacaoService situacoes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    protected PortalWebDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        this.navegador = navegador;
        this.situacoes = situacoes;
    }

    /** Botões que apenas avançam o fluxo (Continuar, Próximo...). */
    protected abstract String[] botoesAvanco();

    /** Botões que enviam a candidatura. */
    protected abstract String[] botoesEnvio();

    /** Trechos (sem acento, minúsculos) que confirmam a candidatura. */
    protected abstract String[] textosSucesso();

    /** Trechos que indicam candidatura já feita por esta conta. */
    protected String[] textosJaCandidatou() {
        return new String[] {"voce ja se candidatou", "candidatura ja enviada", "ja se candidatou"};
    }

    /** Trechos (sem acento, minúsculos) com que o portal avisa que esta conta não pode se candidatar à vaga. */
    protected String[] textosBloqueio() {
        return new String[0];
    }

    /** Ponto de extensão para casos próprios do portal (ex.: vaga externa). */
    /** Seletor do bloco com a descrição da vaga: a página inteira mistura o menu do portal em português com o texto da empresa. */
    protected String seletorDescricao() {
        return null;
    }

    /** Descrição da vaga em inglês (bloco próprio quando o portal tem, senão a página toda). */
    private boolean descricaoEmIngles(WebDriver d, String corpo) {
        if (Texto.emIngles(corpo)) return true;
        if (seletorDescricao() == null) return false;
        for (WebElement e : d.findElements(By.cssSelector(seletorDescricao())))
            if (Texto.emIngles(e.getText())) return true;
        return false;
    }

    protected Optional<Desfecho> casoEspecial(WebDriver d, String corpoNormalizado) {
        return Optional.empty();
    }

    /** Limite de requisições (429) é intermitente nas buscas públicas: espera e tenta de novo antes de falhar. */
    protected String get(String url) throws Exception {
        for (int tentativa = 1; ; tentativa++) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0")
                    .header("Accept-Language", "pt-BR,pt;q=0.9").timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) return "";
            if (r.statusCode() == 429 && tentativa < 5) {
                pausa(5000L * tentativa);
                continue;
            }
            if (r.statusCode() / 100 != 2) throw new IllegalStateException(nome() + " busca HTTP " + r.statusCode());
            return r.body();
        }
    }

    @Override
    public boolean pedeCaptcha() {
        return LoginAutomatico.pedeCaptcha(navegador.driver());
    }

    @Override
    public String diagnostico() {
        return LoginAutomatico.diagnostico(navegador.driver());
    }

    @Override
    public String[] lerLogin() {
        return LoginAutomatico.ler(navegador.driver());
    }

    @Override
    public boolean entrar(String usuario, String senha) {
        return LoginAutomatico.entrar(navegador.driver(), usuario, senha);
    }

    @Override
    public Desfecho candidatar(Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar) {
        try {
            return fluxo(vaga, cv, dryRun, continuar);
        } catch (org.openqa.selenium.UnhandledAlertException e) {
            // ex.: "você será redirecionado ao sistema de seleção da empresa": vaga externa, não candidata por aqui
            String aviso = e.getAlertText() == null ? "" : e.getAlertText().strip();
            return new Desfecho(Status.PULADA, "vaga externa (candidatura fora do portal)"
                    + (aviso.isEmpty() ? "" : ": " + (aviso.length() > 120 ? aviso.substring(0, 120) : aviso)));
        }
    }

    /** Formulário de candidatura (janela aberta) escrito em inglês: a vaga é de empresa estrangeira, não se candidata. */
    private static boolean formularioEmIngles(WebDriver d) {
        for (WebElement j : d.findElements(By.cssSelector("dialog[open],[role=dialog],[role=alertdialog],.artdeco-modal,form")))
            if (j.isDisplayed() && Texto.emIngles(j.getText(), 5)) return true;
        // a moldura do portal é em português ("Candidate-se", "Voltar"): basta as perguntas da empresa estarem em inglês
        Object perguntas = ((JavascriptExecutor) d).executeScript(
                "var t=[];document.querySelectorAll('dialog[open] label,dialog[open] legend,[role=dialog] label,[role=dialog] legend,.artdeco-modal label,"
                        + ".artdeco-modal legend,form label,form legend').forEach(function(e){"
                        + "if(e.getClientRects().length)t.push(e.innerText);});return t.join(' ');");
        return perguntas != null && Texto.emIngles(perguntas.toString(), 4);
    }

    /** Abre a vaga e confere o domínio: a navegação vinda de outro portal às vezes não completa e deixa a página anterior em branco. */
    private void abrir(WebDriver d, String url) {
        String host = java.net.URI.create(url).getHost();
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            d.get(url);
            pausa(2500);
            String atual = String.valueOf(d.getCurrentUrl());
            if (host == null || atual.contains(host.replaceFirst("^www\\.", ""))) return;
        }
    }

    /** Perguntas já respondidas na vaga atual: se o formulário volta ao início e repete uma, a vaga é pulada em vez de entrar em loop. */
    private final java.util.Set<String> perguntasDaVaga = new java.util.HashSet<>();

    private Desfecho fluxo(Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar) {
        WebDriver d = navegador.driver();
        perguntasDaVaga.clear();
        abrir(d, vaga.url());
        String contexto = vaga.empresa() + " — " + vaga.cargo() + "\n" + vaga.descricao();
        String anterior = null;
        int parados = 0;
        boolean abriu = false;
        boolean clicouEnvio = false;
        for (int passo = 0; passo < 20 && continuar.getAsBoolean(); passo++) {
            String corpo = Texto.normalizar(d.findElement(By.tagName("body")).getText());
            if (passo == 0 && descricaoEmIngles(d, corpo)) return new Desfecho(Status.PULADA, "vaga em inglês");
            if (passo > 0 && formularioEmIngles(d)) return new Desfecho(Status.PULADA, "vaga em inglês");
            if (passo == 0) // ao abrir a vaga, qualquer aviso de candidatura existente é de antes desta execução
                for (String s : textosJaCandidatou())
                    if (corpo.contains(s)) return new Desfecho(Status.PULADA, "já candidatada nesta conta");
            for (String s : textosSucesso())
                if (corpo.contains(s)) return new Desfecho(Status.ENVIADA, s);
            for (String s : textosJaCandidatou())
                if (corpo.contains(s)) // depois do clique em Enviar, "candidatura enviada" é a confirmação deste envio
                    return clicouEnvio ? new Desfecho(Status.ENVIADA, s) : new Desfecho(Status.PULADA, "já candidatada nesta conta");
            for (String s : textosBloqueio()) {
                int i = corpo.indexOf(s);
                if (i >= 0) return new Desfecho(Status.PULADA, "portal não permite a candidatura: "
                        + corpo.substring(i, Math.min(corpo.length(), i + 100)));
            }
            if (corpo.contains("captcha") || corpo.contains("security check") || corpo.contains("just a moment")
                    || corpo.contains("verifique que voce e humano") || corpo.contains("verificacao de seguranca"))
                return new Desfecho(Status.PRECISA_USUARIO, "CAPTCHA/verificação em " + nome());
            if (d.findElements(By.cssSelector("input[type=password]")).stream().anyMatch(WebElement::isDisplayed))
                return new Desfecho(Status.PRECISA_USUARIO, "login necessário em " + nome());
            Optional<Desfecho> especial = casoEspecial(d, corpo);
            if (especial.isPresent()) return especial.get();

            Desfecho r = preencherCampos(d, contexto, cv, continuar);
            if (r != null) return r;

            WebElement envio = achar(d, botoesEnvio());
            // antes de abrir a candidatura, o botão de abertura vem primeiro: a página da vaga tem outros "Avançar" (galeria de fotos)
            WebElement abertura = !abriu && envio == null && !formularioAberto(d) ? achar(d, botoesAbertura()) : null;
            WebElement botao = envio != null ? envio : abertura != null ? abertura : achar(d, botoesAvanco());
            if (botao == null)
                return new Desfecho(Status.ERRO, "nenhum botão de avanço/envio encontrado (passo " + passo + ") · "
                        + LoginAutomatico.diagnostico(d) + " · botões visíveis: " + botoesVisiveis(d)
                        + " · captura=" + GupyDriver.capturar(d));
            if (envio != null && dryRun)
                return new Desfecho(Status.PRONTA_DRY_RUN, "dry-run: formulário pronto, não enviado");
            if (ehAbertura(d, botao)) {
                // o botão que abre o formulário de novo: o modal não tinha botão de avanço conhecido e voltaria ao início
                if (abriu) return new Desfecho(Status.PULADA, "formulário volta ao início (nenhum botão de avanço conhecido) · botões visíveis: "
                        + botoesVisiveis(d));
                abriu = true;
                if (!abrirFormulario(d))
                    return new Desfecho(Status.PULADA, "o botão de candidatura não abriu o formulário · botões visíveis: " + botoesVisiveis(d));
                pausa(1000);
                continue;
            }
            if (envio != null) clicouEnvio = true;
            // botão no rodapé de um modal pode estar fora da área visível: rola até ele antes de clicar
            ((JavascriptExecutor) d).executeScript(
                    "arguments[0].scrollIntoView({block:'center'});arguments[0].click()", botao);
            pausa(2500);
            String assinatura = d.getCurrentUrl() + "|" + d.findElement(By.tagName("body")).getText().hashCode();
            parados = assinatura.equals(anterior) ? parados + 1 : 0;
            anterior = assinatura;
            if (parados >= 3)
                return new Desfecho(Status.ERRO, "sem progresso: o botão não avança (campo obrigatório sem resposta?) · "
                        + LoginAutomatico.diagnostico(d) + " · botões visíveis: " + botoesVisiveis(d) + " · captura="
                        + GupyDriver.capturar(d) + " · texto: " + resumo(d));
        }
        return new Desfecho(Status.ERRO, "fluxo não terminou em 20 passos · botões visíveis: " + botoesVisiveis(d) + " · " + resumo(d));
    }

    private Desfecho preencherCampos(WebDriver d, String contexto, Cv cv, BooleanSupplier continuar) {
        for (WebElement el : d.findElements(By.cssSelector("input[type=text],input[type=number],input:not([type]),textarea"))) {
            try {
                if (!el.isDisplayed() || !el.isEnabled()) continue;
                String atual = el.getAttribute("value");
                if (atual != null && !atual.isBlank()) continue;
                String pergunta = rotulo(d, el);
                if (pergunta.isBlank() || ehBusca(pergunta)) continue;
                boolean area = "textarea".equalsIgnoreCase(el.getTagName());
                if (Texto.perguntaEmIngles(pergunta)) return new Desfecho(Status.PULADA, "vaga em inglês: " + pergunta);
                if (!perguntasDaVaga.add(pergunta))
                    return new Desfecho(Status.PULADA, "formulário reinicia e repete a pergunta: " + pergunta);
                var res = situacoes.resolver(nome(), pergunta, contexto, cv, area, limiteDoCampo(el, pergunta), continuar);
                switch (res.tipo()) {
                    case SENSIVEL -> {
                        return new Desfecho(Status.PRECISA_USUARIO, "dado sensível: " + pergunta);
                    }
                    case SEM_RESPOSTA -> {
                        return new Desfecho(Status.PRECISA_USUARIO, "sem resposta para: " + pergunta);
                    }
                    default -> {
                        String valor = ajustar(el, pergunta, res.resposta());
                        if (ehLocalidade(pergunta)) escolherLocalidade(d, el, valor);
                        else {
                            el.sendKeys(valor);
                            escolherSugestao(d, el);
                            caberNoContador(el, pergunta, valor);
                        }
                    }
                }
            } catch (org.openqa.selenium.StaleElementReferenceException e) {
                return null; // a página mudou; o laço principal reavalia
            }
        }
        return Radios.preencher(d, situacoes, nome(), contexto, cv, continuar, perguntasDaVaga);
    }

    /** Restrição do campo em linguagem para a IA: só número, e/ou máximo de caracteres; null quando não há. */
    static String limiteDoCampo(WebElement el, String pergunta) {
        return limite(campoNumerico(el), pergunta, String.valueOf(maxDoCampo(el)));
    }

    static String limite(boolean numerico, String pergunta, String maxlength) {
        StringBuilder sb = new StringBuilder();
        if (ehSalario(pergunta))
            sb.append("pretensão salarial: responda APENAS o valor mensal, sem R$, sem letras e sem texto (ex.: 10.000).");
        else if (ehNumerico(numerico, pergunta))
            sb.append("campo numérico: responda APENAS o número (anos: ex.: 5), sem a palavra anos, sem texto, unidade ou pontuação extra.");
        int max = maxlength(maxlength);
        if (max > 0 && sb.length() == 0)
            sb.append("no máximo ").append(max).append(" caracteres (contando espaços); escreva já neste tamanho, sem cortar frase no meio.");
        return sb.length() == 0 ? null : sb.toString();
    }

    /** type=number, inputmode numérico ou id "...-numeric" (LinkedIn): o campo só aceita algarismos. */
    private static boolean campoNumerico(WebElement el) {
        String tipo = String.valueOf(el.getAttribute("type")).toLowerCase(java.util.Locale.ROOT);
        String modo = String.valueOf(el.getAttribute("inputmode")).toLowerCase(java.util.Locale.ROOT);
        String id = String.valueOf(el.getAttribute("id")).toLowerCase(java.util.Locale.ROOT);
        return tipo.equals("number") || modo.equals("numeric") || modo.equals("decimal") || id.endsWith("-numeric");
    }

    /**
     * Tamanho máximo do campo: o maxlength ou, quando o portal só mostra um contador ("0/20" no LinkedIn), o número
     * depois da barra no texto perto do campo; 0 quando não há.
     */
    static int maxDoCampo(WebElement el) {
        int max = maxlength(el.getAttribute("maxlength"));
        if (max > 0 || !(el instanceof org.openqa.selenium.WrapsDriver w)) return max;
        try {
            Object r = ((JavascriptExecutor) w.getWrappedDriver()).executeScript(
                    "var p=arguments[0].parentElement;for(var i=0;i<4&&p;i++,p=p.parentElement){"
                            + "if(p.querySelectorAll('input,textarea,select').length>1)break;"
                            + "var m=(p.innerText||'').match(/(?:^|\\s)\\d+\\s*\\/\\s*(\\d+)(?:\\s|$)/);if(m)return m[1];}return null;", el);
            return r == null ? 0 : maxlength(r.toString());
        } catch (org.openqa.selenium.WebDriverException e) {
            return 0;
        }
    }

    static boolean ehSalario(String pergunta) {
        String n = Texto.normalizar(pergunta);
        return n.contains("salari") || n.contains("pretensao") || n.contains("remuneracao") || n.contains("salary")
                || n.contains("expectativa de ganho") || n.contains("compensation");
    }

    private static boolean ehNumerico(boolean numerico, String pergunta) {
        String n = Texto.normalizar(pergunta);
        return numerico || n.contains("quantos anos") || n.contains("anos de") || n.contains("anos com") || n.contains("em anos")
                || n.contains("tempo de experiencia") || n.contains("quanto tempo") || n.contains("how many years")
                || n.contains("years of") || n.contains("years with") || n.contains("in years") || n.startsWith("how long");
    }

    private static int maxlength(String maxlength) {
        try {
            return maxlength == null ? 0 : Integer.parseInt(maxlength.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Rede de segurança (a IA já recebe o limite): adapta a resposta ao campo: salário vira só o valor ("10.000", ou
     * "10000" em campo numérico); campo numérico recebe só o primeiro número; texto acima do tamanho máximo do campo é
     * cortado na última frase/palavra que cabe.
     */
    static String ajustar(WebElement el, String pergunta, String resposta) {
        int max = maxDoCampo(el);
        return ajustar(campoNumerico(el), pergunta, resposta, max > 0 ? String.valueOf(max) : null);
    }

    static String ajustar(boolean numerico, String pergunta, String resposta, String maxlength) {
        String r = resposta == null ? "" : resposta.trim();
        Long salario = ehSalario(pergunta) ? valorSalario(pergunta, r) : null;
        if (salario != null)
            r = numerico ? String.valueOf(salario) : String.format(java.util.Locale.forLanguageTag("pt-BR"), "%,d", salario);
        else if (ehNumerico(numerico, pergunta)) {
            var m = java.util.regex.Pattern.compile("\\d+(?:[.,]\\d+)?").matcher(r);
            if (m.find()) r = m.group().replace(',', '.');
            else r = "0"; // "não tenho experiência..." num campo que só aceita número
        }
        int max = maxlength(maxlength);
        if (max > 0 && r.length() > max) {
            String corte = r.substring(0, max);
            int fim = Math.max(corte.lastIndexOf(". "), corte.lastIndexOf('.') == max - 1 ? max - 1 : -1);
            if (fim > 0) corte = corte.substring(0, fim + 1);
            else if (corte.lastIndexOf(' ') > 0) corte = corte.substring(0, corte.lastIndexOf(' '));
            r = corte.trim();
        }
        return r;
    }

    private static final java.util.regex.Pattern VALOR = java.util.regex.Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,\\d{1,2})?\\s*(k|mil)?\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Valor mensal da resposta ("R$ 10.000,00/mês no modelo PJ" → 10000; "12k" → 12000). Se a pergunta cita PJ ou CLT
     * e a resposta traz os dois, vale o número que vem depois do regime perguntado. null quando não há valor.
     */
    static Long valorSalario(String pergunta, String resposta) {
        String n = Texto.normalizar(pergunta);
        String r = resposta.toLowerCase(java.util.Locale.ROOT);
        int inicio = 0;
        for (String regime : new String[] {"pj", "clt"}) {
            if (!(" " + n + " ").contains(" " + regime + " ")) continue;
            var m = java.util.regex.Pattern.compile("\\b" + regime + "\\b").matcher(r);
            if (m.find()) inicio = m.start();
        }
        var m = VALOR.matcher(r);
        if (!m.find(inicio) && !m.find(0)) return null;
        long v = Long.parseLong(m.group(1).replace(".", ""));
        if (m.group(2) != null) v *= 1000;
        return v;
    }

    /** O contador "78/20" só aparece depois de digitar: se o texto passou do limite, apaga e escreve a versão que cabe. */
    private static void caberNoContador(WebElement el, String pergunta, String valor) {
        int max = maxDoCampo(el);
        if (max <= 0 || valor.length() <= max) return;
        el.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.DELETE);
        el.sendKeys(ajustar(false, pergunta, valor, String.valueOf(max)));
    }

    static boolean ehLocalidade(String pergunta) {
        String n = Texto.normalizar(pergunta);
        return n.contains("cidade") || n.contains("localidade") || n.contains("location") || n.equals("city")
                || n.contains("(city)");
    }

    /**
     * Cidade com autocompletar: só vale a sugestão escolhida da lista ("Florianópolis, SC" digitado fica inválido).
     * Digita só o nome da cidade, tecla a tecla para disparar a busca, e clica na sugestão que contém a cidade.
     */
    private static void escolherLocalidade(WebDriver d, WebElement el, String valor) {
        String cidade = valor.split("[,\\-/]")[0].trim();
        el.clear();
        for (char c : cidade.toCharArray()) {
            el.sendKeys(String.valueOf(c));
            pausa(60);
        }
        String alvo = Texto.normalizar(cidade);
        for (int espera = 0; espera < 6; espera++) {
            pausa(700);
            for (WebElement o : d.findElements(By.cssSelector(
                    "[role=option],[role=listbox] li,.basic-typeahead__selectable,.search-typeahead-v2__hit"))) {
                try {
                    if (o.isDisplayed() && Texto.normalizar(o.getText()).contains(alvo)) {
                        o.click();
                        pausa(700);
                        return;
                    }
                } catch (org.openqa.selenium.WebDriverException e) {
                    // lista recriada enquanto digita: procura de novo
                }
            }
        }
        String auto = el.getAttribute("aria-autocomplete");
        if ("combobox".equals(el.getAttribute("role")) || (auto != null && !auto.isBlank() && !"none".equals(auto))) {
            el.sendKeys(org.openqa.selenium.Keys.ARROW_DOWN, org.openqa.selenium.Keys.ENTER); // lista sem texto legível: primeira sugestão
            pausa(700);
        } else { // campo de texto comum: Enter poderia enviar o formulário, então só preenche o valor inteiro
            el.clear();
            el.sendKeys(valor);
        }
    }

    /**
     * Campo com autocompletar (cidade/localidade do LinkedIn): o texto digitado só vale depois de escolher uma sugestão.
     * Clica na primeira opção da lista; sem lista visível, tenta seta para baixo + Enter.
     */
    private static void escolherSugestao(WebDriver d, WebElement el) {
        String role = String.valueOf(el.getAttribute("role"));
        String auto = el.getAttribute("aria-autocomplete");
        if (!"combobox".equals(role) && (auto == null || auto.isBlank() || "none".equals(auto))) return;
        pausa(1500);
        for (WebElement o : d.findElements(By.cssSelector("[role=listbox] [role=option],[role=option]"))) {
            try {
                if (o.isDisplayed()) {
                    o.click();
                    pausa(500);
                    return;
                }
            } catch (org.openqa.selenium.WebDriverException e) {
                // opção recriada: tenta a próxima
            }
        }
        el.sendKeys(org.openqa.selenium.Keys.ARROW_DOWN, org.openqa.selenium.Keys.ENTER);
        pausa(500);
    }

    /** Botões que abrem o formulário de candidatura (clicados uma vez por vaga); vazio = o portal não tem. */
    protected String[] botoesAbertura() {
        return new String[0];
    }

    /**
     * Clica no botão que abre a candidatura até o formulário aparecer: a página pode ter mais de um botão com o mesmo
     * texto (cabeçalho fixo e cartão da vaga) e o clique por JavaScript às vezes não abre o modal. Tenta cada um com
     * clique por JavaScript e depois clique real; se abrir em outra aba, passa para ela.
     */
    private boolean abrirFormulario(WebDriver d) {
        java.util.Set<String> abas = d.getWindowHandles();
        String url = d.getCurrentUrl();
        int campos = camposVisiveis(d);
        for (boolean real : new boolean[] {false, true}) {
            for (WebElement b : acharTodos(d, botoesAbertura())) {
                try {
                    ((JavascriptExecutor) d).executeScript("arguments[0].scrollIntoView({block:'center'})", b);
                    if (real) b.click();
                    else ((JavascriptExecutor) d).executeScript("arguments[0].click()", b);
                } catch (org.openqa.selenium.WebDriverException e) {
                    continue; // botão coberto ou recriado: tenta o próximo
                }
                for (int espera = 0; espera < 5; espera++) {
                    pausa(1000);
                    for (String aba : d.getWindowHandles())
                        if (!abas.contains(aba)) {
                            d.switchTo().window(aba);
                            return true;
                        }
                    if (!url.equals(d.getCurrentUrl()) || formularioAberto(d) || camposVisiveis(d) > campos) return true;
                }
            }
        }
        return false;
    }

    /** Formulário que abre na própria página (sem modal nem troca de URL): aparecem campos novos. */
    private static int camposVisiveis(WebDriver d) {
        Object n = ((JavascriptExecutor) d).executeScript("return Array.prototype.filter.call("
                + "document.querySelectorAll('input:not([type=hidden]),textarea,select'),function(e){return e.offsetParent!==null;}).length;");
        return n instanceof Number x ? x.intValue() : 0;
    }

    private static boolean formularioAberto(WebDriver d) {
        return d.findElements(By.cssSelector("dialog[open],[role=dialog],.artdeco-modal,.jobs-easy-apply-modal")).stream()
                .anyMatch(PortalWebDriver::visivel);
    }

    private static boolean visivel(WebElement e) {
        try {
            return e.isDisplayed();
        } catch (org.openqa.selenium.WebDriverException x) {
            return false;
        }
    }

    private boolean ehAbertura(WebDriver d, WebElement botao) {
        String rot = Texto.normalizar(String.valueOf(((JavascriptExecutor) d).executeScript(
                "return arguments[0].innerText||arguments[0].getAttribute('aria-label')||''", botao)));
        for (String t : botoesAbertura()) if (rot.equals(Texto.normalizar(t))) return true;
        return false;
    }

    private static boolean ehBusca(String rotulo) {
        String n = Texto.normalizar(rotulo);
        return n.equals("buscar") || n.contains("pesquis") || n.contains("search");
    }

    private static String rotulo(WebDriver d, WebElement el) {
        Object o = ((JavascriptExecutor) d).executeScript(
                "var e=arguments[0];var l=e.labels&&e.labels[0];if(l)return l.innerText;"
                        + "return e.getAttribute('aria-label')||e.getAttribute('placeholder')||"
                        + "(e.closest('label,div')?e.closest('label,div').innerText:'');", el);
        return o == null ? "" : o.toString().trim();
    }

    /** Fim do texto da página, para diagnosticar onde o fluxo travou. */
    private static String resumo(WebDriver d) {
        String t = d.findElement(By.tagName("body")).getText().replaceAll("\\s+", " ");
        return t.length() > 300 ? t.substring(t.length() - 300) : t;
    }

    /** Textos dos botões/links de ação visíveis, para diagnosticar uma página que o robô não entendeu. */
    private static String botoesVisiveis(WebDriver d) {
        List<String> out = new ArrayList<>();
        for (WebElement b : d.findElements(By.cssSelector("button,a.btn,input[type=submit],[role=button]"))) {
            try {
                String t = b.getText().trim();
                if (b.isDisplayed() && !t.isEmpty() && out.size() < 12) out.add(t.length() > 40 ? t.substring(0, 40) : t);
            } catch (org.openqa.selenium.WebDriverException ignored) {
                // elemento trocado durante a leitura
            }
        }
        return out.toString();
    }

    /** Todos os botões visíveis com um dos textos, na ordem dos textos. */
    @SuppressWarnings("unchecked")
    private static List<WebElement> acharTodos(WebDriver d, String[] textos) {
        List<WebElement> botoes = d.findElements(By.cssSelector("button,a,input[type=submit],[role=button]"));
        List<Object> rotulos = (List<Object>) ((JavascriptExecutor) d).executeScript(
                "return arguments[0].map(function(e){return e.offsetParent===null||e.disabled?null:"
                        + "(e.innerText||e.getAttribute('aria-label')||e.value||'');});", botoes);
        List<WebElement> out = new ArrayList<>();
        for (String t : textos) {
            String alvo = Texto.normalizar(t);
            for (int i = 0; i < botoes.size() && i < rotulos.size(); i++) {
                Object rot = rotulos.get(i);
                if (rot != null && Texto.normalizar(rot.toString()).equals(alvo) && !out.contains(botoes.get(i))) out.add(botoes.get(i));
            }
        }
        return out;
    }

    /**
     * Primeiro botão visível e habilitado cujo texto bate com um dos alvos (na ordem dada). Lê o texto de todos os
     * botões numa única chamada ao navegador: um getText por elemento fica lento demais em páginas com centenas de botões.
     */
    @SuppressWarnings("unchecked")
    private static WebElement achar(WebDriver d, String[] textos) {
        // com o formulário aberto num modal, só valem os botões dele: a página atrás tem outros "Avançar" (galeria de fotos)
        List<WebElement> modal = d.findElements(By.cssSelector("dialog[open],[role=dialog],.artdeco-modal")).stream()
                .filter(PortalWebDriver::visivel).toList();
        List<WebElement> botoes = new ArrayList<>();
        for (WebElement m : modal) botoes.addAll(m.findElements(By.cssSelector("button,a.btn,input[type=submit],[role=button]")));
        if (botoes.isEmpty()) botoes = d.findElements(By.cssSelector("button,a.btn,input[type=submit],[role=button]"));
        List<Object> rotulos = (List<Object>) ((JavascriptExecutor) d).executeScript(
                "return arguments[0].map(function(e){var r=e.offsetParent===null||e.disabled?null:"
                        + "(e.innerText||e.getAttribute('aria-label')||e.value||'');return r;});", botoes);
        for (String t : textos) {
            String alvo = Texto.normalizar(t);
            for (int i = 0; i < botoes.size() && i < rotulos.size(); i++) {
                Object rot = rotulos.get(i);
                if (rot != null && Texto.normalizar(rot.toString()).equals(alvo)) return botoes.get(i);
            }
        }
        return null;
    }

    protected static void pausa(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void fechar() {
        navegador.fechar();
    }
}
