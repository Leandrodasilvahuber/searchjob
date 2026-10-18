package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AjustarCampoTest {
    @Test
    void campoNumericoRecebeSoONumero() {
        assertEquals("5", PortalWebDriver.ajustar(true, "Quantos anos?", "Mais de 5 anos atuando (desde 2021)", null));
        assertEquals("5", PortalWebDriver.ajustar(false, "Quantos anos de experiência você possui?", "Mais de 5 anos", null));
        assertEquals("Mais de 5 anos", PortalWebDriver.ajustar(false, "Conte sobre você", "Mais de 5 anos", null));
    }

    @Test
    void respeitaMaxlength() {
        String r = PortalWebDriver.ajustar(false, "Resumo", "Primeira frase. Segunda frase longa demais.", "20");
        assertEquals("Primeira frase.", r);
        assertTrue(PortalWebDriver.ajustar(false, "Resumo", "palavra1 palavra2 palavra3", "12").length() <= 12);
    }

    @Test
    void tempoSemNumeroVira0() {
        assertEquals("0", PortalWebDriver.ajustar(false, "Há quanto tempo você trabalha com Angular?", "Não tenho experiência com Angular", "20"));
        assertEquals("5", PortalWebDriver.ajustar(false, "Há quanto tempo você trabalha como Full Stack?", "Mais de 5 anos", "20"));
    }

    @Test
    void anosViramSoNumero() {
        assertEquals("8", PortalWebDriver.ajustar(false, "Tempo de experiência com Java", "8 anos, desde 2017", null));
        assertEquals("6", PortalWebDriver.ajustar(false, "Experiência em anos com Spring Boot*", "Cerca de 6 anos", null));
        assertEquals("4", PortalWebDriver.ajustar(false, "How many years of work experience do you have with React?", "4 years", null));
        assertTrue(PortalWebDriver.limite(false, "Anos de experiência com Java", null).contains("APENAS o número"));
    }

    @Test
    void salarioViraSoOValor() {
        String r = "R$ 10.000,00/mês no modelo PJ, negociável conforme escopo e benefícios da vaga.";
        assertEquals("10.000", PortalWebDriver.ajustar(false, "Qual sua pretensão salarial como Prestador de Serviços (PJ)?*", r, "20"));
        assertEquals("10000", PortalWebDriver.ajustar(true, "Pretensão salarial", r, null));
        assertEquals("7.000", PortalWebDriver.ajustar(false, "Pretensão salarial CLT", "PJ R$ 10.000 ou CLT R$ 7.000", null));
        assertEquals("12.000", PortalWebDriver.ajustar(false, "Expected salary", "12k", null));
        assertTrue(PortalWebDriver.limite(false, "Pretensão salarial?", "20").contains("10.000"));
    }

    @Test
    void reconheceCampoDeCidade() {
        assertTrue(PortalWebDriver.ehLocalidade("Location (city)*"));
        assertTrue(PortalWebDriver.ehLocalidade("Inserir cidade ou localidade"));
        assertEquals(false, PortalWebDriver.ehLocalidade("Mobile phone number*"));
    }

    @Test
    void reconhecePerguntaEmIngles() {
        assertTrue(Texto.perguntaEmIngles("Have you completed the following level of education: Bachelor's Degree?"));
        assertTrue(Texto.perguntaEmIngles("What's is your salary expected?"));
        assertTrue(Texto.perguntaEmIngles("Blip Global has my consent to collect, store, and process my data for the purpose of considering me"));
        assertEquals(false, Texto.perguntaEmIngles("LinkedIn Profile"));
        assertEquals(false, Texto.perguntaEmIngles("Website / Portfolio / Github"));
        assertEquals(false, Texto.perguntaEmIngles("Você aceita uma remuneração de R$ 13.500,00 no modelo PJ?"));
        assertEquals(false, Texto.perguntaEmIngles("Quantos anos de experiência você tem com Node.js?"));
    }
}
