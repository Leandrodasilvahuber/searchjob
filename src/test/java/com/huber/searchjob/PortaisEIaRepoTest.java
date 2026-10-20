package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huber.searchjob.db.IaProvedorRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.PortalRepo;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class PortaisEIaRepoTest extends BaseIT {
    @Autowired PortalRepo portais;
    @Autowired IaProvedorRepo provedores;
    @Autowired KvRepo kv;
    @Autowired JdbcClient jdbc;
    @Autowired com.huber.searchjob.db.IaPromptRepo prompts;

    @Test
    void promptDoModeloSemeadoUmaVezENaoSobrescreveOEditado() {
        prompts.semear(List.of("teste/m1", "teste/m2"));
        assertEquals(com.huber.searchjob.db.IaPromptRepo.PADRAO, prompts.de("teste/m1"));
        prompts.salvar("teste/m1", "  só números  ");
        prompts.salvar("teste/m2", "");
        prompts.semear(List.of("teste/m1", "teste/m2"));
        assertEquals("só números", prompts.de("teste/m1"));
        assertEquals("", prompts.de("teste/m2"), "apagado de propósito continua vazio");
        assertEquals("", prompts.de("teste/sem-linha"));
    }

    @Test
    void modoEscolhidoVemPrimeiroEOsOutrosFicamDeReserva() {
        assertEquals(List.of("linkedin", "google", "portal"), PortalRepo.ordemLogin("linkedin"));
        assertEquals(List.of("portal", "google", "linkedin"), PortalRepo.ordemLogin("portal"));
        assertEquals(List.of("google", "linkedin", "portal"), PortalRepo.ordemLogin("qualquer"));
    }

    @Test
    void portaisSemeadosComOModoDeCadaUm() {
        assertEquals(PortalRepo.LINKEDIN, portais.modo("Sólides"));
        assertEquals(PortalRepo.PORTAL, portais.modo("ProgramaThor"));
        assertEquals(PortalRepo.GOOGLE, portais.modo("inexistente"));
        portais.definirModo("programathor", PortalRepo.GOOGLE);
        portais.semear();
        assertEquals(PortalRepo.GOOGLE, portais.modo("programathor"), "semear não sobrescreve o que o usuário mudou");
        portais.definirModo("programathor", PortalRepo.PORTAL);
    }

    @Test
    void primeiraSubidaTrazOsProvedoresDaConfigEApagaAsChavesAntigas() {
        jdbc.sql("DELETE FROM ia_provedor").update();
        kv.put(KvRepo.CONFIG, "openrouter_chave", "sk-or");
        kv.put(KvRepo.CONFIG, "openrouter_modelos", "a,b");
        kv.put(KvRepo.CONFIG, "zai_chave", "sk-zai");
        kv.put(KvRepo.CONFIG, "fallback_zai", "false");
        provedores.semear(kv);
        List<IaProvedorRepo.Provedor> ps = provedores.todos();
        assertEquals(List.of("OpenRouter", "Z.ai", "xAI"), ps.stream().map(IaProvedorRepo.Provedor::nome).toList());
        assertEquals("sk-or", ps.get(0).chave());
        assertEquals(List.of("a", "b"), ps.get(0).modelos());
        assertEquals(10, ps.get(0).tentativas());
        assertFalse(ps.get(1).ativo(), "fallback_zai=false vira Z.ai inativa");
        assertFalse(ps.get(2).utilizavel(), "xAI sem chave fica fora do fallback");
        assertEquals("", kv.get(KvRepo.CONFIG, "openrouter_chave", ""));
        assertEquals("x", kv.get(KvRepo.CONFIG, "fallback_zai", "x"));
        String noBanco = jdbc.sql("SELECT chave_cifrada FROM ia_provedor WHERE nome = 'OpenRouter'").query(String.class).single();
        assertFalse(noBanco.contains("sk-or"));
        provedores.semear(kv);
        assertEquals(3, provedores.todos().size(), "só semeia com a tabela vazia");
        assertTrue(provedores.resumos().get(0).temChave());
    }
}
