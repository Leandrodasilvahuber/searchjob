package com.huber.searchjob.db;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Portais e o modo de login de cada um (Google, LinkedIn ou e-mail e senha do próprio portal). */
@Repository
public class PortalRepo {
    public static final String GOOGLE = "google";
    public static final String LINKEDIN = "linkedin";
    public static final String PORTAL = "portal";
    public static final Set<String> MODOS = Set.of(GOOGLE, LINKEDIN, PORTAL);

    public record Portal(String chave, String nome, String modoLogin) {}

    private final JdbcClient jdbc;

    public PortalRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Cria os portais que faltam com o modo que entrou ao vivo em cada um; não mexe nos já configurados. */
    public void semear() {
        Object[][] padrao = {
            {"gupy", "Gupy", GOOGLE}, {"linkedin", "LinkedIn", PORTAL}, {"solides", "Sólides", LINKEDIN},
            {"indeed", "Indeed", GOOGLE}, {"catho", "Catho", GOOGLE}, {"programathor", "ProgramaThor", PORTAL}};
        for (int i = 0; i < padrao.length; i++)
            jdbc.sql("INSERT OR IGNORE INTO portal(chave, nome, modo_login, ordem) VALUES (?,?,?,?)")
                    .param(padrao[i][0]).param(padrao[i][1]).param(padrao[i][2]).param(i).update();
    }

    public List<Portal> todos() {
        return jdbc.sql("SELECT chave, nome, modo_login FROM portal ORDER BY ordem, nome")
                .query((rs, i) -> new Portal(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    /** Modo de login do portal (aceita o nome de exibição, ex. "Sólides"); Google se o portal não está cadastrado. */
    public String modo(String portal) {
        return jdbc.sql("SELECT modo_login FROM portal WHERE chave = ?").param(chave(portal))
                .query(String.class).optional().filter(MODOS::contains).orElse(GOOGLE);
    }

    public void definirModo(String portal, String modo) {
        if (!MODOS.contains(modo)) throw new IllegalArgumentException("modo de login inválido: " + modo);
        jdbc.sql("UPDATE portal SET modo_login = ? WHERE chave = ?").param(modo).param(chave(portal)).update();
    }

    /** Ordem das tentativas de login: o modo escolhido primeiro e depois os outros (Google → LinkedIn → portal). */
    public static List<String> ordemLogin(String modo) {
        List<String> ordem = new ArrayList<>(List.of(GOOGLE, LINKEDIN, PORTAL));
        if (ordem.remove(modo)) ordem.add(0, modo);
        return ordem;
    }

    static String chave(String portal) {
        return Normalizer.normalize(portal.trim().toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
