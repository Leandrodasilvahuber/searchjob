package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Listagem da Sólides: payload do Next.js com HTML fixo (offline). */
class SolidesDriverTest {
    // a descrição longa vem como referência "$1e" e o texto numa linha à parte "1e:T<bytes em hexa>,"
    private static final String PAYLOAD = "9:[\"$\",\"div\",null,{\"data\":[{\"killerQuestions\":[{\"question\":\"Qual a sua pretensão salarial?\"}],"
            + "\"id\":940126,\"title\":\"Desenvolvedor(a) Web Fullstack \",\"description\":\"$1e\",\"companyName\":\"Contato Seguro\","
            + "\"state\":{\"code\":\"RS\"},\"city\":{\"name\":\"Porto Alegre\"},\"jobType\":\"remoto\",\"createdAt\":\"2026-10-19\","
            + "\"hardSkills\":[{\"name\":\"Laravel\"}]},"
            + "{\"killerQuestions\":[],\"id\":\"bCFX46fqia\",\"title\":\"Analista\",\"description\":\"<p>curta</p>\",\"companyName\":\"X\","
            + "\"state\":null,\"city\":null,\"jobType\":\"presencial\",\"createdAt\":null,"
            + "\"publishers\":{\"rhgestor\":{\"externalId\":\"Zj0U\",\"redirectUrl\":\"https://gam.rhgestor.com.br/vagas/detalhes/Zj0U\"}}}],\"totalPages\":3}]\n"
            + "1e:Tb,<p>Olá</p>2f:[\"$\",\"p\"]";

    @Test
    void lêVagasDoPayload() {
        List<Vaga> v = SolidesDriver.parse(PAYLOAD);
        assertEquals(2, v.size());
        Vaga a = v.get(0);
        assertEquals("940126", a.id());
        assertEquals("Desenvolvedor(a) Web Fullstack", a.cargo());
        assertEquals("Contato Seguro", a.empresa());
        assertEquals("Porto Alegre - RS", a.local());
        assertEquals("remoto", a.modalidade());
        assertEquals(LocalDate.of(2026, 10, 19), a.publicada());
        assertEquals("https://vagas.solides.com.br/vaga/940126", a.url());
        assertTrue(a.descricao().startsWith("Olá Laravel"), a.descricao());
        assertTrue(a.descricao().contains("pretensão salarial"));
        Vaga b = v.get(1);
        assertEquals("curta", b.descricao());
        assertEquals("https://gam.rhgestor.com.br/vagas/detalhes/Zj0U", b.url()); // candidatura só no site do rhgestor
        assertEquals("", b.local());
        assertNull(b.publicada());
    }

    @Test
    void referênciaUsaTamanhoEmBytes() {
        assertEquals("<p>Olá</p>", SolidesDriver.texto(PAYLOAD, "$1e"));
        assertEquals("texto comum", SolidesDriver.texto(PAYLOAD, "texto comum"));
        assertEquals("", SolidesDriver.texto(PAYLOAD, "$ff"));
    }

    @Test
    void juntaOsPedaçosDoPayload() throws Exception {
        String html = "<script>self.__next_f.push([1,\"a:\\\"x\\\"\\n\"])</script><script>self.__next_f.push([1,\"b\"])</script>";
        assertEquals("a:\"x\"\nb", SolidesDriver.payload(html));
    }

    @Test
    void slugIgualAoDoPortal() {
        assertEquals("node.js", SolidesDriver.slug("node.js"));
        assertEquals("full-stack", SolidesDriver.slug("  full   stack "));
        assertEquals("programacao", SolidesDriver.slug("programação"));
        assertEquals("c%23", SolidesDriver.slug("c#"));
    }
}
