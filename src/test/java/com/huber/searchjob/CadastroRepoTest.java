package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huber.searchjob.db.CadastroRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class CadastroRepoTest extends BaseIT {
    @Autowired CadastroRepo cadastros;
    @Autowired JdbcClient jdbc;

    @Test
    void senhaAleatoriaTemOsQuatroTiposDeCaractere() {
        for (int i = 0; i < 50; i++) {
            String s = CadastroRepo.senhaAleatoria();
            assertEquals(14, s.length());
            assertTrue(s.matches(".*[a-z].*") && s.matches(".*[A-Z].*") && s.matches(".*[0-9].*") && s.matches(".*[!@#$%*].*"), s);
        }
        assertNotEquals(CadastroRepo.senhaAleatoria(), CadastroRepo.senhaAleatoria());
    }

    @Test
    void contaDoSiteValePeloDominioESenhaFicaCifrada() {
        cadastros.salvar("Acme", "https://www.acme.gupy.io/jobs/1?x=1", "eu@x.com", "S3nh@forte", "senha", "Acme — Dev");
        var conta = cadastros.doSite("https://acme.gupy.io/candidates/signin").orElseThrow();
        assertEquals("eu@x.com", conta.login());
        assertEquals("S3nh@forte", conta.senha());
        // outro subdomínio da mesma plataforma acha a conta (a conta do ATS vale em todos), dizendo onde foi criada
        assertEquals("acme.gupy.io", cadastros.doSite("https://outra.gupy.io/").orElseThrow().dominio());
        assertTrue(cadastros.doSite("https://gupy.com.br/").isEmpty());
        cadastros.salvar("W", "https://acme.wd5.myworkdayjobs.com/x", "eu@x.com", "Wd#12345", "senha", "W — Dev");
        assertTrue(cadastros.doSite("https://outra.wd5.myworkdayjobs.com/").isEmpty(), "no Workday a conta é por empresa");
        String noBanco = jdbc.sql("SELECT senha_cifrada FROM cadastro WHERE dominio = 'acme.gupy.io'").query(String.class).single();
        assertTrue(!noBanco.contains("S3nh@forte"));

        cadastros.salvar("Acme", "https://acme.gupy.io/x", "eu@x.com", "Nova#123", "senha", "Acme — Dev 2");
        assertEquals("Nova#123", cadastros.doSite("https://acme.gupy.io/").orElseThrow().senha());
        long id = cadastros.todos().stream().filter(c -> c.dominio().equals("acme.gupy.io")).count();
        assertEquals(1, id, "mesmo login no mesmo site atualiza em vez de duplicar");
    }

    @Test
    void plataformaEhODominioRegistravel() {
        assertEquals("solides.com.br", CadastroRepo.plataforma("contatoseguro.vagas.solides.com.br"));
        assertEquals("gupy.io", CadastroRepo.plataforma("acme.gupy.io"));
        assertEquals("inhire.app", CadastroRepo.plataforma("inhire.app"));
        assertEquals("acme.wd5.myworkdayjobs.com", CadastroRepo.plataforma("acme.wd5.myworkdayjobs.com"));
    }
}
