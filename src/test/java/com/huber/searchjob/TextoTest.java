package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;

import com.huber.searchjob.engine.Texto;
import org.junit.jupiter.api.Test;

class TextoTest {
    @Test
    void normalizaAcentosEPontuacao() {
        assertEquals("pretensao salarial", Texto.normalizar("  Pretensão  Salarial?! "));
        assertEquals(Texto.hash("Pretensão salarial"), Texto.hash("pretensao   SALARIAL?"));
    }

    @Test
    void similaridadeAltaParaPerguntasParecidas() {
        assertTrue(Texto.similaridade("Qual a sua pretensão salarial?", "Qual a sua pretensão salarial CLT") >= 0.6);
        assertTrue(Texto.similaridade("Qual a sua pretensão salarial?", "Você possui CNH") < 0.2);
    }
}
