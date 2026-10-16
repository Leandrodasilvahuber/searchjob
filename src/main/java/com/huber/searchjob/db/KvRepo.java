package com.huber.searchjob.db;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acesso às tabelas chave/valor: dado_fixo, preferencia e config. */
@Repository
public class KvRepo {
    public static final String DADO = "dado_fixo";
    public static final String PREF = "preferencia";
    public static final String CONFIG = "config";
    private static final Set<String> TABELAS = Set.of(DADO, PREF, CONFIG);
    private final JdbcClient jdbc;

    public KvRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Lança 404 se a tabela não é uma das três de chave/valor. */
    public static void valida(String tabela) {
        if (!TABELAS.contains(tabela))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    /** Valor numérico da configuração; usa o padrão se estiver vazio ou inválido (evita derrubar a sessão). */
    public int inteiro(String tabela, String chave, int padrao) {
        try {
            return Integer.parseInt(get(tabela, chave, String.valueOf(padrao)).trim());
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    /** Valor booleano da configuração ("true" liga); usa o padrão se a chave não existir. */
    public boolean booleano(String tabela, String chave, boolean padrao) {
        return Boolean.parseBoolean(get(tabela, chave, String.valueOf(padrao)).trim());
    }

    private static String t(String tabela) {
        if (!TABELAS.contains(tabela)) throw new IllegalArgumentException(tabela);
        return tabela;
    }

    public Map<String, String> todos(String tabela) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT chave, valor FROM " + t(tabela) + " ORDER BY chave")
                .query((rs, i) -> out.put(rs.getString(1), rs.getString(2)))
                .list();
        return out;
    }

    public String get(String tabela, String chave, String padrao) {
        return jdbc.sql("SELECT valor FROM " + t(tabela) + " WHERE chave = ?")
                .param(chave).query(String.class).optional().orElse(padrao);
    }

    public void put(String tabela, String chave, String valor) {
        jdbc.sql("INSERT INTO " + t(tabela) + "(chave, valor) VALUES (?, ?) "
                        + "ON CONFLICT(chave) DO UPDATE SET valor = excluded.valor")
                .param(chave).param(valor).update();
    }

    public void remover(String tabela, String chave) {
        jdbc.sql("DELETE FROM " + t(tabela) + " WHERE chave = ?").param(chave).update();
    }
}
