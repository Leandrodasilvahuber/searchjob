package com.huber.searchjob;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OrigemLocalFilterTest extends BaseIT {
    @Autowired MockMvc mvc;
    @org.springframework.boot.test.mock.mockito.MockBean com.huber.searchjob.engine.Encerramento encerramento;

    @Test
    void botaoEncerrarDerrubaOServicoSoComOrigemLocal() throws Exception {
        mvc.perform(post("/encerrar")).andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(encerramento);
        mvc.perform(post("/encerrar").header("Origin", "http://localhost:8787")).andExpect(status().isOk());
        org.mockito.Mockito.verify(encerramento).encerrar();
    }

    @Test
    void postSemOrigemOuDeOutroSiteEBloqueado() throws Exception {
        mvc.perform(post("/parar")).andExpect(status().isForbidden());
        mvc.perform(post("/parar").header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(post("/parar").header("Origin", "http://localhost:8787")).andExpect(status().is3xxRedirection());
    }

    @Test
    void hostForaDoLoopbackEBloqueadoContraDnsRebinding() throws Exception {
        mvc.perform(get("/").header("Host", "evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/").header("Host", "localhost:8787")).andExpect(status().isOk());
    }

    @Test
    void chaveDaIaNuncaApareceNaPagina() throws Exception {
        mvc.perform(post("/kv/config").header("Origin", "http://localhost:8787")
                .param("chave", "openrouter_chave").param("valor", "SEGREDO-123"));
        String html = mvc.perform(get("/kv/config").header("Host", "localhost")).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("SEGREDO-123"));
        mvc.perform(post("/kv/config").header("Origin", "http://localhost:8787").param("chave", "openrouter_chave").param("valor", ""));
        org.junit.jupiter.api.Assertions.assertFalse(
                mvc.perform(get("/kv/config").header("Host", "localhost")).andReturn().getResponse().getContentAsString().isEmpty());
    }
}
