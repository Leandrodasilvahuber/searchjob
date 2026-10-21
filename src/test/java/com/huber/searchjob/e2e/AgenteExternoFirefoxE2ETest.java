package com.huber.searchjob.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huber.searchjob.BaseIT;
import com.huber.searchjob.db.CadastroRepo;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.engine.AgenteExterno;
import com.huber.searchjob.engine.Aviso;
import com.huber.searchjob.engine.CvContexto;
import com.huber.searchjob.engine.IaClient;
import com.huber.searchjob.engine.NavegadorFirefox;
import com.huber.searchjob.engine.PortalDriver;
import com.huber.searchjob.engine.Vaga;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Firefox real (headless) contra um "site de empresa" local, com a IA simulada: o agente cria a conta (senha aleatória
 * gravada em Cadastros), preenche o formulário, seleciona a opção, anexa o CV, para no dry-run e envia de verdade.
 */
@SpringBootTest
class AgenteExternoFirefoxE2ETest extends BaseIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer servidor;
    private static String base;

    @MockBean IaClient ia;
    @MockBean Aviso aviso;
    @Autowired CadastroRepo cadastros;
    @Autowired CvContexto cvContexto;
    @Autowired KvRepo kv;

    @BeforeAll
    static void sobeServidor() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String formulario = "<h1>Candidatura</h1><label for=n>Nome completo</label><input id=n required>"
                + "<label for=r>Regime</label><select id=r><option>Selecione</option><option>CLT</option><option>PJ</option></select>"
                + "<label for=f>Currículo</label><input type=file id=f style=display:none>"
                + "<button onclick=\\\"document.body.innerHTML='<p>Recebemos sua candidatura</p>'\\\">Enviar candidatura</button>";
        String js = "document.title=document.getElementById('u').value+'|'+document.getElementById('p').value+'|'"
                + "+document.getElementById('p2').value;document.getElementById('area').innerHTML=\"" + formulario + "\"";
        servidor.createContext("/vaga", ex -> {
            byte[] b = ("<!doctype html><meta charset=utf-8><body><div id=area><h1>Crie sua conta</h1>"
                    + "<label for=u>E-mail</label><input type=email id=u><label for=p>Senha</label><input type=password id=p>"
                    + "<label for=p2>Confirme a senha</label><input type=password id=p2>"
                    + "<button onclick=\"" + js.replace("\"", "&quot;") + "\">Criar conta</button></div>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html;charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @AfterAll
    static void derruba() {
        if (servidor != null) servidor.stop(0);
    }

    /** Id do elemento cujo rótulo é {@code rotulo} na lista ELEMENTOS do pedido. */
    private static String id(String pedido, String rotulo) {
        Matcher m = Pattern.compile("(?m)^(\\S+) \\S+ \"" + Pattern.quote(rotulo) + "\"").matcher(pedido);
        if (!m.find()) throw new AssertionError("sem elemento \"" + rotulo + "\" em:\n" + pedido);
        return m.group(1);
    }

    /** IA simulada: cadastro → formulário → confirmação. */
    private static Object responder(String pedido) throws Exception {
        String r;
        if (pedido.contains("Recebemos sua candidatura")) r = "{\"estado\":\"enviada\",\"motivo\":\"confirmação na tela\"}";
        else if (pedido.contains("Crie sua conta"))
            r = "{\"estado\":\"agir\",\"cadastro\":true,\"metodo\":\"senha\",\"acoes\":["
                    + "{\"tipo\":\"preencher\",\"id\":\"" + id(pedido, "E-mail") + "\",\"valor\":\"eu@exemplo.com\"},"
                    + "{\"tipo\":\"preencher\",\"id\":\"" + id(pedido, "Senha") + "\",\"valor\":\"{{SENHA}}\"},"
                    + "{\"tipo\":\"preencher\",\"id\":\"" + id(pedido, "Confirme a senha") + "\",\"valor\":\"{{SENHA}}\"},"
                    + "{\"tipo\":\"clicar\",\"id\":\"" + id(pedido, "Criar conta") + "\"}]}";
        else
            r = "{\"estado\":\"agir\",\"acoes\":["
                    + "{\"tipo\":\"preencher\",\"id\":\"" + id(pedido, "Nome completo") + "\",\"valor\":\"Fulano\"},"
                    + "{\"tipo\":\"selecionar\",\"id\":\"" + id(pedido, "Regime") + "\",\"valor\":\"PJ\"},"
                    + "{\"tipo\":\"anexar_cv\",\"id\":\"" + id(pedido, "Currículo") + "\"},"
                    + "{\"tipo\":\"enviar\",\"id\":\"" + id(pedido, "Enviar candidatura") + "\"}]}";
        return JSON.readTree(r);
    }

    @Test
    void criaContaPreencheAnexaParaNoDryRunEEnvia() throws Exception {
        when(ia.conversarJson(any(), any())).thenAnswer(inv -> responder(inv.getArgument(1)));
        kv.put(KvRepo.DADO, "E-mail", "eu@exemplo.com");
        Path pdf = Path.of("target/cv-e2e.pdf").toAbsolutePath(); // o Firefox snap não enxerga o /tmp
        Files.writeString(pdf, "%PDF-1.4");
        Cv cv = new Cv(1L, "CV_E2E", pdf.toString(), "15 anos de PHP", "php");
        Vaga vaga = new Vaga("LinkedIn externa", "1", "Acme", "Dev", base + "/vaga", "Brasil", "remoto", null, "php");

        NavegadorFirefox nav = new NavegadorFirefox();
        try {
            try {
                nav.driver();
            } catch (IllegalStateException e) {
                Assumptions.abort("Firefox/geckodriver indisponíveis: " + e.getMessage());
            }
            var d = nav.driver();
            var agente = new AgenteExterno(ia, cadastros, cvContexto, kv);
            agente.observar(l -> System.out.println("AGENTE " + l));

            d.get(base + "/vaga");
            var ensaio = agente.candidatar(d, vaga, cv, true, () -> true);
            assertEquals(PortalDriver.Status.PRONTA_DRY_RUN, ensaio.status(), ensaio.motivo());
            String[] titulo = d.getTitle().split("\\|");
            assertEquals("eu@exemplo.com", titulo[0]);
            assertEquals(titulo[1], titulo[2], "senha e confirmação iguais");
            assertTrue(titulo[1].length() >= 12 && !titulo[1].contains("{{"), "senha aleatória: " + titulo[1]);
            var conta = cadastros.doSite(base + "/vaga").orElseThrow();
            assertEquals("eu@exemplo.com", conta.login());
            assertEquals(titulo[1], conta.senha(), "a senha digitada é a salva em Cadastros");
            assertEquals("PJ", new org.openqa.selenium.support.ui.Select(d.findElement(By.id("r"))).getFirstSelectedOption().getText());
            assertTrue(d.findElement(By.id("f")).getAttribute("value").endsWith(".pdf"), "CV anexado");
            assertFalse(d.findElement(By.tagName("body")).getText().contains("Recebemos"), "dry-run não envia");

            var real = agente.candidatar(d, vaga, cv, false, () -> true);
            assertEquals(PortalDriver.Status.ENVIADA, real.status(), real.motivo());
            assertEquals(1, cadastros.todos().size(), "não duplica o cadastro");
        } finally {
            nav.fechar();
        }
    }
}
