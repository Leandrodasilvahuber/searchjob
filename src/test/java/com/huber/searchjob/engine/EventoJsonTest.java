package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventoJsonTest {
    @Test
    void gravaPerguntaEVagaComoJson() throws Exception {
        var r = new SituacaoService.Resultado(SituacaoService.Tipo.APLICADA, "Docker | CI/CD", 1);
        EventoJson.pergunta("Gupy", "Acme — Dev Java\nVaga de backend", Checkboxes.MARCADOR + "Quais? [opções: CI/CD | Docker]", false, r, 1234, "m/x");
        EventoJson.vaga(new Vaga("Gupy", "1", "Acme", "Dev Java", "http://x", "", "", null, "x".repeat(2000)), "erro", "sem progresso");
        List<String> linhas = Files.readAllLines(EventoJson.ARQUIVO);
        JsonNode p = new ObjectMapper().readTree(linhas.get(linhas.size() - 2));
        JsonNode v = new ObjectMapper().readTree(linhas.get(linhas.size() - 1));
        assertEquals("checkbox", p.path("tipo_campo").asText());
        assertEquals("Quais?", p.path("pergunta").asText());
        assertEquals("Acme", p.path("empresa").asText());
        assertEquals("Dev Java", p.path("cargo").asText());
        assertEquals(2, p.path("opcoes").size());
        assertEquals("automatica", p.path("origem").asText());
        assertEquals(1234, p.path("duracao_ms").asLong());
        assertEquals("m/x", p.path("modelo").asText());
        assertEquals("erro", v.path("status").asText());
        assertEquals(2000, v.path("descricao").asText().length());
    }
}
