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
        when(kv.get(anyString(), anyString(), anyString())).thenAnswer(i -> switch ((String) i.getArgument(1)) {
            case "excluir_titulos" -> "estagio,estagiario,jovem aprendiz";
            case "exige_ingles_oral" -> "fluencia oral,reunioes em ingles";
            case "stack_proibida" -> "python,.net,java-only";
            default -> i.getArgument(2);
        });
        filtro = new FiltroVaga(kv);
    }

    private static Vaga vaga(String cargo, String desc, LocalDate pub, String local) {
        return new Vaga("Gupy", "1", "Acme", cargo, "https://x", local, "remote", pub, desc);
    }

    private Optional<String> r(String cargo, String desc) {
        return filtro.motivoDescarte(vaga(cargo, desc, HOJE, "Brasil"), HOJE);
    }

    @Test
    void passaVagaNormal() {
        assertTrue(r("Dev Full Stack Node", "inglês técnico para ler documentação").isEmpty());
    }

    @Test
    void descartaPorRecenciaNoLimite() {
        assertTrue(filtro.motivoDescarte(vaga("Dev", "", HOJE.minusDays(14), "Brasil"), HOJE).isEmpty());
        assertTrue(filtro.motivoDescarte(vaga("Dev", "", HOJE.minusDays(15), "Brasil"), HOJE).isPresent());
        assertTrue(filtro.motivoDescarte(vaga("Dev", "", null, "Brasil"), HOJE).isEmpty());
    }

    @Test
    void descartaEstagioEInglesOral() {
        assertTrue(r("Estágio em TI", "").orElse("").contains("estagio"));
        assertTrue(r("Dev", "Reuniões em inglês com o time").orElse("").contains("inglês oral"));
    }

    @Test
    void stackProibidaSoNoTituloEPorPalavraInteira() {
        assertTrue(r("Dev Python Sênior", "").isPresent());
        assertTrue(r("Dev .NET", "").isPresent());
        assertTrue(r("Engenheiro Kubernetes", "").isEmpty());
        assertTrue(r("Dev Node", "integração com python").isEmpty());
    }

    @Test
    void descartaForaDoBrasil() {
        assertEquals("vaga fora do Brasil",
                filtro.motivoDescarte(vaga("Dev", "", HOJE, "Lisboa Portugal"), HOJE).orElse(""));
    }
}
