package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CheckboxesTest {
    private static final List<String> OPCOES = List.of("NoSQL", "Docker", "CI/CD", "Cloud (AWS, Azure ou GCP)", "Outras");

    @Test
    void marcaVariasOpcoesSeparadasPorBarra() {
        assertEquals(Set.of(1, 2, 3), Checkboxes.casarVarias("Docker | CI/CD | Cloud (AWS, Azure ou GCP)", OPCOES));
    }

    @Test
    void ignoraPartesQueNaoSaoOpcoes() {
        assertEquals(Set.of(0), Checkboxes.casarVarias("NoSQL; nada a ver", OPCOES));
    }

    @Test
    void semRespostaNaoMarcaNada() {
        assertTrue(Checkboxes.casarVarias(null, OPCOES).isEmpty());
        assertTrue(Checkboxes.casarVarias("", OPCOES).isEmpty());
    }
}
