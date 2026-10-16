package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CathoFiltroTermoTest {
    @Test
    void pontoNetCasaEmTitulosComPontuacao() {
        assertTrue(CathoDriver.mencionaTermo("Desenvolvedor.NET Pleno", ".net"));
        assertTrue(CathoDriver.mencionaTermo("Dev ASP.NET Core", ".net"));
    }

    @Test
    void netSemPontoNaoCasaEmNetSuite() {
        assertFalse(CathoDriver.mencionaTermo("Consultor Net Suite Oracle Senior", ".net"));
    }

    @Test
    void cSustenidoNaoCasaEmLetraCSolta() {
        assertTrue(CathoDriver.mencionaTermo("Desenvolvedor C# Pleno", "c#"));
        assertFalse(CathoDriver.mencionaTermo("Auxiliar de Montagem C", "c#"));
        assertFalse(CathoDriver.mencionaTermo("Motorista Categoria C", "c#"));
    }

    @Test
    void palavraInteiraEIgnoraAcentoECaixa() {
        assertTrue(CathoDriver.mencionaTermo("PROGRAMADOR DELPHI", "delphi"));
        assertFalse(CathoDriver.mencionaTermo("Analista de Sistemas", "java"));
        assertFalse(CathoDriver.mencionaTermo("Desenvolvedor Javascript", "java"));
        assertTrue(CathoDriver.mencionaTermo("Analista de Integração", "analista de integracao"));
    }

    @Test
    void termoComVariasPalavrasExigeTodasEmQualquerOrdem() {
        assertTrue(CathoDriver.mencionaTermo("Backend Programador Sênior", "programador backend"));
        assertFalse(CathoDriver.mencionaTermo("Programador Frontend", "programador backend"));
    }

    @Test
    void leDescricaoCompletaDoJsonLd() {
        String html = "<script type=\"application/ld+json\">{\"@type\":\"BreadcrumbList\"}</script>"
                + "<script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"description\":\"Atuar com C# e .NET Core;\\nSQL\"}</script>";
        String desc = CathoDriver.descricaoDoJsonLd(html);
        assertTrue(CathoDriver.mencionaTermo(desc, ".net"));
        assertTrue(CathoDriver.mencionaTermo(desc, "c#"));
        assertFalse(CathoDriver.mencionaTermo(desc, "delphi"));
        assertTrue(CathoDriver.descricaoDoJsonLd("<html></html>").isEmpty());
    }
}
