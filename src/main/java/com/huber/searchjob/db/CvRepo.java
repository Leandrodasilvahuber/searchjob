package com.huber.searchjob.db;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CvRepo {
    private final JdbcClient jdbc;

    public CvRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Cv> todos() {
        return jdbc.sql("SELECT id, nome, arquivo_pdf, texto_extraido, regras_stack FROM cv ORDER BY nome")
                .query((rs, i) -> new Cv(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
    }

    public Optional<Cv> porId(long id) {
        return todos().stream().filter(c -> c.id() == id).findFirst();
    }

    public Optional<Cv> porNome(String nome) {
        return todos().stream().filter(c -> c.nome().equals(nome)).findFirst();
    }

    public void salvar(String nome, String arquivoPdf, String texto, String regras) {
        jdbc.sql("INSERT INTO cv(nome, arquivo_pdf, texto_extraido, regras_stack) VALUES (?,?,?,?) "
                        + "ON CONFLICT(nome) DO UPDATE SET arquivo_pdf = excluded.arquivo_pdf, "
                        + "texto_extraido = excluded.texto_extraido, regras_stack = excluded.regras_stack")
                .param(nome).param(arquivoPdf).param(texto).param(regras).update();
    }

    public void atualizarRegras(long id, String regras, String texto) {
        jdbc.sql("UPDATE cv SET regras_stack = ?, texto_extraido = ? WHERE id = ?")
                .param(regras).param(texto).param(id).update();
    }

    public void excluir(long id) {
        jdbc.sql("DELETE FROM cv WHERE id = ?").param(id).update();
    }
}
