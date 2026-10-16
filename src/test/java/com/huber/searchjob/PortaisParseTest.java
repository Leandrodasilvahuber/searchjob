package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;

import com.huber.searchjob.engine.CathoDriver;
import com.huber.searchjob.engine.ProgramaThorDriver;
import com.huber.searchjob.engine.Vaga;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Busca real nas listagens públicas; ignorado sem rede. */
class PortaisParseTest {
    @Test
    void programaThorListaVagas() throws Exception {
        List<Vaga> v;
        try {
            v = new ProgramaThorDriver(null, null).buscar("php", 10);
        } catch (java.io.IOException e) {
            Assumptions.abort("sem rede");
            return;
        }
        assertFalse(v.isEmpty());
        Vaga p = v.get(0);
        System.out.println("PT: " + p.empresa() + " | " + p.cargo() + " | " + p.local() + " | " + p.url());
        assertTrue(p.url().startsWith("https://programathor.com.br/jobs/"));
        assertFalse(p.cargo().isBlank());
        assertFalse(p.empresa().isBlank());
    }

    @Test
    void cathoListaVagas() throws Exception {
        List<Vaga> v;
        try {
            v = new CathoDriver(null, null).buscar("desenvolvedor php", 10);
        } catch (java.io.IOException e) {
            Assumptions.abort("sem rede");
            return;
        }
        assertFalse(v.isEmpty());
        Vaga p = v.get(0);
        System.out.println("CATHO: " + p.empresa() + " | " + p.cargo() + " | " + p.local() + " | " + p.publicada() + " | " + p.url());
        assertTrue(p.url().matches("https://www.catho.com.br/vagas/[^/]+/\\d+"));
        assertFalse(p.cargo().isBlank());
    }
}
