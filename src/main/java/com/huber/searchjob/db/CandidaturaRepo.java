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
        // base criada antes da coluna descricao: acrescenta sem perder as candidaturas
        if (jdbc.sql("SELECT COUNT(*) FROM pragma_table_info('candidatura') WHERE name = 'descricao'").query(Integer.class).single() == 0)
            jdbc.sql("ALTER TABLE candidatura ADD COLUMN descricao TEXT").update();
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

    /**
     * URLs de vagas já tratadas (enviada/pulada). Em dry-run entram também as vagas já ensaiadas, para não
     * repetir o preenchimento (e as chamadas à IA) a cada ensaio.
     */
    public List<String> urlsProcessadas(boolean incluirDryRun) {
        return jdbc.sql("SELECT url FROM candidatura WHERE url IS NOT NULL AND status IN ('enviada','pulada'"
                        + (incluirDryRun ? ",'dry_run'" : "") + ")")
                .query(String.class).list();
    }

    /** Quantas vagas do portal já estão tratadas: a busca pede essa folga a mais para o limite valer só para vagas novas. */
    public int contarProcessadas(String portal, boolean incluirDryRun) {
        return jdbc.sql("SELECT COUNT(*) FROM candidatura WHERE portal = ? AND status IN ('enviada','pulada'"
                        + (incluirDryRun ? ",'dry_run'" : "") + ")")
                .param(portal).query(Integer.class).single();
    }

    /**
     * Grava o resultado de uma vaga sem duplicar linhas: se já existe uma linha em aberto
     * (pendente, erro, dry_run, aguardando_usuario) para a mesma URL, atualiza; senão insere.
     */
    public long gravar(String portal, String empresa, String cargo, String url, String cv, String status, String motivo) {
        return gravar(portal, empresa, cargo, url, cv, status, motivo, null);
    }

    /** Igual ao anterior, guardando também a descrição da vaga (a que a IA leu); null mantém a já gravada. */
    public long gravar(String portal, String empresa, String cargo, String url, String cv, String status, String motivo,
                       String descricao) {
        long id = gravarSemDescricao(portal, empresa, cargo, url, cv, status, motivo);
        if (descricao != null && !descricao.isBlank())
            jdbc.sql("UPDATE candidatura SET descricao = ? WHERE id = ?").param(descricao).param(id).update();
        return id;
    }

    private long gravarSemDescricao(String portal, String empresa, String cargo, String url, String cv, String status, String motivo) {
        var aberta = jdbc.sql("SELECT id FROM candidatura WHERE url = ? AND status IN ('pendente','erro','dry_run','aguardando_usuario') "
                + "ORDER BY id DESC LIMIT 1").param(url).query(Long.class).optional();
        if (aberta.isPresent()) {
            jdbc.sql("UPDATE candidatura SET status = ?, motivo = ?, cv_usado = COALESCE(?, cv_usado), data = date('now') WHERE id = ?")
                    .param(status).param(motivo).param(cv).param(aberta.get()).update();
            return aberta.get();
        }
        return inserir(portal, empresa, cargo, url, cv, status, motivo, null);
    }

    public long inserir(String portal, String empresa, String cargo, String url, String cv,
                        String status, String motivo, String data) {
        return jdbc.sql("INSERT INTO candidatura(portal, empresa, cargo, url, cv_usado, status, motivo, data) "
                        + "VALUES (?,?,?,?,?,?,?, COALESCE(?, date('now'))) RETURNING id")
                .param(portal).param(empresa).param(cargo).param(url).param(cv)
                .param(status).param(motivo).param(data).query(Long.class).single();
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
