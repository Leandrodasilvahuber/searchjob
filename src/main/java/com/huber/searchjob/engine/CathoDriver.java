package com.huber.searchjob.engine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catho: listagem pública em /vagas/<termo>/; candidatura pelo botão "Quero me candidatar" (ignora planos pagos). */
public class CathoDriver extends PortalWebDriver {
    private static final Pattern ARTIGO = Pattern.compile("<article class=\"offer.*?</article>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<a href=\"(/vagas/[^\"/]+/(\\d+))\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern EMPRESA = Pattern.compile("<span class=\"text-12\">(.*?)</span>", Pattern.DOTALL);
    private static final Pattern LOCAL = Pattern.compile("</strong>\\s*-\\s*([^<]+)<", Pattern.DOTALL);
    private static final Pattern DATA = Pattern.compile("Publicada em (\\d{2})/(\\d{2})");

    public CathoDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        super(navegador, situacoes);
    }

    @Override
    public String nome() {
        return "Catho";
    }

    @Override
    public List<Vaga> buscar(String termo, int max) throws Exception {
        String slug = URLEncoder.encode(Texto.normalizar(termo).replace(' ', '-'), StandardCharsets.UTF_8);
        return parse(get("https://www.catho.com.br/vagas/" + slug + "/"), max, LocalDate.now());
    }

    static List<Vaga> parse(String html, int max, LocalDate hoje) {
        List<Vaga> out = new ArrayList<>();
        Matcher a = ARTIGO.matcher(html);
        while (a.find() && out.size() < max) {
            String art = a.group();
            Matcher l = LINK.matcher(art);
            if (!l.find()) continue;
            Matcher e = EMPRESA.matcher(art), lo = LOCAL.matcher(art), dt = DATA.matcher(art);
            LocalDate pub = null;
            if (dt.find()) { // a Catho só mostra dia/mês: se cair no futuro, é do ano passado
                pub = LocalDate.of(hoje.getYear(), Integer.parseInt(dt.group(2)), Integer.parseInt(dt.group(1)));
                if (pub.isAfter(hoje)) pub = pub.minusYears(1);
            }
            String local = lo.find() ? Html.texto(lo.group(1)) : "";
            out.add(new Vaga("Catho", l.group(2), e.find() ? Html.texto(e.group(1)) : "", Html.texto(l.group(3)),
                    "https://www.catho.com.br" + l.group(1), local, "", pub, Html.texto(art)));
        }
        return out;
    }

    @Override
    protected String[] botoesAvanco() {
        return new String[] {"Continuar", "Próximo", "Avançar"};
    }

    @Override
    protected String[] botoesEnvio() {
        return new String[] {"Quero me candidatar", "Enviar currículo", "Enviar candidatura"};
    }

    @Override
    protected String[] textosSucesso() {
        return new String[] {"candidatura enviada", "curriculo enviado", "voce se candidatou", "candidatura realizada"};
    }
}
