package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsers das listagens com HTML fixo (offline e determinístico). */
class ParsersPortaisTest {
    private static final String PT = """
            <a href="/jobs"><span>menu</span></a>
            <div class="cell-list ">
              <a href="/jobs/33843-desenvolvedor-a-full-stack-senior">
                <div class="cell-list-content"><h3 class="text-24">Desenvolvedor(a) Full Stack Sênior</h3>
                <div class='cell-list-content-icon'><span><i class='fa fa-briefcase'></i>Galileu Saúde</span>
                <span><i class='fas fa-map-marker-alt'></i>Remoto</span><span><i class='fa fa-building'></i>Pequena</span>
                <span><i class='far fa-chart-bar'></i>Sênior</span><span><i class='far fa-file-alt'></i>PJ</span></div></div>
              </a>
            </div>
            <div class="cell-list ">
              <a href="/jobs/33844-arquiteto"><div><h3>Arquiteto &amp; Líder</h3>
                <span><i class='x'></i>Acme</span><span><i class='x'></i>São Paulo</span></div></a>
            </div>
            """;

    private static final String CATHO = """
            <article class="offer ">
              <span class="tag pub_ontem mb-2">Publicada em 21/07</span>
              <h2 class="title_offer"><a href="/vagas/desenvolvedor-php-pleno/37592584" title="x">Desenvolvedor PHP Pleno</a></h2>
              <p class="mb-2"><span class="text-12">MUV RECURSOS HUMANOS</span></p>
              <p><span class="icon i_job_location"></span><strong>1
              vaga</strong>
                  - Porto Alegre
              </p>
            </article>
            <article class="offer ">
              <h2 class="title_offer"><a href="/vagas/dev-java/99999999">Dev Java</a></h2>
              <span class="tag">Publicada em 30/12</span>
            </article>
            """;

    @Test
    void programaThorIgnoraLinksDeMenuELeCards() {
        List<Vaga> v = ProgramaThorDriver.parse(PT, 10);
        assertEquals(2, v.size());
        Vaga a = v.get(0);
        assertEquals("33843", a.id());
        assertEquals("Galileu Saúde", a.empresa());
        assertEquals("Desenvolvedor(a) Full Stack Sênior", a.cargo());
        assertEquals("Remoto", a.local());
        assertEquals("https://programathor.com.br/jobs/33843-desenvolvedor-a-full-stack-senior", a.url());
        assertNull(a.publicada());
        assertEquals("Arquiteto & Líder", v.get(1).cargo());
    }

    @Test
    void programaThorRespeitaOLimite() {
        assertEquals(1, ProgramaThorDriver.parse(PT, 1).size());
    }

    @Test
    void cathoLeCardsEDatasSemAno() {
        LocalDate hoje = LocalDate.of(2026, 10, 16);
        List<Vaga> v = CathoDriver.parse(CATHO, 10, hoje);
        assertEquals(2, v.size());
        assertEquals("37592584", v.get(0).id());
        assertEquals("MUV RECURSOS HUMANOS", v.get(0).empresa());
        assertEquals("Porto Alegre", v.get(0).local());
        assertEquals(LocalDate.of(2026, 7, 21), v.get(0).publicada());
        // 30/12 ainda não aconteceu em 2026-10-16: é do ano passado
        assertEquals(LocalDate.of(2025, 12, 30), v.get(1).publicada());
    }

    @Test
    void htmlTextoRemoveTagsEEntidades() {
        assertEquals("A & B ok", Html.texto("<b>A &amp; B</b>\n  <i>ok</i>"));
        assertEquals("", Html.texto(null));
    }

    @Test
    void gupyConverteJsonSemValoresNull() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"id":123,"name":"Dev","careerPageName":"Acme","jobUrl":"https://acme.gupy.io/job/x",
                 "publishedDate":"2026-10-10T12:00:00.000Z","city":"Florianópolis","state":"SC","country":null,
                 "workplaceType":"remote","description":"d"}""");
        Vaga v = GupyDriver.paraVaga(json);
        assertEquals("123", v.id());
        assertEquals("Florianópolis SC", v.local());
        assertEquals(LocalDate.of(2026, 10, 10), v.publicada());
        assertTrue(!v.local().contains("null"));
    }
}
