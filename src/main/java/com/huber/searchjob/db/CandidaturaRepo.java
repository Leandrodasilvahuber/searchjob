package com.huber.searchjob.db;

import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** status: enviada | pulada | erro | pendente | aguardando_usuario. */
@Repository
public class CandidaturaRepo {
    private static final RowMapper<Candidatura> MAP = (rs, i) -> new Candidatura(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getString(8), rs.getString(9));
    private static final String COLS = "id, portal, empresa, cargo, url, cv_usado, status, motivo, data";

    private final JdbcClient jdbc;

    public CandidaturaRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidatura> listar(String portal, String status) {
        return jdbc.sql("SELECT " + COLS + " FROM candidatura WHERE (? = '' OR portal = ?) AND (? = '' OR status = ?) "
                        + "ORDER BY data DESC, id DESC LIMIT 1000")
                .param(portal).param(portal).param(status).param(status).query(MAP).list();
    }

    /** Já existe registro final (enviada/pulada) para essa URL? Evita candidatura duplicada. */
    public boolean jaProcessada(String url) {
        return jdbc.sql("SELECT COUNT(*) FROM candidatura WHERE url = ? AND status IN ('enviada','pulada')")
                .param(url).query(Integer.class).single() > 0;
    }

    public long inserir(String portal, String empresa, String cargo, String url, String cv,
                        String status, String motivo, String data) {
        jdbc.sql("INSERT INTO candidatura(portal, empresa, cargo, url, cv_usado, status, motivo, data) "
                        + "VALUES (?,?,?,?,?,?,?, COALESCE(?, date('now')))")
                .param(portal).param(empresa).param(cargo).param(url).param(cv)
                .param(status).param(motivo).param(data).update();
        return jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
    }

    public void atualizarStatus(long id, String status, String motivo) {
        jdbc.sql("UPDATE candidatura SET status = ?, motivo = ? WHERE id = ?")
                .param(status).param(motivo).param(id).update();
    }

    public void excluir(long id) {
        jdbc.sql("DELETE FROM candidatura WHERE id = ?").param(id).update();
    }

    public long contar() {
        return jdbc.sql("SELECT COUNT(*) FROM candidatura").query(Long.class).single();
    }
}
