package com.huber.searchjob.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provedores de IA do fallback (API compatível com OpenAI), na ordem em que são tentados. A chave fica cifrada no banco
 * (mesmo cofre das senhas dos portais) e nunca volta para a tela.
 */
@Repository
public class IaProvedorRepo {
    private static final Logger log = LoggerFactory.getLogger(IaProvedorRepo.class);

    /** Provedor com a chave em claro, para o cliente de IA. */
    public record Provedor(long id, String nome, String url, String chave, List<String> modelos, int ordem, boolean ativo,
                           int intervaloMs, int timeoutS, int tentativas) {
        /** Entra no fallback: ativo, com chave, URL e ao menos um modelo. */
        public boolean utilizavel() {
            return ativo && !chave.isBlank() && !url.isBlank() && !modelos.isEmpty();
        }
    }

    /** Linha para a tela: só diz se a chave existe. */
    public record Resumo(long id, String nome, String url, boolean temChave, String modelos, int ordem, boolean ativo,
                         int intervaloMs, int timeoutS, int tentativas) {}

    private final JdbcClient jdbc;
    private final Cofre cofre;

    public IaProvedorRepo(JdbcClient jdbc, Cofre cofre) {
        this.jdbc = jdbc;
        this.cofre = cofre;
    }

    private static final String COLUNAS = "id, nome, url, chave_cifrada, modelos, ordem, ativo, intervalo_ms, timeout_s, tentativas";

    /** Todos, na ordem do fallback. */
    public List<Provedor> todos() {
        return jdbc.sql("SELECT " + COLUNAS + " FROM ia_provedor ORDER BY ordem, id")
                .query((rs, i) -> new Provedor(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4).isEmpty() ? "" : cofre.decifrar(rs.getString(4)), modelos(rs.getString(5)),
                        rs.getInt(6), rs.getInt(7) == 1, rs.getInt(8), rs.getInt(9), rs.getInt(10)))
                .list();
    }

    public List<Resumo> resumos() {
        return jdbc.sql("SELECT " + COLUNAS + " FROM ia_provedor ORDER BY ordem, id")
                .query((rs, i) -> new Resumo(rs.getLong(1), rs.getString(2), rs.getString(3), !rs.getString(4).isEmpty(),
                        rs.getString(5), rs.getInt(6), rs.getInt(7) == 1, rs.getInt(8), rs.getInt(9), rs.getInt(10)))
                .list();
    }

    /**
     * Cria (id 0) ou atualiza o provedor. Chave em branco mantém a atual; "-" apaga a chave.
     */
    @Transactional
    public void salvar(long id, String nome, String url, String chave, String modelos, int ordem, boolean ativo,
                       int intervaloMs, int timeoutS, int tentativas) {
        if (nome.isBlank() || url.isBlank()) throw new IllegalArgumentException("Informe nome e URL");
        String m = String.join(",", modelos(modelos));
        if (id == 0) {
            jdbc.sql("INSERT INTO ia_provedor(nome, url, chave_cifrada, modelos, ordem, ativo, intervalo_ms, timeout_s, tentativas) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)")
                    .param(nome.trim()).param(url.trim()).param(cifrar(chave)).param(m).param(ordem).param(ativo ? 1 : 0)
                    .param(Math.max(0, intervaloMs)).param(Math.max(1, timeoutS)).param(Math.max(1, tentativas)).update();
            return;
        }
        jdbc.sql("UPDATE ia_provedor SET nome = ?, url = ?, modelos = ?, ordem = ?, ativo = ?, intervalo_ms = ?, timeout_s = ?, "
                        + "tentativas = ? WHERE id = ?")
                .param(nome.trim()).param(url.trim()).param(m).param(ordem).param(ativo ? 1 : 0)
                .param(Math.max(0, intervaloMs)).param(Math.max(1, timeoutS)).param(Math.max(1, tentativas)).param(id).update();
        if (!chave.isBlank())
            jdbc.sql("UPDATE ia_provedor SET chave_cifrada = ? WHERE id = ?").param(cifrar(chave)).param(id).update();
    }

    /** Sobe para {@code segundos} o prazo por chamada dos provedores que estão abaixo dele. */
    public void prazoMinimo(int segundos) {
        jdbc.sql("UPDATE ia_provedor SET timeout_s = ? WHERE timeout_s < ?").param(segundos).param(segundos).update();
    }

    public void ativar(long id, boolean ativo) {
        jdbc.sql("UPDATE ia_provedor SET ativo = ? WHERE id = ?").param(ativo ? 1 : 0).param(id).update();
    }

    public void excluir(long id) {
        jdbc.sql("DELETE FROM ia_provedor WHERE id = ?").param(id).update();
    }

    /**
     * Tabela vazia: cria OpenRouter, Z.ai e xAI, trazendo URL, chave, modelos e prazos que estavam na config
     * (openrouter_*, zai_*, fallback_zai) e apagando essas chaves de lá.
     */
    @Transactional
    public void semear(KvRepo kv) {
        if (jdbc.sql("SELECT COUNT(*) FROM ia_provedor").query(Integer.class).single() > 0) return;
        String zaiChave = kv.get(KvRepo.CONFIG, "zai_chave", "");
        if (zaiChave.isBlank()) zaiChave = chaveDoOpenclaude("zai.env");
        importar(kv, "openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions",
                kv.get(KvRepo.CONFIG, "openrouter_chave", ""),
                "openai/gpt-oss-20b,mistralai/mistral-nemo,qwen/qwen3.7-flash,nvidia/nemotron-3-super-120b-a12b:free",
                0, true, 3500, 180, 10);
        importar(kv, "zai", "Z.ai", "https://api.z.ai/api/paas/v4/chat/completions", zaiChave, "glm-4.5-flash",
                1, kv.booleano(KvRepo.CONFIG, "fallback_zai", true), 2000, 300, 1);
        importar(kv, "xai", "xAI", "https://api.x.ai/v1/chat/completions", "", "", 2, true, 1000, 180, 1);
        kv.remover(KvRepo.CONFIG, "fallback_zai");
    }

    private void importar(KvRepo kv, String prefixo, String nome, String url, String chave, String modelos, int ordem,
                          boolean ativo, int intervaloMs, int timeoutS, int tentativas) {
        salvar(0, nome, kv.get(KvRepo.CONFIG, prefixo + "_url", url), chave, kv.get(KvRepo.CONFIG, prefixo + "_modelos", modelos),
                ordem, ativo, kv.inteiro(KvRepo.CONFIG, prefixo + "_intervalo_ms", intervaloMs),
                kv.inteiro(KvRepo.CONFIG, prefixo + "_timeout_s", timeoutS), kv.inteiro(KvRepo.CONFIG, prefixo + "_tentativas", tentativas));
        for (String s : List.of("url", "chave", "modelos", "intervalo_ms", "timeout_s", "tentativas"))
            kv.remover(KvRepo.CONFIG, prefixo + "_" + s);
    }

    /** OPENAI_API_KEY do .env do openclaude (~/.config/openclaude/arquivo), ou vazio. */
    private static String chaveDoOpenclaude(String arquivo) {
        Path env = Path.of(System.getProperty("user.home"), ".config", "openclaude", arquivo);
        try {
            if (!Files.isReadable(env)) return "";
            Optional<String> v = Files.readAllLines(env).stream().filter(l -> l.startsWith("OPENAI_API_KEY="))
                    .map(l -> l.substring("OPENAI_API_KEY=".length()).replace("\"", "").trim()).findFirst();
            return v.orElse("");
        } catch (IOException e) {
            log.warn("não li {}: {}", env, e.getMessage());
            return "";
        }
    }

    private String cifrar(String chave) {
        return chave == null || chave.isBlank() ? "" : cofre.cifrar(chave.trim());
    }

    private static List<String> modelos(String lista) {
        return java.util.Arrays.stream(lista.split(",")).map(String::trim).filter(m -> !m.isEmpty()).distinct().toList();
    }
}
