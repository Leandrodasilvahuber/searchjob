package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huber.searchjob.db.KvRepo;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FiltroVaga sem Spring nem banco: o KvRepo é simulado. */
class FiltroVagaUnitTest {
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 16);
    private FiltroVaga filtro;

    @BeforeEach
    void prepara() {
        KvRepo kv = mock(KvRepo.class);
        when(kv.inteiro(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(14);
        filtro = new FiltroVaga(kv);
    }

    private static Vaga vaga(String cargo, String desc, LocalDate pub) {
        return new Vaga("Gupy", "1", "Acme", cargo, "https://x", "Brasil", "remote", pub, desc);
    }

    @Test
    void descartaPorRecenciaNoLimite() {
        assertTrue(filtro.motivoRecencia(vaga("Dev", "", HOJE.minusDays(14)), HOJE).isEmpty());
        assertTrue(filtro.motivoRecencia(vaga("Dev", "", HOJE.minusDays(15)), HOJE).isPresent());
        assertTrue(filtro.motivoRecencia(vaga("Dev", "", null), HOJE).isEmpty());
    }

    @Test
    void descartaVagaEscritaEmIngles() {
        String en = "We are looking for a developer to join our team. You will work with the product team and your experience "
                + "with React is a plus. This is a role for the best of the team and we are growing.";
        assertEquals(Optional.of("vaga em inglês"), filtro.motivoIdioma(vaga("Fullstack Engineer", en, HOJE)));
        String pt = "Buscamos uma pessoa desenvolvedora para a nossa equipe, com experiência em React e Node para atuar na vaga.";
        assertTrue(filtro.motivoIdioma(vaga("Desenvolvedor Fullstack", pt, HOJE)).isEmpty());
    }
}
