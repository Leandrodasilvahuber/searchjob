package com.huber.searchjob.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.BaseIT;
import com.huber.searchjob.db.Candidatura;
import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.CvRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.engine.Aviso;
import com.huber.searchjob.engine.FabricaDrivers;
import com.huber.searchjob.engine.PortalDriver;
import com.huber.searchjob.engine.Vaga;
import com.huber.searchjob.importer.MdImporter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Sessão completa pela interface HTTP: Iniciar → busca → filtros → escolha de CV → candidatura → histórico.
 * O portal é um driver de mentira (sem navegador), então roda em qualquer máquina.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessaoE2ETest extends BaseIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int porta;
    @MockBean FabricaDrivers fabrica;
    @MockBean Aviso aviso;
    @Autowired KvRepo kv;
    @Autowired CvRepo cvs;
    @Autowired CandidaturaRepo candidaturas;
    @Autowired MdImporter importer;
    @Autowired JdbcClient jdbc;

    private final FakePortal portal = new FakePortal();
    private Http http;

    /** Portal simulado: devolve vagas fixas e o desfecho programado para cada URL, contando as tentativas. */
    static final class FakePortal implements PortalDriver {
        final Map<String, Desfecho> desfechos = new HashMap<>();
        final Map<String, Integer> tentativas = new HashMap<>();
        final Map<String, String> cvPorUrl = new HashMap<>();
        List<Vaga> vagas = List.of();

        @Override
        public String nome() {
            return "Fake";
        }

        @Override
        public List<Vaga> buscar(String termo, int max) {
            return vagas;
        }

        @Override
        public Desfecho candidatar(Vaga v, Cv cv, boolean dryRun, BooleanSupplier continuar) {
            tentativas.merge(v.url(), 1, Integer::sum);
            cvPorUrl.put(v.url(), cv.nome());
            return desfechos.getOrDefault(v.url(), new Desfecho(Status.ENVIADA, "ok"));
        }
    }

    private static Vaga vaga(String id, String cargo, String desc) {
        return new Vaga("Fake", id, "Acme", cargo, "https://fake.test/" + id, "Brasil", "remote", LocalDate.now(), desc);
    }

    @BeforeEach
    void prepara() throws Exception {
        http = new Http(porta);
        importer.importar(Path.of("/nao/existe")); // semeia preferências padrão
        jdbc.sql("DELETE FROM candidatura").update();
        jdbc.sql("DELETE FROM cv").update();
        cvs.salvar("CV_NodeJS", "", "texto node", "node,typescript");
        cvs.salvar("CV_PHP_Laravel", "", "texto php", "php,laravel,vue");
        kv.put(KvRepo.CONFIG, "portais", "fake");
        kv.put(KvRepo.CONFIG, "modo", "codigo");
        kv.put(KvRepo.CONFIG, "dry_run", "false");
        kv.put(KvRepo.CONFIG, "max_vagas", "10");
        kv.put(KvRepo.PREF, "termos", "laravel");
        kv.put(KvRepo.PREF, "recencia_dias", "14");
        when(fabrica.criar(eq("fake"), any())).thenReturn(Optional.of(portal));
        portal.desfechos.clear();
        portal.tentativas.clear();
        portal.cvPorUrl.clear();
    }

    private JsonNode rodar() throws Exception {
        assertEquals(302, http.post("/iniciar", Map.of()).status());
        for (int i = 0; i < 150; i++) {
            Thread.sleep(200);
            JsonNode e = JSON.readTree(http.get("/api/estado").corpo()).path("estado");
            if (!e.path("rodando").asBoolean() && e.path("log").toString().contains("terminei")) {
                Thread.sleep(300); // deixa a thread da sessão terminar de vez
                return e;
            }
        }
        throw new AssertionError("a sessão não terminou em 30s");
    }

    private Map<String, Candidatura> historico() {
        Map<String, Candidatura> out = new HashMap<>();
        candidaturas.listar("", "").forEach(c -> out.put(c.url(), c));
        return out;
    }

    @Test
    void sessaoCompletaFiltraEscolheCvPulaPortalDeslogadoERetomaSemDuplicar() throws Exception {
        portal.vagas = List.of(
                vaga("a", "Dev Laravel Pleno", "php laravel vue"),
                vaga("b", "Estagiário de PHP", "php"),
                vaga("c", "Dev Node Sênior", "typescript node"),
                vaga("d", "Dev Vue", "vue"));
        portal.desfechos.put("https://fake.test/c",
                new PortalDriver.Desfecho(PortalDriver.Status.PRECISA_USUARIO, "login necessário em Fake"));

        // --- rodada 1
        JsonNode e = rodar();
        Map<String, Candidatura> h = historico();
        assertEquals("enviada", h.get("https://fake.test/a").status());
        assertEquals("CV_PHP_Laravel", h.get("https://fake.test/a").cvUsado());
        assertEquals("pulada", h.get("https://fake.test/b").status());
        assertTrue(h.get("https://fake.test/b").motivo().contains("estagiario"));
        assertEquals("aguardando_usuario", h.get("https://fake.test/c").status());
        assertFalse(h.containsKey("https://fake.test/d"), "portal sem login não deve seguir para a vaga seguinte");
        assertEquals(1, e.path("enviadas").asInt());
        assertEquals(1, e.path("puladas").asInt());
        assertEquals("CV_NodeJS", portal.cvPorUrl.get("https://fake.test/c"));

        // --- rodada 2: usuário logou. A não é refeita; C é retomada; D agora entra
        portal.desfechos.clear();
        rodar();
        h = historico();
        assertEquals(1, portal.tentativas.get("https://fake.test/a"), "vaga já enviada não pode ser refeita");
        assertFalse(portal.tentativas.containsKey("https://fake.test/b"), "vaga pulada não pode ser refeita");
        assertEquals(2, portal.tentativas.get("https://fake.test/c"));
        assertEquals("enviada", h.get("https://fake.test/c").status());
        assertEquals("enviada", h.get("https://fake.test/d").status());
        assertEquals(4, candidaturas.listar("", "").size(), "nenhuma linha duplicada no histórico");
    }

    @Test
    void dryRunNaoRepeteEnsaioENaoImpedeOEnvioReal() throws Exception {
        portal.vagas = List.of(vaga("x", "Dev Laravel", "php laravel"));
        portal.desfechos.put("https://fake.test/x", new PortalDriver.Desfecho(PortalDriver.Status.PRONTA_DRY_RUN, "dry-run"));
        kv.put(KvRepo.CONFIG, "dry_run", "true");

        rodar();
        assertEquals("dry_run", historico().get("https://fake.test/x").status());
        rodar(); // segundo ensaio: não refaz
        assertEquals(1, portal.tentativas.get("https://fake.test/x"));

        kv.put(KvRepo.CONFIG, "dry_run", "false");
        portal.desfechos.clear();
        rodar(); // envio real: refaz uma vez
        assertEquals(2, portal.tentativas.get("https://fake.test/x"));
        assertEquals("enviada", historico().get("https://fake.test/x").status());
        assertEquals(1, candidaturas.listar("", "").size());
    }

    @Test
    void vagaJaEnviadaEmOutroFormatoDeLinkNaoEReenviada() throws Exception {
        // histórico importado do .md usa /jobs/<id>; a busca devolve o token base64 com query
        candidaturas.inserir("Gupy", "Finnet", "Dev", "https://finnet.gupy.io/jobs/12418941", null, "enviada", "ok", "2026-09-13");
        portal.vagas = List.of(new Vaga("Fake", "12418941", "Finnet", "Dev Laravel",
                "https://finnet.gupy.io/job/eyJqb2JJZCI6MTI0MTg5NDEsInNvdXJjZSI6Imd1cHlfcG9ydGFsIn0=?jobBoardSource=gupy_portal",
                "Brasil", "remote", LocalDate.now(), "php"));
        rodar();
        assertTrue(portal.tentativas.isEmpty());
    }

    @Test
    void pararEncerraASessaoEmAndamento() throws Exception {
        portal.vagas = List.of();
        rodar();
        var r = http.post("/parar", Map.of());
        assertEquals(302, r.status());
    }
}
