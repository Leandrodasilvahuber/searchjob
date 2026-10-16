package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.db.SituacaoRepo;
import com.huber.searchjob.engine.Aviso;
import com.huber.searchjob.engine.SituacaoService;
import com.huber.searchjob.engine.SituacaoService.Resultado;
import com.huber.searchjob.engine.SituacaoService.Tipo;
import com.huber.searchjob.engine.Texto;
import com.huber.searchjob.engine.ZaiClient;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class SituacaoServiceTest extends BaseIT {
    @Autowired SituacaoService service;
    @Autowired SituacaoRepo repo;
    @Autowired com.huber.searchjob.db.TextoGeradoRepo textos;
    @MockBean ZaiClient zai;
    @MockBean Aviso aviso;
    static final ObjectMapper JSON = new ObjectMapper();

    private void esperarStatus(String status) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (!repo.porStatus(status).isEmpty()) return;
            Thread.sleep(100);
        }
        fail("status " + status + " não apareceu");
    }

    @Test
    void aplicaSituacaoSalvaSemChamarZai() {
        repo.inserir("Gupy", "pergunta", "Você possui CNH?", Texto.hash("Você possui CNH?"), "Sim, categoria AB",
                "usuario", SituacaoRepo.ATIVA, "");
        Resultado r = service.resolver("Gupy", "voce possui cnh", "", () -> true);
        assertEquals(Tipo.APLICADA, r.tipo());
        assertEquals("Sim, categoria AB", r.resposta());
    }

    @Test
    void zaiResolveEUsuarioConfirmaVirandoRegra() throws Exception {
        when(zai.configurado()).thenReturn(true);
        when(zai.conversarJson(any(), any())).thenReturn(JSON.readTree("{\"resolvido\":true,\"resposta\":\"15 anos\"}"));
        var futuro = CompletableFuture.supplyAsync(() -> service.resolver("Gupy", "Anos de experiência com Docker?", "", () -> true));
        esperarStatus(SituacaoRepo.PENDENTE_CONFIRMACAO);
        long id = repo.porStatus(SituacaoRepo.PENDENTE_CONFIRMACAO).get(0).id();
        service.confirmar(id, "Mais de 8 anos");
        Resultado r = futuro.get();
        assertEquals(Tipo.CONFIRMADA_PELO_USUARIO, r.tipo());
        assertEquals("Mais de 8 anos", r.resposta());
        assertEquals("zai_confirmada", repo.porId(id).orElseThrow().origem());
        // próxima vez: aplicada direto
        assertEquals(Tipo.APLICADA, service.resolver("Gupy", "Anos de experiência com Docker?", "", () -> true).tipo());
    }

    @Test
    void zaiNaoResolveEUsuarioRelata() throws Exception {
        when(zai.configurado()).thenReturn(true);
        when(zai.conversarJson(any(), any())).thenReturn(JSON.readTree("{\"resolvido\":false,\"resposta\":\"\"}"));
        var futuro = CompletableFuture.supplyAsync(() -> service.resolver("Gupy", "Qual seu time de futebol?", "", () -> true));
        esperarStatus(SituacaoRepo.AGUARDANDO_USUARIO);
        long id = repo.porStatus(SituacaoRepo.AGUARDANDO_USUARIO).get(0).id();
        service.responder(id, "Prefiro não informar");
        Resultado r = futuro.get();
        assertEquals(Tipo.RESPONDIDA_PELO_USUARIO, r.tipo());
        assertEquals("usuario", repo.porId(id).orElseThrow().origem());
    }

    @Test
    void dadoSensivelNuncaVaiParaZai() {
        assertEquals(Tipo.SENSIVEL, service.resolver("Gupy", "Número do RG", "", () -> true).tipo());
        assertEquals(Tipo.SENSIVEL, service.resolver("Gupy", "Nome completo do pai", "", () -> true).tipo());
    }

    @Test
    void campoDiscursivoEGeradoSobMedidaParaCadaVagaSemConfirmacao() {
        when(zai.configurado()).thenReturn(true);
        when(zai.conversar(any(), any())).thenReturn("Texto A", "Texto B");
        var cv = new com.huber.searchjob.db.Cv(1L, "Leandro_Huber_CV_NodeJS", "", "15 anos de experiência", "");
        Resultado a = service.resolver("Gupy", "Apresente-se para a vaga", "Acme — Dev Node", cv, true, () -> true);
        Resultado b = service.resolver("Gupy", "Apresente-se para a vaga", "Beta — Dev PHP", cv, true, () -> true);
        assertEquals(Tipo.APLICADA, a.tipo());
        assertEquals("Texto A", a.resposta());
        assertEquals("Texto B", b.resposta()); // não reaproveita o texto da vaga anterior
        assertEquals(2, textos.recentes().size());
        assertTrue(repo.todas().stream().anyMatch(x -> "personalizada".equals(x.tipo())));
    }

    @Test
    void deteccaoDeCampoDiscursivo() {
        assertTrue(SituacaoService.discursiva("Conte sobre sua experiência", false));
        assertTrue(SituacaoService.discursiva("Qualquer coisa", true));
        assertFalse(SituacaoService.discursiva("Pretensão salarial", false));
    }
}
