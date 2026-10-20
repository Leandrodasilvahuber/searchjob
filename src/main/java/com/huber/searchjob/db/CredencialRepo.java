package com.huber.searchjob.db;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Logins dos portais. A senha só existe cifrada no banco e só é decifrada para o robô preencher o formulário. */
@Repository
public class CredencialRepo {
    /** Linha para a tela: nunca carrega a senha. */
    public record Resumo(long id, String portal, String usuario, boolean ativa, String atualizadoEm) {}

    /** Login em claro, para uso do robô. */
    public record Login(String usuario, String senha) {}

    private final JdbcClient jdbc;
    private final Cofre cofre;

    public CredencialRepo(JdbcClient jdbc, Cofre cofre) {
        this.jdbc = jdbc;
        this.cofre = cofre;
    }

    public List<Resumo> todas() {
        return jdbc.sql("SELECT id, portal, usuario, ativa, atualizado_em FROM credencial ORDER BY portal, usuario")
                .query((rs, i) -> new Resumo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, rs.getString(5)))
                .list();
    }

    /**
     * Cria ou atualiza o login (portal + usuário). Senha em branco numa conta existente mantém a atual.
     * A primeira conta de um portal já nasce ativa.
     */
    @Transactional
    public void salvar(String portal, String usuario, String senha) {
        String p = chave(portal);
        String u = usuario.trim();
        boolean existe = jdbc.sql("SELECT COUNT(*) FROM credencial WHERE portal = ? AND usuario = ?")
                .param(p).param(u).query(Integer.class).single() > 0;
        if (existe) {
            if (!senha.isEmpty())
                jdbc.sql("UPDATE credencial SET senha_cifrada = ?, atualizado_em = datetime('now') WHERE portal = ? AND usuario = ?")
                        .param(cofre.cifrar(senha)).param(p).param(u).update();
        } else {
            if (senha.isEmpty()) throw new IllegalArgumentException("Informe a senha");
            boolean primeira = jdbc.sql("SELECT COUNT(*) FROM credencial WHERE portal = ?").param(p)
                    .query(Integer.class).single() == 0;
            jdbc.sql("INSERT INTO credencial(portal, usuario, senha_cifrada, ativa) VALUES (?,?,?,?)")
                    .param(p).param(u).param(cofre.cifrar(senha)).param(primeira ? 1 : 0).update();
        }
    }

    /** Torna esta a conta usada pelo robô no portal (as outras do portal ficam inativas). */
    @Transactional
    public void ativar(long id) {
        jdbc.sql("UPDATE credencial SET ativa = CASE WHEN id = ? THEN 1 ELSE 0 END "
                        + "WHERE portal = (SELECT portal FROM credencial WHERE id = ?)")
                .param(id).param(id).update();
    }

    @Transactional
    public void excluir(long id) {
        Optional<String> portal = jdbc.sql("SELECT portal FROM credencial WHERE id = ? AND ativa = 1").param(id)
                .query(String.class).optional();
        jdbc.sql("DELETE FROM credencial WHERE id = ?").param(id).update();
        // excluiu a ativa: promove outra conta do mesmo portal, se houver
        portal.ifPresent(p -> jdbc.sql("UPDATE credencial SET ativa = 1 WHERE id = "
                + "(SELECT MIN(id) FROM credencial WHERE portal = ?)").param(p).update());
    }

    /** Login ativo do portal (o nome do portal não diferencia maiúsculas nem acentos). */
    public Optional<Login> ativa(String portal) {
        return jdbc.sql("SELECT usuario, senha_cifrada FROM credencial WHERE portal = ? AND ativa = 1")
                .param(chave(portal))
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional()
                .map(l -> new Login(l[0], cofre.decifrar(l[1])));
    }

    /** O robô chama o portal pelo nome de exibição ("Sólides") e a tela de credenciais pela chave ("solides"). */
    private static String chave(String portal) {
        return PortalRepo.chave(portal);
    }
}
