package com.huber.searchjob.engine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkedIn, só Candidatura simplificada. A busca usa a listagem pública (sem login, filtro f_AL); a candidatura abre a
 * vaga no Firefox já logado, clica em "Candidatura simplificada" e segue o formulário em etapas (Avançar → Revisar → Enviar).
 */
public class LinkedInDriver extends PortalWebDriver {
    private static final Pattern CARD = Pattern.compile("data-entity-urn=\"urn:li:jobPosting:(\\d+)\"");
    private static final Pattern TITULO = Pattern.compile("<h3 class=\"base-search-card__title\">(.*?)</h3>", Pattern.DOTALL);
    private static final Pattern EMPRESA = Pattern.compile("<h4 class=\"base-search-card__subtitle\">(.*?)</h4>", Pattern.DOTALL);
    private static final Pattern LOCAL = Pattern.compile("<span class=\"job-search-card__location\">(.*?)</span>", Pattern.DOTALL);
    private static final Pattern DATA = Pattern.compile("<time[^>]*datetime=\"(\\d{4}-\\d{2}-\\d{2})\"");
    private static final int POR_PAGINA = 10; // a listagem pública devolve 10 vagas por página
    /** Períodos da busca (f_TPR, em segundos), do mais curto ao mais longo: 24 h, 7 dias, 14 dias. */
    private static final int[] JANELAS = {86_400, 604_800, 1_209_600};

    public LinkedInDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        super(navegador, situacoes);
    }

    @Override
    public String nome() {
        return "LinkedIn";
    }

    @Override
    public List<Vaga> buscar(String termo, int max) throws Exception {
        // a busca pública não ordena por data (sortBy=DD é ignorado), mas o filtro de período (f_TPR) funciona: lê as
        // janelas de 24 h, 7 dias e 14 dias nessa ordem, para as mais novas entrarem antes do corte de 100, e ordena aqui
        List<Vaga> out = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        int limite = Math.max(max, 100);
        janelas:
        for (int segundos : JANELAS) {
            for (int inicio = 0; inicio < 100 && out.size() < limite; inicio += POR_PAGINA) {
                String html;
                try {
                    html = get("https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?keywords="
                            + URLEncoder.encode(termo, StandardCharsets.UTF_8) + "&geoId=106057199&f_AL=true&f_TPR=r" + segundos
                            + "&sortBy=DD&start=" + inicio);
                } catch (Exception e) {
                    if (out.isEmpty()) throw e;
                    break janelas; // página que falhou mesmo depois das novas tentativas: fica com as vagas já lidas
                }
                List<Vaga> pagina = parse(html, POR_PAGINA);
                for (Vaga v : pagina) if (ids.add(v.id())) out.add(v);
                if (pagina.isEmpty()) break;
            }
        }
        out.sort(Vaga.MAIS_RECENTE);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    static List<Vaga> parse(String html, int max) {
        List<Vaga> out = new ArrayList<>();
        Matcher m = CARD.matcher(html);
        List<Integer> inicios = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            inicios.add(m.start());
            ids.add(m.group(1));
        }
        for (int i = 0; i < ids.size() && out.size() < max; i++) {
            String bloco = html.substring(inicios.get(i), i + 1 < inicios.size() ? inicios.get(i + 1) : html.length());
            Matcher t = TITULO.matcher(bloco);
            if (!t.find()) continue;
            Matcher e = EMPRESA.matcher(bloco);
            Matcher l = LOCAL.matcher(bloco);
            Matcher d = DATA.matcher(bloco);
            String local = l.find() ? Html.texto(l.group(1)) : "";
            out.add(new Vaga("LinkedIn", ids.get(i), e.find() ? Html.texto(e.group(1)) : "", Html.texto(t.group(1)),
                    "https://www.linkedin.com/jobs/view/" + ids.get(i) + "/", local, local,
                    d.find() ? LocalDate.parse(d.group(1)) : null, ""));
        }
        return out;
    }

    private static final Pattern DESCRICAO = Pattern.compile("show-more-less-html__markup[^>]*>(.*?)</div>", Pattern.DOTALL);

    /** A busca não traz a descrição: lê da página pública da vaga, inteira (sem "ver mais"), para o filtro de idioma e a IA. */
    @Override
    public Vaga detalhar(Vaga v) {
        if (v.descricao() != null && !v.descricao().isBlank()) return v;
        try {
            Matcher m = DESCRICAO.matcher(get("https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/" + v.id()));
            if (!m.find()) return v;
            return new Vaga(v.portal(), v.id(), v.empresa(), v.cargo(), v.url(), v.local(), v.modalidade(), v.publicada(),
                    Html.texto(m.group(1)));
        } catch (Exception e) {
            return v; // sem a descrição, a checagem na página e nas perguntas ainda pega vaga em inglês
        }
    }

    @Override
    public boolean entrar(String usuario, String senha) {
        navegador.driver().get("https://www.linkedin.com/login");
        pausa(2000);
        return super.entrar(usuario, senha);
    }

    @Override
    protected String paginaLogada() {
        return "https://www.linkedin.com/feed/";
    }

    @Override
    protected String paginaLogin() {
        return "https://www.linkedin.com/login";
    }

    @Override
    protected String seletorDescricao() {
        return ".jobs-description__content,.jobs-box__html-content,#job-details,.jobs-description,.description__text";
    }

    @Override
    protected String[] botoesAvanco() {
        return new String[] {"Avançar", "Revisar", "Avaliar", "Próximo", "Continuar", "Next", "Review", "Candidatura simplificada", "Easy Apply"};
    }

    @Override
    protected String[] botoesAbertura() {
        return new String[] {"Candidatura simplificada", "Easy Apply"};
    }

    @Override
    protected String[] botoesEnvio() {
        return new String[] {"Enviar candidatura", "Submit application"};
    }

    @Override
    protected String[] textosSucesso() {
        return new String[] {"sua candidatura foi enviada", "candidatura enviada com sucesso", "application sent"};
    }

    @Override
    protected String[] textosJaCandidatou() {
        return new String[] {"candidatura enviada", "voce se candidatou", "candidatou-se", "applied "};
    }

    @Override
    protected String[] textosBloqueio() {
        return new String[] {"esta vaga nao esta mais aceitando candidaturas", "no longer accepting applications"};
    }
}
