package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EscolhaCvUnitTest {
    @Test
    void javaNaoCasaComJavascriptMasNodejsCasaComNode() {
        assertFalse(EscolhaCv.casa("experiencia em javascript", "java"));
        assertTrue(EscolhaCv.casa("stack java e spring", "java"));
        assertTrue(EscolhaCv.casa("backend em nodejs", "node"));
        assertTrue(EscolhaCv.casa("usamos springboot", "spring"));
    }

    @Test
    void termosLongosCasamPorRadical() {
        assertTrue(EscolhaCv.casa("arquitetura de microsservicos", "microsservi"));
    }

    @Test
    void pontuarContaPalavrasDistintas() {
        assertEquals(2, EscolhaCv.pontuar("php com laravel", "php,laravel,vue"));
        assertEquals(0, EscolhaCv.pontuar("nada a ver", "php,laravel"));
        assertEquals(0, EscolhaCv.pontuar("php", null));
    }
}
