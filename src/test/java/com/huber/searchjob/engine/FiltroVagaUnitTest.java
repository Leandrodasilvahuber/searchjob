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
            case "excluir_titulos" -> "estagio,estagiario,jovem aprendiz,junior,jr";
            case "exige_ingles_oral" -> "fluencia oral,reunioes em ingles";
            case "stack_proibida" -> "python,.net,java-only,csharp";
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
        assertTrue(r("Pessoa Desenvolvedora Fullstack .NET JR", "").isPresent());
        assertTrue(r("Dev C# Pleno", "").isPresent());
        assertTrue(r("Dev Node Jr", "").orElse("").contains("jr"));
        assertTrue(r("Dev Node Pleno/Sênior", "").isEmpty());
        assertTrue(r("Engenheiro Kubernetes", "").isEmpty());
        assertTrue(r("Dev Node", "integração com python").isEmpty());
    }

    @Test
    void javaOnlyDescartaSoSemStackAceitaNoTituloOuDescricao() {
        assertTrue(r("Desenvolvedor Full Stack Java (Pleno)", "Java, Spring Boot e Angular").isPresent());
        assertTrue(r("Desenvolvedor Full Stack Java", "Java com React no front").isEmpty());
        assertTrue(r("Dev Node", "integração com java").isEmpty());
    }

    @Test
    void descartaForaDoBrasil() {
        assertEquals("vaga fora do Brasil",
                filtro.motivoDescarte(vaga("Dev", "", HOJE, "Lisboa Portugal"), HOJE).orElse(""));
    }

    @Test
    void descartaVagaEscritaEmIngles() {
        String en = "We are looking for a developer to join our team. You will work with the product team and your experience "
                + "with React is a plus. This is a role for the best of the team and we are growing.";
        assertEquals(Optional.of("vaga em inglês"), filtro.motivoIdioma(vaga("Fullstack Engineer", en, HOJE, "Brasil")));
        String pt = "Buscamos uma pessoa desenvolvedora para a nossa equipe, com experiência em React e Node para atuar na vaga.";
        assertTrue(filtro.motivoIdioma(vaga("Desenvolvedor Fullstack", pt, HOJE, "Brasil")).isEmpty());
    }

    @Test
    void descartaCSharpNoTitulo() {
        assertTrue(r("Desenvolvedor C# Pleno", "").isPresent());
        assertTrue(r("Desenvolvedor Full Stack", "").isEmpty());
    }
}
