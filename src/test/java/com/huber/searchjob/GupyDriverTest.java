package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;

import com.huber.searchjob.engine.GupyDriver;
import com.huber.searchjob.engine.Vaga;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Usa a API pública real do Gupy; é ignorado se estiver sem rede. */
class GupyDriverTest {
    @Test
    void buscaVagasPelaApiPublica() throws Exception {
        List<Vaga> vagas;
        try {
            vagas = new GupyDriver(null, null).buscar("laravel", 20);
        } catch (java.io.IOException e) {
            Assumptions.abort("sem rede: " + e.getMessage());
            return;
        }
        assertFalse(vagas.isEmpty());
        Vaga v = vagas.get(0);
        assertEquals("Gupy", v.portal());
        assertTrue(v.url().contains(".gupy.io/"), v.url());
        assertFalse(v.local().contains("null"), v.local());
        for (int i = 1; i < vagas.size(); i++) // mais recentes primeiro
            if (vagas.get(i).publicada() != null && vagas.get(i - 1).publicada() != null)
                assertFalse(vagas.get(i).publicada().isAfter(vagas.get(i - 1).publicada()));
    }
}
