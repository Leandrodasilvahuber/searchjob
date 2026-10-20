package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huber.searchjob.db.Cofre;
import com.huber.searchjob.db.CredencialRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class CredencialRepoTest extends BaseIT {
    @Autowired CredencialRepo repo;
    @Autowired JdbcClient jdbc;
    @TempDir Path pasta;

    @BeforeEach
    void limpa() {
        jdbc.sql("DELETE FROM credencial").update();
    }

    @Test
    void cofreCifraEDecifraEGeraChaveComPermissaoRestrita() throws Exception {
        Path chave = pasta.resolve("k.key");
        Cofre cofre = new Cofre(chave.toString());
        String c = cofre.cifrar("s3nh@ çãõ");
        assertNotEquals("s3nh@ çãõ", c);
        assertEquals("s3nh@ çãõ", cofre.decifrar(c));
        assertNotEquals(c, cofre.cifrar("s3nh@ çãõ"), "IV aleatório: mesma senha cifra diferente");
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(chave)));
        assertEquals("s3nh@ çãõ", new Cofre(chave.toString()).decifrar(c), "a chave persiste entre execuções");
        assertThrows(IllegalStateException.class, () -> new Cofre(pasta.resolve("outra.key").toString()).decifrar(c));
    }

    @Test
    void senhaFicaCifradaNoBancoEPrimeiraContaNasceAtiva() {
        repo.salvar("Gupy", " eu@x.com ", "segredo123");
        String noBanco = jdbc.sql("SELECT senha_cifrada FROM credencial").query(String.class).single();
        assertFalse(noBanco.contains("segredo123"));
        Optional<CredencialRepo.Login> l = repo.ativa("GUPY");
        assertEquals("eu@x.com", l.orElseThrow().usuario());
        assertEquals("segredo123", l.get().senha());
        assertTrue(repo.todas().get(0).ativa());
    }

    @Test
    void nomeDoPortalComAcentoAchaALoginDaTelaDeCredenciais() {
        repo.salvar("solides", "eu@x.com", "segredo123"); // como a tela de credenciais grava
        assertEquals("eu@x.com", repo.ativa("Sólides").orElseThrow().usuario()); // como o robô procura
    }

    @Test
    void trocarSenhaEmBrancoMantemETrocaComValor() {
        repo.salvar("gupy", "a@x.com", "antiga");
        repo.salvar("gupy", "a@x.com", "");
        assertEquals("antiga", repo.ativa("gupy").orElseThrow().senha());
        repo.salvar("gupy", "a@x.com", "nova");
        assertEquals("nova", repo.ativa("gupy").orElseThrow().senha());
        assertEquals(1, repo.todas().size());
        assertThrows(IllegalArgumentException.class, () -> repo.salvar("gupy", "b@x.com", ""));
    }

    @Test
    void trocarContaAtivaEExcluirPromoveOutra() {
        repo.salvar("gupy", "a@x.com", "1");
        repo.salvar("gupy", "b@x.com", "2");
        repo.salvar("catho", "c@x.com", "3");
        long b = repo.todas().stream().filter(c -> c.usuario().equals("b@x.com")).findFirst().orElseThrow().id();
        repo.ativar(b);
        assertEquals("b@x.com", repo.ativa("gupy").orElseThrow().usuario());
        assertEquals("c@x.com", repo.ativa("catho").orElseThrow().usuario(), "não mexe em outro portal");
        repo.excluir(b);
        assertEquals("a@x.com", repo.ativa("gupy").orElseThrow().usuario());
        assertTrue(repo.ativa("indeed").isEmpty());
    }
}
