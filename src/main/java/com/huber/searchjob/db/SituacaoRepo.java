package com.huber.searchjob.db;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Situações aprendidas. status: ativa | inativa | pendente_confirmacao | aguardando_usuario.
 * origem: md | usuario | zai_confirmada | zai.
 */
@Repository
public class SituacaoRepo {
    public static final String ATIVA = "ativa";
    public static final String INATIVA = "inativa";
    public static final String PENDENTE_CONFIRMACAO = "pendente_confirmacao";
    public static final String AGUARDANDO_USUARIO = "aguardando_usuario";

    private static final String COLS = "id, portal, tipo, padrao_pergunta, hash, prompt_ou_resposta, origem, status, usos, contexto, criado_em";
    private static final RowMapper<Situacao> MAP = (rs, i) -> new Situacao(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getString(8), rs.getInt(9), rs.getString(10), rs.getString(11));

    private final JdbcClient jdbc;

    public SituacaoRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Situacao> todas() {
        return jdbc.sql("SELECT " + COLS + " FROM situacao ORDER BY id DESC").query(MAP).list();
    }

    public List<Situacao> porStatus(String... status) {
        return todas().stream().filter(s -> List.of(status).contains(s.status())).toList();
    }

    public List<Situacao> ativas() {
        return porStatus(ATIVA);
    }

    public Optional<Situacao> porId(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM situacao WHERE id = ?").param(id).query(MAP).optional();
    }

    /** Pendência ainda aberta (z.ai respondeu ou aguardando o usuário) para a mesma pergunta. */
    public Optional<Situacao> pendentePorHash(String hash) {
        return jdbc.sql("SELECT " + COLS + " FROM situacao WHERE hash = ? AND status IN ('pendente_confirmacao','aguardando_usuario') "
                        + "ORDER BY id DESC LIMIT 1").param(hash).query(MAP).optional();
    }

    public Optional<Situacao> ativaPorHash(String hash) {
        return jdbc.sql("SELECT " + COLS + " FROM situacao WHERE hash = ? AND status = 'ativa' ORDER BY id DESC LIMIT 1")
                .param(hash).query(MAP).optional();
    }

    public long inserir(String portal, String tipo, String padrao, String hash, String resposta,
                        String origem, String status, String contexto) {
        return jdbc.sql("INSERT INTO situacao(portal, tipo, padrao_pergunta, hash, prompt_ou_resposta, origem, status, contexto) "
                        + "VALUES (?,?,?,?,?,?,?,?) RETURNING id")
                .param(portal).param(tipo).param(padrao).param(hash).param(resposta)
                .param(origem).param(status).param(contexto).query(Long.class).single();
    }

    public void atualizar(long id, String padrao, String hash, String resposta, String status, String origem) {
        jdbc.sql("UPDATE situacao SET padrao_pergunta = ?, hash = ?, prompt_ou_resposta = ?, status = ?, origem = ? WHERE id = ?")
                .param(padrao).param(hash).param(resposta).param(status).param(origem).param(id).update();
    }

    public void definirStatus(long id, String status) {
        jdbc.sql("UPDATE situacao SET status = ? WHERE id = ?").param(status).param(id).update();
    }

    public void incrementarUso(long id) {
        jdbc.sql("UPDATE situacao SET usos = usos + 1 WHERE id = ?").param(id).update();
    }

    public void excluir(long id) {
        jdbc.sql("DELETE FROM situacao WHERE id = ?").param(id).update();
    }
}
