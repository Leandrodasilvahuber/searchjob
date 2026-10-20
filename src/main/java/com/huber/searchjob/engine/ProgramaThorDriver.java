package com.huber.searchjob.engine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ProgramaThor: listagem pública em /jobs-<termo>; a candidatura exige login e usa o botão "Quero me candidatar". */
public class ProgramaThorDriver extends PortalWebDriver {
    private static final Pattern INICIO = Pattern.compile("<a href=\"(/jobs/[^\"]+)\">");
    private static final Pattern TITULO = Pattern.compile("<h3[^>]*>(.*?)</h3>", Pattern.DOTALL);
    private static final Pattern SPAN = Pattern.compile("<span><i class='[^']*'></i>([^<]*)</span>");

    public ProgramaThorDriver(NavegadorFirefox navegador, SituacaoService situacoes) {
        super(navegador, situacoes);
    }

    @Override
    public String nome() {
        return "ProgramaThor";
    }

    @Override
    public List<Vaga> buscar(String termo, int max) throws Exception {
        String slug = URLEncoder.encode(Texto.normalizar(termo).replace(' ', '-'), StandardCharsets.UTF_8);
        // a listagem não traz data e mistura vagas em destaque antigas: o id é sequencial, então id maior = mais nova
        List<Vaga> out = new ArrayList<>(parse(get("https://programathor.com.br/jobs-" + slug), Integer.MAX_VALUE));
        out.sort(Comparator.comparingLong((Vaga v) -> numero(v.id())).reversed());
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static long numero(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return 0; // id fora do padrão vai para o fim
        }
    }

    static List<Vaga> parse(String html, int max) {
        List<Vaga> out = new ArrayList<>();
        Matcher m = INICIO.matcher(html);
        List<int[]> pos = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        while (m.find()) {
            pos.add(new int[] {m.end()});
            paths.add(m.group(1));
        }
        for (int i = 0; i < paths.size() && out.size() < max; i++) {
            int fim = i + 1 < pos.size() ? pos.get(i + 1)[0] : html.length();
            String bloco = html.substring(pos.get(i)[0], fim);
            Matcher t = TITULO.matcher(bloco);
            if (!t.find()) continue; // links de menu/rodapé para /jobs não têm card
            List<String> spans = new ArrayList<>();
            Matcher s = SPAN.matcher(bloco);
            while (s.find()) spans.add(Html.texto(s.group(1)));
            String path = paths.get(i);
            String id = path.replaceAll("^/jobs/(\\d+).*", "$1");
            String empresa = spans.size() > 0 ? spans.get(0) : "";
            String local = spans.size() > 1 ? spans.get(1) : "";
            String regime = spans.size() > 4 ? spans.get(4) : "";
            // a listagem não traz data de publicação: fica nula e passa no filtro de recência
            out.add(new Vaga("ProgramaThor", id, empresa, Html.texto(t.group(1)),
                    "https://programathor.com.br" + path, local, local, null, regime + " " + Html.texto(bloco)));
        }
        return out;
    }

    /**
     * Na vaga deslogada o botão "Quero me candidatar" leva ao CADASTRO (/users/sign_up, com reCAPTCHA), não ao login.
     * Por isso o login automático abre antes a página de login de verdade.
     */
    @Override
    public boolean entrar(String usuario, String senha) {
        navegador.driver().get("https://programathor.com.br/users/sign_in");
        pausa(1500);
        return super.entrar(usuario, senha);
    }

    /** Edição da conta: deslogado, redireciona para /users/sign_in. */
    @Override
    protected String paginaLogada() {
        return "https://programathor.com.br/users/edit";
    }

    @Override
    protected String paginaLogin() {
        return "https://programathor.com.br/users/sign_in";
    }

    @Override
    protected String[] textosBloqueio() {
        // ex.: "Você não pode se candidatar: esta vaga requer habilidade(s) que você não possui."
        // a segunda frase é a página de erro 500 do próprio portal (algumas vagas quebram até logado)
        return new String[] {"voce nao pode se candidatar", "ha algo de errado com essa pagina"};
    }

    @Override
    protected String[] botoesAvanco() {
        return new String[] {"Continuar", "Próximo"};
    }

    @Override
    protected String[] botoesEnvio() {
        return new String[] {"Quero me candidatar", "Candidatar-se", "Candidatar", "Enviar candidatura"};
    }

    @Override
    protected String[] textosSucesso() {
        return new String[] {"candidatura enviada", "voce se candidatou com sucesso", "candidatura realizada"};
    }
}
