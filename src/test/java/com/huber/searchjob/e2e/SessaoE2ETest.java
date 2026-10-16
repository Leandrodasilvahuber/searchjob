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
import com.huber.searchjob.db.CredencialRepo;
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
    @Autowired CredencialRepo credenciais;

    private final FakePortal portal = new FakePortal();
    private Http http;

    /** Portal simulado: devolve vagas fixas e o desfecho programado para cada URL, contando as tentativas. */
    static final class FakePortal implements PortalDriver {
        final Map<String, Desfecho> desfechos = new HashMap<>();
        final Map<String, Integer> tentativas = new HashMap<>();
        final Map<String, String> cvPorUrl = new HashMap<>();
        List<Vaga> vagas = List.of();
        /** Login "digitado pelo usuário" que lerLogin devolve, e logins usados por entrar(). */
        volatile String[] digitado = {null, null};
        final List<String> entradas = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean entrar(String usuario, String senha) {
            entradas.add(usuario + ":" + senha);
            desfechos.clear(); // logou: a vaga volta a passar
            return true;
        }

        @Override
        public String[] lerLogin() {
            return digitado;
        }

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
        jdbc.sql("DELETE FROM credencial").update();
        portal.entradas.clear();
        portal.digitado = new String[] {null, null};
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
            if (!e.path("chamado").isNull() && !e.path("chamado").isMissingNode()) {
                http.post("/chamado/pular", Map.of()); // o usuário não logou: pula o portal
            }
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
    void chamadoDeLoginPausaAteContinuarERetomaAMesmaVaga() throws Exception {
        portal.vagas = List.of(vaga("l", "Dev Laravel", "php laravel"));
        portal.desfechos.put("https://fake.test/l",
                new PortalDriver.Desfecho(PortalDriver.Status.PRECISA_USUARIO, "login necessário em Fake"));
        assertEquals(302, http.post("/iniciar", Map.of()).status());
        JsonNode chamado = null;
        for (int i = 0; i < 100 && (chamado == null || chamado.isNull()); i++) {
            Thread.sleep(200);
            chamado = JSON.readTree(http.get("/api/estado").corpo()).path("estado").path("chamado");
        }
        assertEquals("LOGIN", chamado.path("tipo").asText());
        portal.desfechos.clear(); // o usuário logou
        http.post("/chamado/continuar", Map.of());
        for (int i = 0; i < 100; i++) {
            Thread.sleep(200);
            if (JSON.readTree(http.get("/api/estado").corpo()).path("estado").path("log").toString().contains("terminei")) break;
        }
        assertEquals("enviada", historico().get("https://fake.test/l").status());
        assertEquals(2, portal.tentativas.get("https://fake.test/l"));
    }

    @Test
    void comLoginSalvoEntraSozinhoSemChamarOUsuario() throws Exception {
        credenciais.salvar("fake", "eu@x.com", "pw1");
        portal.vagas = List.of(vaga("s", "Dev Laravel", "php laravel"));
        portal.desfechos.put("https://fake.test/s",
                new PortalDriver.Desfecho(PortalDriver.Status.PRECISA_USUARIO, "login necessário em Fake"));
        JsonNode e = rodar();
        assertEquals(List.of("eu@x.com:pw1"), portal.entradas);
        assertEquals("enviada", historico().get("https://fake.test/s").status());
        assertFalse(e.path("log").toString().contains("aguardando"), "não deve chamar o usuário");
    }

    @Test
    void semLoginSalvoChamaOUsuarioESalvaOQueEleDigitou() throws Exception {
        portal.vagas = List.of(vaga("n", "Dev Laravel", "php laravel"));
        portal.desfechos.put("https://fake.test/n",
                new PortalDriver.Desfecho(PortalDriver.Status.PRECISA_USUARIO, "login necessário em Fake"));
        assertEquals(302, http.post("/iniciar", Map.of()).status());
        JsonNode chamado = null;
        for (int i = 0; i < 100 && (chamado == null || chamado.isNull()); i++) {
            Thread.sleep(200);
            chamado = JSON.readTree(http.get("/api/estado").corpo()).path("estado").path("chamado");
        }
        assertEquals("LOGIN", chamado.path("tipo").asText());
        assertTrue(chamado.path("motivo").asText().contains("será salvo"));
        portal.digitado = new String[] {"novo@x.com", "senha9"}; // o usuário digita no formulário
        Thread.sleep(2500); // dá tempo da captura periódica
        portal.digitado = new String[] {null, null}; // enviou o formulário: os campos sumiram
        portal.desfechos.clear();
        http.post("/chamado/continuar", Map.of());
        for (int i = 0; i < 100; i++) {
            Thread.sleep(200);
            if (JSON.readTree(http.get("/api/estado").corpo()).path("estado").path("log").toString().contains("terminei")) break;
        }
        assertEquals("enviada", historico().get("https://fake.test/n").status());
        CredencialRepo.Login salvo = credenciais.ativa("fake").orElseThrow();
        assertEquals("novo@x.com", salvo.usuario());
        assertEquals("senha9", salvo.senha());
        assertTrue(portal.entradas.isEmpty(), "sem login salvo não há login automático");
    }

    @Test
    void maxEnviosEncerraASessaoDepoisDoPrimeiroEnvio() throws Exception {
        portal.vagas = List.of(vaga("p1", "Dev Laravel", "php laravel"), vaga("p2", "Dev Laravel II", "php laravel"),
                vaga("p3", "Dev Laravel III", "php laravel"));
        kv.put(KvRepo.CONFIG, "max_envios", "1");
        try {
            JsonNode e = rodar();
            assertEquals(1, e.path("enviadas").asInt());
            assertEquals(1, portal.tentativas.size(), "as outras vagas não podem ser abertas depois do limite");
            assertTrue(e.path("log").toString().contains("limite de 1 envio"));
        } finally {
            kv.put(KvRepo.CONFIG, "max_envios", "0");
        }
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
