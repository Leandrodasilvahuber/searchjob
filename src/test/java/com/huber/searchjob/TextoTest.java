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
    void tokensCurtosNaoReaproveitamRespostaDeOutraPergunta() {
        assertFalse(Texto.equivalentes("Você possui experiência com a linguagem de programação Go?",
                "Você possui experiência com a linguagem de programação R?"));
        assertFalse(Texto.equivalentes("Possui CNH categoria A para a vaga?", "Possui CNH categoria B para a vaga?"));
        assertTrue(Texto.equivalentes("Qual a sua pretensão salarial?", "Qual sua pretensão salarial"));
    }

    @Test
    void palavraInteiraNaoCasaPorSubstring() {
        assertFalse(Texto.contemPalavra("Especialista em Kubernetes e Internet", ".net"));
        assertTrue(Texto.contemPalavra("Dev C# .NET Sênior", ".net"));
    }

    @Test
    void similaridadeAltaParaPerguntasParecidas() {
        assertTrue(Texto.similaridade("Qual a sua pretensão salarial?", "Qual a sua pretensão salarial CLT") >= 0.6);
        assertTrue(Texto.similaridade("Qual a sua pretensão salarial?", "Você possui CNH") < 0.2);
    }
}
