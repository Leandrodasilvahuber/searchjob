package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huber.searchjob.db.KvRepo;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** IaClient contra um servidor HTTP local que faz o papel do OpenRouter (/or) e da Z.ai (/zai). */
class IaClientFallbackTest {
    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"oi\"}}]}";
    private HttpServer srv;
    private final Map<String, String> cfg = new HashMap<>();
    /** Status devolvido por chamada a cada rota (o último se repete). */
    private final Map<String, List<Integer>> roteiro = new HashMap<>();
    private final Map<String, AtomicInteger> chamadas = new HashMap<>();
    private final List<Long> horarios = new CopyOnWriteArrayList<>();
    private final List<String> corpos = new CopyOnWriteArrayList<>();
    private IaClient ia;

    @BeforeEach
    void sobe() throws Exception {
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String rota : List.of("/or", "/zai")) {
            chamadas.put(rota, new AtomicInteger());
            srv.createContext(rota, x -> {
                corpos.add(rota + " " + new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                horarios.add(System.currentTimeMillis());
                List<Integer> r = roteiro.getOrDefault(rota, List.of(200));
                int n = chamadas.get(rota).getAndIncrement();
                int st = r.get(Math.min(n, r.size() - 1));
                byte[] b = (st == 200 ? OK : "{\"erro\":1}").getBytes(StandardCharsets.UTF_8);
                if (st == 429) x.getResponseHeaders().add("Retry-After", "0");
                x.sendResponseHeaders(st, b.length);
                x.getResponseBody().write(b);
                x.close();
            });
        }
        // feito o OpenRouter segurando o corpo: cabeçalhos na hora e espaços de keep-alive sem nunca terminar
        srv.createContext("/lento", x -> {
            x.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 40; i++) {
                    x.getResponseBody().write(' ');
                    x.getResponseBody().flush();
                    Thread.sleep(250);
                }
            } catch (Exception ignorada) {
            }
            x.close();
        });
        srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        cfg.put("openrouter_url", base + "/or");
        cfg.put("openrouter_chave", "k1");
        cfg.put("openrouter_modelos", "m1");
        cfg.put("openrouter_intervalo_ms", "0");
        cfg.put("zai_url", base + "/zai");
        cfg.put("zai_chave", "k2");
        cfg.put("zai_modelos", "glm-4.5-flash");
        cfg.put("zai_intervalo_ms", "0");
        cfg.put("fallback_zai", "true");
        KvRepo kv = mock(KvRepo.class);
        when(kv.get(anyString(), anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer(i -> cfg.getOrDefault(i.getArgument(1), i.getArgument(2)));
        when(kv.booleano(anyString(), anyString(), anyBoolean())).thenAnswer(i -> Boolean.parseBoolean(
                cfg.getOrDefault(i.getArgument(1), String.valueOf((boolean) i.getArgument(2)))));
        when(kv.inteiro(anyString(), anyString(), anyInt())).thenAnswer(i -> Integer.parseInt(
                cfg.getOrDefault(i.getArgument(1), String.valueOf((int) i.getArgument(2)))));
        ia = new IaClient(kv);
    }

    @AfterEach
    void desce() {
        srv.stop(0);
    }

    @Test
    void openRouterFalhandoCaiNaZaiSemThinking() {
        roteiro.put("/or", List.of(500));
        assertEquals("oi", ia.conversar("s", "u"));
        assertEquals("glm-4.5-flash", ia.ultimoModelo());
        assertEquals(10, chamadas.get("/or").get(), "o OpenRouter é tentado 10 vezes antes da Z.ai");
        String zai = corpos.stream().filter(c -> c.startsWith("/zai")).findFirst().orElseThrow();
        assertTrue(zai.contains("\"thinking\":{\"type\":\"disabled\"}"));
        assertFalse(corpos.get(0).contains("thinking"), "o OpenRouter não recebe o parâmetro da Z.ai");
    }

    @Test
    void corpoQueNuncaTerminaEstouraOPrazoECaiNaZai() {
        cfg.put("openrouter_url", cfg.get("zai_url").replace("/zai", "/lento"));
        cfg.put("openrouter_timeout_s", "1");
        cfg.put("openrouter_tentativas", "2");
        long t0 = System.currentTimeMillis();
        assertEquals("oi", ia.conversar("s", "u"));
        assertEquals("glm-4.5-flash", ia.ultimoModelo());
        assertTrue(System.currentTimeMillis() - t0 < 5000, "levou " + (System.currentTimeMillis() - t0) + "ms");
    }

    @Test
    void fallbackDesligadoNaoChamaAZai() {
        roteiro.put("/or", List.of(500));
        cfg.put("fallback_zai", "false");
        assertThrows(IaClient.IaIndisponivel.class, () -> ia.conversar("s", "u"));
        assertEquals(0, chamadas.get("/zai").get());
    }

    @Test
    void semChaveNenhumaNaoEstaConfigurado() {
        cfg.put("openrouter_chave", "");
        assertTrue(ia.configurado(), "a Z.ai ligada e com chave basta");
        cfg.put("fallback_zai", "false");
        assertFalse(ia.configurado());
    }

    @Test
    void zaiFalhandoTentaUmaVezSo() {
        roteiro.put("/or", List.of(500));
        roteiro.put("/zai", List.of(500));
        assertThrows(IaClient.IaIndisponivel.class, () -> ia.conversar("s", "u"));
        assertEquals(1, chamadas.get("/zai").get());
    }

    @Test
    void erro429EsperaETentaDeNovoNoMesmoProvedor() {
        roteiro.put("/or", List.of(429, 429, 200));
        assertEquals("oi", ia.conversar("s", "u"));
        assertEquals(3, chamadas.get("/or").get());
        assertEquals(0, chamadas.get("/zai").get());
    }

    @Test
    void respeitaOIntervaloMinimoEntreChamadas() {
        cfg.put("openrouter_intervalo_ms", "400");
        ia.conversar("s", "u");
        ia.conversar("s", "u");
        assertTrue(horarios.get(1) - horarios.get(0) >= 380, "intervalo: " + (horarios.get(1) - horarios.get(0)));
    }

    @Test
    void chamadasSimultaneasVaoUmaPorVez() throws Exception {
        cfg.put("openrouter_intervalo_ms", "200");
        Thread a = new Thread(() -> ia.conversar("s", "u"));
        Thread b = new Thread(() -> ia.conversar("s", "u"));
        a.start();
        b.start();
        a.join();
        b.join();
        assertTrue(Math.abs(horarios.get(1) - horarios.get(0)) >= 180);
    }
}
