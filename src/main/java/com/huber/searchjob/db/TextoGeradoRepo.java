package com.huber.searchjob.db;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Registro de cada texto que a IA escreveu sob medida para uma vaga (auditoria do que foi enviado). */
@Repository
public class TextoGeradoRepo {
    public record TextoGerado(long id, String portal, String pergunta, String vaga, String cv, String texto, String criadoEm) {}

    private final JdbcClient jdbc;

    public TextoGeradoRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void inserir(String portal, String pergunta, String vaga, String cv, String texto) {
        jdbc.sql("INSERT INTO texto_gerado(portal, pergunta, vaga, cv, texto) VALUES (?,?,?,?,?)")
                .param(portal).param(pergunta).param(vaga).param(cv).param(texto).update();
    }

    public List<TextoGerado> recentes() {
        return jdbc.sql("SELECT id, portal, pergunta, vaga, cv, texto, criado_em FROM texto_gerado ORDER BY id DESC LIMIT 200")
                .query((rs, i) -> new TextoGerado(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7))).list();
    }

    public void excluir(long id) {
        jdbc.sql("DELETE FROM texto_gerado WHERE id = ?").param(id).update();
    }
}
