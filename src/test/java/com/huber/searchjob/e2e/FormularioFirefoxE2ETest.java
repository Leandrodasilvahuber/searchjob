package com.huber.searchjob.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.huber.searchjob.BaseIT;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.SituacaoRepo;
import com.huber.searchjob.engine.Aviso;
import com.huber.searchjob.engine.NavegadorFirefox;
import com.huber.searchjob.engine.PortalDriver;
import com.huber.searchjob.engine.PortalWebDriver;
import com.huber.searchjob.engine.SituacaoService;
import com.huber.searchjob.engine.Texto;
import com.huber.searchjob.engine.Vaga;
import com.huber.searchjob.engine.ZaiClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Firefox real (headless) contra um "portal" local: valida o laço genérico de candidatura —
 * preenchimento de campo curto pela resposta salva, texto livre pela z.ai, parada do dry-run antes do envio,
 * envio real, e a detecção de login (campo de senha visível x escondido).
 * É ignorado se o Firefox/geckodriver não estiverem disponíveis.
 */
@SpringBootTest
class FormularioFirefoxE2ETest extends BaseIT {
    private static HttpServer servidor;
    private static String base;

    @MockBean ZaiClient zai;
    @MockBean Aviso aviso;
    @Autowired SituacaoService situacoes;
    @Autowired SituacaoRepo repo;

    @BeforeAll
    static void sobeServidor() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pagina("/vaga", "<h1>Vaga</h1><label for=q>Quantos anos de experiência com Docker?</label><input id=q type=text>"
                + "<label for=ap>Apresente-se</label><textarea id=ap></textarea>"
                + "<button id=b onclick=\"document.body.innerHTML+='<p>Candidatura enviada</p>'\">Quero me candidatar</button>");
        pagina("/login", "<h1>Entrar</h1><input type=password id=p><button>Entrar</button>");
        pagina("/escondido", "<div style='display:none'><input type=password></div><h1>Vaga</h1>"
                + "<button onclick=\"document.body.innerHTML+='<p>Candidatura enviada</p>'\">Quero me candidatar</button>");
        pagina("/entrar", "<h1>Entrar</h1><input type=email id=u><input type=password id=p>"
                + "<button onclick=\"document.title=document.getElementById('u').value+'|'+document.getElementById('p').value\">Entrar</button>");
        pagina("/entrar2", "<h1>Entrar</h1><input type=email id=u><div id=etapa></div>"
                + "<button onclick=\"document.getElementById('etapa').innerHTML='<input type=password id=p>';"
                + "this.onclick=function(){document.title=document.getElementById('u').value+'|'+document.getElementById('p').value}\">Continuar</button>");
        pagina("/bloqueada", "<h1>Vaga</h1><p>Você não pode se candidatar : esta vaga requer habilidade(s) que você não possui.</p>");
        pagina("/externa", "<h1>Vaga</h1><button onclick=\"alert('Você será redirecionado ao sistema de seleção da empresa')\">Quero me candidatar</button>");
        pagina("/ja", "<h1>Vaga</h1><p>Você já se candidatou a esta vaga</p>");
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    private static void pagina(String caminho, String corpo) {
        servidor.createContext(caminho, ex -> {
            byte[] b = ("<!doctype html><meta charset=utf-8><body>" + corpo).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html;charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
    }

    @AfterAll
    static void derruba() {
        if (servidor != null) servidor.stop(0);
    }

    /** Driver mínimo apontando para o portal local. */
    private static final class PortalLocal extends PortalWebDriver {
        PortalLocal(NavegadorFirefox n, SituacaoService s) {
            super(n, s);
        }

        @Override
        public String nome() {
            return "Local";
        }

        @Override
        public List<Vaga> buscar(String termo, int max) {
            return List.of();
        }

        @Override
        protected String[] botoesAvanco() {
            return new String[] {"Continuar"};
        }

        @Override
        protected String[] botoesEnvio() {
            return new String[] {"Quero me candidatar"};
        }

        @Override
        protected String[] textosSucesso() {
            return new String[] {"candidatura enviada"};
        }

        @Override
        protected String[] textosBloqueio() {
            return new String[] {"voce nao pode se candidatar"};
        }
    }

    private static Vaga vaga(String caminho) {
        return new Vaga("Local", "1", "Acme", "Dev Laravel", base + caminho, "Brasil", "remote", null, "php laravel");
    }

    private static final Cv CV = new Cv(1L, "CV_E2E", "", "15 anos de PHP e Laravel", "php");

    private NavegadorFirefox navegador() {
        NavegadorFirefox n = new NavegadorFirefox();
        try {
            n.driver();
        } catch (IllegalStateException e) {
            Assumptions.abort("Firefox/geckodriver indisponíveis: " + e.getMessage());
        }
        return n;
    }

    @Test
    void preencheParaNoDryRunEEnviaDeVerdade() {
        when(zai.configurado()).thenReturn(true);
        when(zai.conversar(any(), any())).thenReturn("Texto sob medida para a vaga");
        repo.inserir("Local", "pergunta", "Quantos anos de experiência com Docker?",
                Texto.hash("Quantos anos de experiência com Docker?"), "8 anos", "usuario", SituacaoRepo.ATIVA, "");

        NavegadorFirefox nav = navegador();
        try {
            var portal = new PortalLocal(nav, situacoes);

            var ensaio = portal.candidatar(vaga("/vaga"), CV, true, () -> true);
            assertEquals(PortalDriver.Status.PRONTA_DRY_RUN, ensaio.status(), ensaio.motivo());
            var d = nav.driver();
            assertEquals("8 anos", d.findElement(By.id("q")).getAttribute("value"));
            assertEquals("Texto sob medida para a vaga", d.findElement(By.id("ap")).getAttribute("value"));
            assertFalse(d.findElement(By.tagName("body")).getText().contains("Candidatura enviada"),
                    "dry-run não pode clicar em enviar");

            var real = portal.candidatar(vaga("/vaga"), CV, false, () -> true);
            assertEquals(PortalDriver.Status.ENVIADA, real.status(), real.motivo());
            assertTrue(nav.driver().findElement(By.tagName("body")).getText().contains("Candidatura enviada"));
        } finally {
            nav.fechar();
        }
    }

    @Test
    void detectaLoginSoQuandoOCampoDeSenhaEstaVisivel() {
        NavegadorFirefox nav = navegador();
        try {
            var portal = new PortalLocal(nav, situacoes);
            var login = portal.candidatar(vaga("/login"), CV, false, () -> true);
            assertEquals(PortalDriver.Status.PRECISA_USUARIO, login.status());
            assertTrue(login.motivo().contains("login"));

            var escondido = portal.candidatar(vaga("/escondido"), CV, false, () -> true);
            assertEquals(PortalDriver.Status.ENVIADA, escondido.status(), "campo de senha oculto não é pedido de login");
        } finally {
            nav.fechar();
        }
    }

    @Test
    void reconheceVagaJaCandidatadaNestaConta() {
        NavegadorFirefox nav = navegador();
        try {
            var r = new PortalLocal(nav, situacoes).candidatar(vaga("/ja"), CV, false, () -> true);
            assertEquals(PortalDriver.Status.PULADA, r.status());
        } finally {
            nav.fechar();
        }
    }

    @Test
    void vagaQueOPortalNaoPermiteEPuladaComOMotivo() {
        NavegadorFirefox nav = navegador();
        try {
            var r = new PortalLocal(nav, situacoes).candidatar(vaga("/bloqueada"), CV, false, () -> true);
            assertEquals(PortalDriver.Status.PULADA, r.status(), r.motivo());
            assertTrue(r.motivo().contains("requer habilidade"), r.motivo());
        } finally {
            nav.fechar();
        }
    }

    @Test
    void vagaExternaComAlertaEPuladaSemQuebrarASessao() {
        NavegadorFirefox nav = navegador();
        try {
            var r = new PortalLocal(nav, situacoes).candidatar(vaga("/externa"), CV, false, () -> true);
            assertEquals(PortalDriver.Status.PULADA, r.status(), r.motivo());
            assertTrue(r.motivo().contains("vaga externa"), r.motivo());
            assertTrue(r.motivo().contains("redirecionado"), r.motivo());
        } finally {
            nav.fechar();
        }
    }

    @Test
    void loginAutomaticoPreencheUmaEDuasEtapas() {
        NavegadorFirefox nav = navegador();
        try {
            var d = nav.driver();
            d.get(base + "/entrar");
            assertTrue(com.huber.searchjob.engine.LoginAutomatico.entrar(d, "eu@x.com", "senha1"));
            assertEquals("eu@x.com|senha1", d.getTitle());

            d.get(base + "/entrar2");
            assertTrue(com.huber.searchjob.engine.LoginAutomatico.entrar(d, "eu@x.com", "senha2"));
            assertEquals("eu@x.com|senha2", d.getTitle());

            d.get(base + "/ja");
            assertFalse(com.huber.searchjob.engine.LoginAutomatico.entrar(d, "x", "y"), "sem formulário de login não faz nada");
        } finally {
            nav.fechar();
        }
    }
}
