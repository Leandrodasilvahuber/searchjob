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
    private static final int POR_PAGINA = 25;

    public LinkedInDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        super(navegador, situacoes);
    }

    @Override
    public String nome() {
        return "LinkedIn";
    }

    @Override
    public List<Vaga> buscar(String termo, int max) throws Exception {
        List<Vaga> out = new ArrayList<>();
        for (int inicio = 0; out.size() < max && inicio < 100; inicio += POR_PAGINA) {
            String html = get("https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?keywords="
                    + URLEncoder.encode(termo, StandardCharsets.UTF_8) + "&geoId=106057199&f_AL=true&f_TPR=r1209600&start=" + inicio);
            List<Vaga> pagina = parse(html, max - out.size());
            if (pagina.isEmpty()) break;
            out.addAll(pagina);
        }
        return out;
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

    @Override
    public boolean entrar(String usuario, String senha) {
        navegador.driver().get("https://www.linkedin.com/login");
        pausa(2000);
        return super.entrar(usuario, senha);
    }

    @Override
    protected String[] botoesAvanco() {
        return new String[] {"Avançar", "Revisar", "Próximo", "Continuar", "Next", "Review", "Candidatura simplificada", "Easy Apply"};
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
