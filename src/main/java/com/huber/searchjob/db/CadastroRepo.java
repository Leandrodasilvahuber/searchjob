package com.huber.searchjob.db;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Contas que o robô criou nos sites das empresas (vagas externas do Full search). A senha fica cifrada e é reusada na
 * próxima vaga do mesmo domínio, para entrar em vez de criar outra conta.
 */
@Repository
public class CadastroRepo {
    /** Linha para a tela: nunca carrega a senha. */
    public record Cadastro(long id, String empresa, String dominio, String link, String login, String metodo, String vaga,
                           String criadoEm) {}

    /** Conta em claro, para o robô entrar; {@code dominio} é onde ela foi criada (pode ser outro host da plataforma). */
    public record Conta(String login, String senha, String metodo, String dominio) {}

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final String MINUSCULAS = "abcdefghijkmnpqrstuvwxyz";
    private static final String MAIUSCULAS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITOS = "23456789";
    private static final String SIMBOLOS = "!@#$%*";

    private final JdbcClient jdbc;
    private final Cofre cofre;

    public CadastroRepo(JdbcClient jdbc, Cofre cofre) {
        this.jdbc = jdbc;
        this.cofre = cofre;
    }

    /** Senha forte de 14 caracteres com minúscula, maiúscula, número e símbolo (o que os formulários costumam exigir). */
    public static String senhaAleatoria() {
        String todos = MINUSCULAS + MAIUSCULAS + DIGITOS + SIMBOLOS;
        char[] s = new char[14];
        s[0] = MAIUSCULAS.charAt(ALEATORIO.nextInt(MAIUSCULAS.length()));
        s[1] = MINUSCULAS.charAt(ALEATORIO.nextInt(MINUSCULAS.length()));
        s[2] = DIGITOS.charAt(ALEATORIO.nextInt(DIGITOS.length()));
        s[3] = SIMBOLOS.charAt(ALEATORIO.nextInt(SIMBOLOS.length()));
        for (int i = 4; i < s.length; i++) s[i] = todos.charAt(ALEATORIO.nextInt(todos.length()));
        for (int i = s.length - 1; i > 0; i--) { // embaralha para os obrigatórios não ficarem sempre no início
            int j = ALEATORIO.nextInt(i + 1);
            char t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        return new String(s);
    }

    /** Domínio da conta: o host sem "www." (contas de um ATS como gupy/greenhouse valem para o host inteiro). */
    public static String dominio(String url) {
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Grava a conta; se o domínio já tem conta com o mesmo login, troca a senha (o site pediu uma nova). */
    public void salvar(String empresa, String link, String login, String senha, String metodo, String vaga) {
        String dom = dominio(link);
        String cifrada = senha == null || senha.isEmpty() ? "" : cofre.cifrar(senha);
        int alterou = jdbc.sql("UPDATE cadastro SET senha_cifrada = ?, metodo = ?, link = ? WHERE dominio = ? AND login = ?")
                .param(cifrada).param(metodo).param(link).param(dom).param(login).update();
        if (alterou == 0)
            jdbc.sql("INSERT INTO cadastro(empresa, dominio, link, login, senha_cifrada, metodo, vaga) VALUES (?,?,?,?,?,?,?)")
                    .param(empresa).param(dom).param(link).param(login).param(cifrada).param(metodo).param(vaga).update();
    }

    /**
     * Plataforma do domínio: o domínio registrável (solides.com.br para contatoseguro.vagas.solides.com.br), porque a
     * conta de um ATS vale em todos os subdomínios dele. No Workday cada empresa tem a própria conta: fica o host.
     */
    public static String plataforma(String dominio) {
        if (dominio.endsWith("myworkdayjobs.com")) return dominio;
        String[] p = dominio.split("\\.");
        int n = p.length >= 3 && p[p.length - 1].length() == 2 && SEGUNDO_NIVEL.contains(p[p.length - 2]) ? 3 : 2;
        return p.length <= n ? dominio : String.join(".", java.util.Arrays.copyOfRange(p, p.length - n, p.length));
    }

    private static final java.util.Set<String> SEGUNDO_NIVEL = java.util.Set.of("com", "net", "org", "gov", "edu", "co");

    /** Conta do site do link: a do mesmo host primeiro, senão a mais recente da mesma plataforma; vazio se não há. */
    public Optional<Conta> doSite(String link) {
        String dom = dominio(link);
        String base = plataforma(dom);
        return jdbc.sql("SELECT login, senha_cifrada, metodo, dominio FROM cadastro WHERE dominio = ? OR dominio = ? OR dominio LIKE ?"
                        + " ORDER BY dominio = ? DESC, id DESC LIMIT 1")
                .param(dom).param(base).param(base.equals(dom) ? dom : "%." + base).param(dom)
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}).optional()
                .map(c -> new Conta(c[0], c[1].isEmpty() ? "" : cofre.decifrar(c[1]), c[2], c[3]));
    }

    public List<Cadastro> todos() {
        return jdbc.sql("SELECT id, empresa, dominio, link, login, metodo, vaga, criado_em FROM cadastro ORDER BY id DESC")
                .query((rs, i) -> new Cadastro(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))).list();
    }

    /** Senha de uma conta, para o botão "mostrar" da tela; vazio se a conta entra pelo Google/LinkedIn. */
    public String senha(long id) {
        return jdbc.sql("SELECT senha_cifrada FROM cadastro WHERE id = ?").param(id).query(String.class).optional()
                .filter(s -> !s.isEmpty()).map(cofre::decifrar).orElse("");
    }

    public void excluir(long id) {
        jdbc.sql("DELETE FROM cadastro WHERE id = ?").param(id).update();
    }
}
