package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IndeedLoginGoogleTest {
    @Test
    void reconheceModalDoGoogle() {
        assertTrue(IndeedDriver.pedeLoginGoogle(Texto.normalizar("Entrar  Continuar com o Google")));
        assertTrue(IndeedDriver.pedeLoginGoogle(Texto.normalizar("Continue with Google")));
    }

    @Test
    void paginaDeVagasNaoEModal() {
        assertFalse(IndeedDriver.pedeLoginGoogle(Texto.normalizar("Vagas de desenvolvedor remoto em Brasil")));
    }
}
