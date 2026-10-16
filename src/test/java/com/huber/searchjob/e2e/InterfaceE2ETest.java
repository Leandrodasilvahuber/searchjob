package com.huber.searchjob.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import com.huber.searchjob.BaseIT;
import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.CvRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.Situacao;
import com.huber.searchjob.db.SituacaoRepo;
import com.huber.searchjob.engine.Aviso;
import com.huber.searchjob.engine.Encerramento;
import com.huber.searchjob.engine.Texto;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Percorre todas as telas e formulários pelo HTTP, como o usuário faria no navegador. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterfaceE2ETest extends BaseIT {
    @LocalServerPort int porta;
    @MockBean Encerramento encerramento;
    @MockBean Aviso aviso;
    @Autowired SituacaoRepo situacoes;
    @Autowired CvRepo cvs;
    @Autowired KvRepo kv;
    @Autowired CandidaturaRepo repo;
    private Http http;

    @BeforeEach
    void prepara() {
        http = new Http(porta);
    }

    @Test
    void todasAsTelasCarregam() throws Exception {
        for (String p : new String[] {"/", "/pendencias", "/historico", "/situacoes", "/textos", "/cvs",
                "/kv/dado_fixo", "/kv/preferencia", "/kv/config"}) {
            var r = http.get(p);
            assertEquals(200, r.status(), p);
            assertTrue(r.corpo().contains("Encerrar serviço"), "menu com o botão de encerrar em " + p);
        }
        assertEquals(404, http.get("/kv/inexistente").status());
    }

    @Test
    void crudDeSituacao() throws Exception {
        http.post("/situacoes/salvar", Map.of("pergunta", "Tem CNH E2E?", "resposta", "Sim, AB", "status", "ativa"));
        Situacao s = situacoes.ativaPorHash(Texto.hash("Tem CNH E2E?")).orElseThrow();
        assertTrue(http.get("/situacoes").corpo().contains("Tem CNH E2E?"));

        http.post("/situacoes/salvar", Map.of("id", String.valueOf(s.id()), "pergunta", "Tem CNH E2E?",
                "resposta", "Sim, categoria AB", "status", "ativa"));
        assertEquals("Sim, categoria AB", situacoes.porId(s.id()).orElseThrow().promptOuResposta());

        http.post("/situacoes/" + s.id() + "/status", Map.of("status", "inativa"));
        assertEquals("inativa", situacoes.porId(s.id()).orElseThrow().status());

        http.post("/situacoes/" + s.id() + "/excluir", Map.of());
        assertTrue(situacoes.porId(s.id()).isEmpty());
    }

    @Test
    void pendenciaConfirmadaViraRegraComOrigemIa() throws Exception {
        long id = situacoes.inserir("Gupy", "pergunta", "Pergunta pendente E2E", Texto.hash("Pergunta pendente E2E"),
                "resposta da ia", "ia", SituacaoRepo.PENDENTE_CONFIRMACAO, "");
        assertTrue(http.get("/pendencias").corpo().contains("Pergunta pendente E2E"));
        assertTrue(http.get("/").corpo().contains("Preciso de você"));

        http.post("/pendencias/" + id + "/confirmar", Map.of("resposta", "resposta corrigida"));
        Situacao s = situacoes.porId(id).orElseThrow();
        assertEquals("ativa", s.status());
        assertEquals("ia_confirmada", s.origem());
        assertEquals("resposta corrigida", s.promptOuResposta());
    }

    @Test
    void pendenciaRespondidaPeloUsuarioViraRegra() throws Exception {
        long id = situacoes.inserir("Gupy", "pergunta", "Ajuda E2E", Texto.hash("Ajuda E2E"), null, "usuario",
                SituacaoRepo.AGUARDANDO_USUARIO, "");
        http.post("/pendencias/" + id + "/responder", Map.of("resposta", "Minha resposta"));
        Situacao s = situacoes.porId(id).orElseThrow();
        assertEquals("ativa", s.status());
        assertEquals("usuario", s.origem());
    }

    @Test
    void configuracaoModoDryRunEChaveSecreta() throws Exception {
        http.post("/modo", Map.of("modo", "ia"));
        assertEquals("ia", kv.get(KvRepo.CONFIG, "modo", ""));
        http.post("/modo", Map.of("modo", "codigo"));
        assertEquals("codigo", kv.get(KvRepo.CONFIG, "modo", ""));
        http.post("/dryrun", Map.of("ligado", "false"));
        assertEquals("false", kv.get(KvRepo.CONFIG, "dry_run", ""));
        http.post("/dryrun", Map.of("ligado", "true"));

        http.post("/kv/config", Map.of("chave", "openrouter_chave", "valor", "SEGREDO-E2E"));
        assertEquals("SEGREDO-E2E", kv.get(KvRepo.CONFIG, "openrouter_chave", ""));
        assertFalse(http.get("/kv/config").corpo().contains("SEGREDO-E2E"));
        http.post("/kv/config", Map.of("chave", "openrouter_chave", "valor", ""));
        assertEquals("SEGREDO-E2E", kv.get(KvRepo.CONFIG, "openrouter_chave", ""), "salvar em branco mantém a chave");
        kv.put(KvRepo.CONFIG, "openrouter_chave", "");

        http.post("/kv/preferencia", Map.of("chave", "pref_e2e", "valor", "1"));
        assertEquals("1", kv.get(KvRepo.PREF, "pref_e2e", ""));
        http.post("/kv/preferencia/excluir", Map.of("chave", "pref_e2e"));
        assertEquals("x", kv.get(KvRepo.PREF, "pref_e2e", "x"));
    }

    @Test
    void cvEditarEExcluir() throws Exception {
        cvs.salvar("CV_E2E", "", "texto", "a");
        long id = cvs.porNome("CV_E2E").orElseThrow().id();
        http.post("/cvs/" + id + "/salvar", Map.of("regras", "novas,regras", "texto", "novo texto"));
        assertEquals("novas,regras", cvs.porId(id).orElseThrow().regrasStack());
        http.post("/cvs/" + id + "/excluir", Map.of());
        assertTrue(cvs.porId(id).isEmpty());
    }

    @Test
    void historicoFiltraPorStatus() throws Exception {
        repo.inserir("Fake", "EmpresaEnviada", "CargoX", "https://h.test/1", null, "enviada", "ok", null);
        repo.inserir("Fake", "EmpresaPulada", "CargoY", "https://h.test/2", null, "pulada", "estágio", null);
        String so = http.get("/historico?status=enviada").corpo();
        assertTrue(so.contains("EmpresaEnviada"));
        assertFalse(so.contains("EmpresaPulada"));
    }

    @Test
    void botaoEncerrarDerrubaOServico() throws Exception {
        var r = http.post("/encerrar", Map.of());
        assertEquals(200, r.status());
        assertTrue(r.corpo().contains("SearchJob encerrado"));
        verify(encerramento).encerrar();
    }
}
