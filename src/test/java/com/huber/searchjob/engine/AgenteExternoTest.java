package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgenteExternoTest {
    @Test
    void valorAceitoComPrefixoDoPaisOuCentavos() {
        assertTrue(AgenteExterno.aceito("+55 (48) 98811-4351", "48988114351"));
        assertTrue(AgenteExterno.aceito("R$ 10.000,00", "10000"));
        assertFalse(AgenteExterno.aceito("(48) 98811-43", "48988114351"));
        assertFalse(AgenteExterno.aceito("R$ 10.000,50", "10000"));
    }

    @Test
    void ddiSoQuandoOCampoMostraOutroPais() {
        assertTrue(AgenteExterno.precisaDdi("+48 (98) 81143-51"));
        assertFalse(AgenteExterno.precisaDdi("+55 48988 114351"));
        assertFalse(AgenteExterno.precisaDdi("(48) 98811-4351"));
    }

    @Test
    void moedaPeloSimboloOuCentavos() {
        assertTrue(AgenteExterno.moeda("R$ 100,00"));
        assertTrue(AgenteExterno.moeda("100,00"));
        assertFalse(AgenteExterno.moeda("10000"));
    }
}
