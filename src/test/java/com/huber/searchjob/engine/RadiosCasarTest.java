package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RadiosCasarTest {
    @Test
    void casaRespostaComOpcao() {
        var sn = List.of("Sim", "Não");
        assertEquals(0, Radios.casar("Sim", sn));
        assertEquals(0, Radios.casar("Sim, 10 anos", sn));
        assertEquals(1, Radios.casar("nao", sn));
        assertEquals(1, Radios.casar("Não possuo", sn));
        assertEquals(1, Radios.casar("PJ", List.of("CLT", "PJ")));
        assertEquals(0, Radios.casar("Superior completo", List.of("Superior completo", "Pós-graduação")));
        assertEquals(0, Radios.casar("Não", List.of("Não", "Não possuo, mas tenho interesse")));
        assertEquals(1, Radios.casar("Não possuo, mas tenho interesse", List.of("Não", "Não possuo, mas tenho interesse")));
        assertEquals(-1, Radios.casar("talvez", sn));
        assertEquals(-1, Radios.casar("", sn));
        assertEquals(-1, Radios.casar(null, sn));
    }
}
