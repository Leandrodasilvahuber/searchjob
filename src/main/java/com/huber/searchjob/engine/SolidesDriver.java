package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.db.Cv;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Sólides: a listagem pública /vagas/&lt;termo&gt; vem renderizada no servidor (payload do Next.js embutido no HTML);
 * a candidatura exige conta (auth.vagas.solides.com.br) e segue o fluxo revisado até "Salvar e Finalizar".
 */
public class SolidesDriver extends PortalWebDriver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PUSH = Pattern.compile("self\\.__next_f\\.push\\(\\[1,\"(.*?)\"\\]\\)</script>", Pattern.DOTALL);
    private static final Pattern PAGINAS = Pattern.compile("\"totalPages\":(\\d+)");
    /** Mais páginas que isso trazem vagas antigas (a listagem vem das mais recentes para as mais antigas). */
    private static final int MAX_PAGINAS = 5;

    /** Habilidades cadastradas pelo candidato (config solides_habilidades_base, separadas por "|"). */
    static final String HABILIDADES_BASE = "python|java|react|javascript|django|centOS|postgreSQL|NGinx|Redis Elastic Cache"
            + "|.NET|Sistemas legados|Celery|AWS";
    /** A cada tantas vagas o perfil volta às habilidades da base: as exigidas por cada vaga vão sendo somadas e há limite. */
    static final int VAGAS_POR_LIMPEZA = 3;
    private static final String LIMPAR = """
            var base=arguments[0],vistos={},rem=[];
            document.querySelectorAll('button [data-icon=delete]').forEach(function(i){
              var b=i.closest('button'),l=b&&b.parentElement.querySelector('label');if(!l)return;
              var n=l.innerText.trim();
              if(base.indexOf(n)>=0&&!vistos[n])vistos[n]=1;else rem.push({b:b,n:n});});
            if(!Object.keys(vistos).length)return null; // lista não carregou (ou mudou de formato): não remove nada
            rem.reverse().forEach(function(x){x.b.click();});
            return rem.map(function(x){return x.n;});
            """;

    private final List<String> base;
    private final int recenciaDias;
    private boolean viuCurriculo;
    /** Vaga atual com candidatura só no site de outro sistema de RH (ver {@link #siteExterno}). */
    private boolean externa;
    private int vagasDesdeLimpeza = VAGAS_POR_LIMPEZA; // limpa antes da primeira vaga da sessão

    public SolidesDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        this(navegador, situacoes, HABILIDADES_BASE, 0);
    }

    /** @param recenciaDias vagas publicadas há mais dias que isso ficam fora da busca (0 = todas) */
    public SolidesDriver(NavegadorFirefox navegador, SituacaoService situacoes, String base, int recenciaDias) {
        super(navegador, situacoes);
        this.recenciaDias = recenciaDias;
        this.base = java.util.Arrays.stream(base.split("\\|")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    @Override
    public Desfecho candidatar(Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar) {
        viuCurriculo = false;
        externa = !vaga.url().startsWith("https://vagas.solides.com.br/");
        if (externa) return super.candidatar(vaga, cv, dryRun, continuar); // não passa pelo perfil da Sólides
        int antes = vagasDesdeLimpeza >= VAGAS_POR_LIMPEZA ? limpar() : -1;
        Desfecho r = super.candidatar(vaga, cv, dryRun, continuar);
        // pendência: o Orquestrador chama de novo para a mesma vaga e compara o motivo, então não conta nem anexa
        if (r.status() == Status.PRECISA_USUARIO) return r;
        int depois = ++vagasDesdeLimpeza >= VAGAS_POR_LIMPEZA ? limpar() : -1;
        int removidas = Math.max(antes, 0) + Math.max(depois, 0);
        if (antes < 0 && depois < 0) return r;
        return new Desfecho(r.status(), r.motivo() + " · perfil: " + removidas + " habilidade(s) fora da base removida(s)");
    }

    private int limpar() {
        vagasDesdeLimpeza = 0;
        return limparHabilidades().size();
    }

    /**
     * Remove do perfil as habilidades fora da base (e as repetidas) e salva. Devolve as removidas; vazia se não havia
     * o que remover ou se a página não carregou a lista.
     */
    @SuppressWarnings("unchecked")
    List<String> limparHabilidades() {
        try {
            WebDriver d = navegador.driver();
            d.get("https://perfil.vagas.solides.com.br/curriculo?aba=habilidades");
            Object r = null;
            for (int espera = 0; espera < 10 && r == null; espera++) {
                pausa(1500);
                r = ((JavascriptExecutor) d).executeScript(LIMPAR, base);
            }
            List<String> removidas = r == null ? List.of() : (List<String>) r;
            if (removidas.isEmpty()) return removidas;
            pausa(1000);
            ((JavascriptExecutor) d).executeScript("var b=[...document.querySelectorAll('button')]"
                    + ".find(function(e){return e.innerText.trim()==='Salvar currículo';});if(b)b.click();");
            pausa(3000);
            return removidas;
        } catch (org.openqa.selenium.WebDriverException e) {
            return List.of(); // limpeza é melhor esforço: a candidatura segue
        }
    }

    @Override
    public String nome() {
        return "Sólides";
    }

    @Override
    public List<Vaga> buscar(String termo, int max) throws Exception {
        List<Vaga> out = new ArrayList<>();
        String base = "https://vagas.solides.com.br/vagas/" + slug(termo);
        for (int pagina = 1, total = 1; pagina <= Math.min(total, MAX_PAGINAS) && out.size() < max; pagina++) {
            String url = pagina == 1 ? base : base + "?page=" + pagina;
            String payload = payload(get(url));
            Matcher t = PAGINAS.matcher(payload);
            // às vezes a página vem sem o bloco da listagem (nem "0 vagas"): tenta de novo antes de concluir que não há vagas
            for (int tentativa = 1; !t.find(0); tentativa++) {
                if (tentativa >= 3) throw new IllegalStateException("Sólides devolveu a página de \"" + termo + "\" sem a listagem de vagas");
                pausa(2000L * tentativa);
                payload = payload(get(url));
                t = PAGINAS.matcher(payload);
            }
            total = Integer.parseInt(t.group(1));
            // vaga antiga não entra (gastaria o limite de vagas novas do portal); página só de antigas encerra a busca
            LocalDate corte = recenciaDias > 0 ? LocalDate.now().minusDays(recenciaDias) : null;
            List<Vaga> lidas = parse(payload);
            List<Vaga> recentes = lidas.stream().filter(v -> corte == null || v.publicada() == null || !v.publicada().isBefore(corte)).toList();
            for (Vaga v : recentes) if (out.size() < max) out.add(v);
            if (!lidas.isEmpty() && recentes.isEmpty()) break;
            if (pagina < total) pausa(500);
        }
        out.sort(Vaga.MAIS_RECENTE);
        return out;
    }

    /** Igual ao portal: sem acento, espaços viram hífen e o resto vai codificado (".net" e "node.js" ficam como estão). */
    static String slug(String termo) {
        String s = java.text.Normalizer.normalize(termo, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .trim().replaceAll("\\s+", "-").replaceAll("-+", "-");
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Junta os pedaços do payload do Next.js (strings JSON em self.__next_f.push) num texto só. */
    static String payload(String html) throws Exception {
        StringBuilder sb = new StringBuilder();
        Matcher m = PUSH.matcher(html);
        while (m.find()) sb.append(JSON.readValue("\"" + m.group(1) + "\"", String.class));
        return sb.toString();
    }

    /** Cada vaga é um objeto JSON que começa em {"killerQuestions"; a descrição longa vem à parte, como referência "$1e". */
    static List<Vaga> parse(String payload) {
        List<Vaga> out = new ArrayList<>();
        for (int i = payload.indexOf("{\"killerQuestions\""); i >= 0; i = payload.indexOf("{\"killerQuestions\"", i + 1)) {
            JsonNode v;
            try (var p = JSON.createParser(payload.substring(i))) {
                v = p.readValueAsTree();
            } catch (Exception e) {
                continue; // objeto cortado no fim do payload
            }
            if (v == null || !v.has("id") || !v.has("title")) continue;
            String id = v.path("id").asText();
            String cidade = v.path("city").path("name").asText("");
            String uf = v.path("state").path("code").asText("");
            String local = cidade.isEmpty() ? uf : uf.isEmpty() ? cidade : cidade + " - " + uf;
            LocalDate pub = null;
            try {
                pub = LocalDate.parse(v.path("createdAt").asText(""));
            } catch (java.time.format.DateTimeParseException ignored) {
                // sem data: passa no filtro de recência
            }
            StringBuilder desc = new StringBuilder(Html.texto(texto(payload, v.path("description").asText(""))));
            for (String campo : new String[] {"hardSkills", "seniority", "recruitmentContractType", "language"})
                for (JsonNode n : v.path(campo)) desc.append(' ').append(n.path("name").asText(""));
            for (JsonNode q : v.path("killerQuestions")) desc.append(' ').append(q.path("question").asText(""));
            String site = siteExterno(v);
            out.add(new Vaga("Sólides", id, v.path("companyName").asText(""), v.path("title").asText("").trim(),
                    site != null ? site : "https://vagas.solides.com.br/vaga/" + id, local, v.path("jobType").asText(""), pub, desc.toString().trim()));
        }
        return out;
    }

    /**
     * Vaga publicada por outro sistema de RH (ex.: publishers.rhgestor.redirectUrl): a página dela na Sólides dá 404 e a
     * candidatura é só no site desse sistema; null quando a candidatura é pela Sólides.
     */
    static String siteExterno(JsonNode vaga) {
        for (JsonNode p : vaga.path("publishers")) {
            String url = p.path("redirectUrl").asText("");
            if (url.startsWith("http")) return url;
        }
        return null;
    }

    /**
     * Texto de uma referência "$1e" do payload: a linha "1e:T&lt;tamanho em hexa&gt;," seguida de tantos bytes UTF-8.
     * Valor que não é referência volta como está.
     */
    static String texto(String payload, String valor) {
        if (!valor.matches("\\$[0-9a-f]+")) return valor;
        Matcher m = Pattern.compile("(?<![0-9a-f])" + valor.substring(1) + ":T([0-9a-f]+),").matcher(payload);
        if (!m.find()) return "";
        byte[] resto = payload.substring(m.end()).getBytes(StandardCharsets.UTF_8);
        int n = Math.min(resto.length, Integer.parseInt(m.group(1), 16));
        return new String(resto, 0, n, StandardCharsets.UTF_8);
    }

    /** Perfil do candidato: deslogado, redireciona para o login. */
    @Override
    protected String paginaLogada() {
        return "https://perfil.vagas.solides.com.br/";
    }

    @Override
    protected String paginaLogin() {
        return "https://auth.vagas.solides.com.br/sign-in";
    }

    /**
     * O fluxo da candidatura passa pelas abas do currículo do perfil (perfil.vagas.solides.com.br/curriculo), já
     * preenchido: lá só valem as perguntas do recrutador (other.killerQuestions.N.text), cujo enunciado fica no item
     * da lista e não num rótulo. Os demais campos de texto (endereço, RG, busca de habilidade) são ignorados.
     */
    @Override
    protected String rotuloDoCampo(WebDriver d, WebElement el) {
        if (!String.valueOf(d.getCurrentUrl()).contains("perfil.vagas.solides.com.br")) return super.rotuloDoCampo(d, el);
        if (!String.valueOf(el.getAttribute("name")).startsWith("other.killerQuestions")) return "";
        Object q = ((JavascriptExecutor) d).executeScript(
                "var li=arguments[0].closest('li');if(!li)return '';var l=li.querySelector('label');"
                        + "return (l?l.innerText:li.innerText.split('\\n')[0])||'';", el);
        return q == null ? "" : q.toString().trim();
    }

    @Override
    protected Optional<Desfecho> casoEspecial(WebDriver d, String corpo) {
        if (externa) return Optional.of(new Desfecho(Status.PULADA, MOTIVO_EXTERNA + ": site da empresa"));
        if (String.valueOf(d.getCurrentUrl()).contains("/profiler") || corpo.contains("conclua o profiler"))
            return Optional.of(new Desfecho(Status.PRECISA_USUARIO, "Profiler pendente na Sólides: responda o teste comportamental"));
        // "Continuar" no aviso seria declarar que atende aos requisitos afirmativos: o robô não afirma isso pelo candidato.
        // O aviso abre alguns segundos depois do currículo carregar: na primeira passagem pelo currículo, espera por ele.
        boolean primeira = !viuCurriculo && String.valueOf(d.getCurrentUrl()).contains("perfil.vagas.solides.com.br/curriculo");
        if (primeira) viuCurriculo = true;
        for (int espera = 0; ; espera++) {
            if (corpo.contains("esta e uma vaga afirmativa"))
                return Optional.of(new Desfecho(Status.PULADA, "vaga afirmativa (pede declarar requisitos afirmativos)"));
            if (!primeira || espera >= 4) return Optional.empty();
            pausa(1000);
            corpo = Texto.normalizar(d.findElement(org.openqa.selenium.By.tagName("body")).getText());
        }
    }

    @Override
    protected String[] textosBloqueio() {
        // CPF já usado em outra conta e vaga exclusiva para PCD (avisos do próprio portal)
        return new String[] {"nao e possivel se candidatar", "vaga destinada apenas para pessoas pcd"};
    }

    /** "Candidatura completa" (revisada) e não a rápida: a rápida envia no clique, sem passar pelas perguntas nem pelo dry-run. */
    @Override
    protected String[] botoesAbertura() {
        return new String[] {"edit_document Candidatura completa", "Candidatura completa"};
    }

    @Override
    protected String[] botoesAvanco() {
        return new String[] {"Avançar", "Salvar e revisar", "Revisar"};
    }

    /**
     * Vaga com Sólides Start não tem a candidatura completa: só "Candidatar-se" (a rápida, que envia no clique; se a vaga
     * tem perguntas, o portal abre "Revisar" e segue o fluxo do currículo) e "Candidatura via WhatsApp".
     */
    @Override
    protected String[] botoesEnvio() {
        return new String[] {"Salvar e Finalizar", "rocket_launch Candidatar-se"};
    }

    @Override
    protected String[] textosJaCandidatou() {
        // "Candidatura realizada!" é o selo verde na página da vaga
        return new String[] {"sua candidatura foi realizada", "voce ja se candidatou", "candidatura ja realizada", "candidatura realizada"};
    }

    @Override
    protected String[] textosSucesso() {
        return new String[] {"candidatura efetuada com sucesso", "candidatura realizada com sucesso"};
    }
}
