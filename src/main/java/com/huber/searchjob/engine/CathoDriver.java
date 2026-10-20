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
    private static final Pattern JSON_LD = Pattern.compile("<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL);
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
        // a Catho devolve vagas sem relação com o termo: o card casando basta; senão lê a descrição completa da vaga
        List<Vaga> out = new ArrayList<>();
        List<Vaga> lidas = new ArrayList<>(parse(get("https://www.catho.com.br/vagas/" + slug + "/?order=dataAtualizacao"), Integer.MAX_VALUE, LocalDate.now()));
        lidas.sort(Vaga.MAIS_RECENTE); // order=dataAtualizacao já traz as mais novas; vagas em destaque vêm no topo fora de ordem
        for (Vaga v : lidas) {
            if (out.size() >= max) break;
            if (mencionaTermo(v.cargo() + " " + v.descricao(), termo) || mencionaTermo(descricaoCompleta(v.url()), termo))
                out.add(v);
        }
        return out;
    }

    /** Descrição completa vem no JSON-LD (JobPosting) da página da vaga; falha de rede conta como "sem descrição". */
    private String descricaoCompleta(String url) {
        try {
            pausa(300);
            return descricaoDoJsonLd(get(url));
        } catch (Exception e) {
            return "";
        }
    }

    static String descricaoDoJsonLd(String html) {
        Matcher m = JSON_LD.matcher(html);
        while (m.find()) {
            try {
                var no = new com.fasterxml.jackson.databind.ObjectMapper().readTree(m.group(1));
                if ("JobPosting".equals(no.path("@type").asText())) return no.path("description").asText("");
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // bloco JSON-LD de outro tipo ou malformado: tenta o próximo
            }
        }
        return "";
    }

    /**
     * Cada palavra do termo original (".net", "c#") precisa aparecer inteira no título/card. A borda só é exigida
     * onde o termo começa/termina em letra ou dígito: ".net" casa em "Desenvolvedor.NET", mas "c#" não casa em "Categoria C".
     */
    static boolean mencionaTermo(String texto, String termo) {
        String alvo = semAcento(texto);
        for (String palavra : semAcento(termo).strip().split("\\s+")) {
            if (palavra.isEmpty()) continue;
            String antes = Character.isLetterOrDigit(palavra.codePointAt(0)) ? "(?<![\\p{L}\\p{N}])" : "";
            String depois = Character.isLetterOrDigit(palavra.codePointBefore(palavra.length())) ? "(?![\\p{L}\\p{N}])" : "";
            if (!Pattern.compile(antes + Pattern.quote(palavra) + depois).matcher(alvo).find()) return false;
        }
        return true;
    }

    private static String semAcento(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase();
    }

    static List<Vaga> parse(String html, int max, LocalDate hoje) {
        List<Vaga> out = new ArrayList<>();
        Matcher a = ARTIGO.matcher(html);
        while (a.find() && out.size() < max) {
            String art = a.group();
            Matcher l = LINK.matcher(art);
            if (!l.find()) continue;
            Matcher e = EMPRESA.matcher(art);
            Matcher lo = LOCAL.matcher(art);
            Matcher dt = DATA.matcher(art);
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

    /** Deslogado, a área do candidato redireciona para a home. */
    @Override
    protected String paginaLogada() {
        return "https://www.catho.com.br/area-candidato/";
    }

    @Override
    protected String paginaLogin() {
        return "https://www.catho.com.br/signin/";
    }

    @Override
    protected String[] botoesAvanco() {
        return new String[] {"Continuar", "Próximo", "Avançar"};
    }

    @Override
    protected String[] botoesEnvio() {
        return new String[] {"Quero me candidatar", "Enviar currículo", "Enviar meu currículo", "Enviar candidatura"};
    }

    @Override
    protected String[] textosJaCandidatou() {
        return new String[] {"voce ja se candidatou", "candidatura ja enviada", "ja se candidatou", "cv enviado"};
    }

    @Override
    protected String[] textosSucesso() {
        return new String[] {"candidatura enviada", "curriculo enviado", "cv enviado", "voce se candidatou", "candidatura realizada"};
    }
}
