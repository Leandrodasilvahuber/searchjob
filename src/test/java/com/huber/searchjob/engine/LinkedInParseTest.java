package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parser da listagem pública de vagas do LinkedIn (HTML fixo, offline). */
class LinkedInParseTest {
    private static final String HTML = """
            <li><div class="base-card base-search-card" data-entity-urn="urn:li:jobPosting:4469936945">
              <div class="base-search-card__info">
                <h3 class="base-search-card__title">
                  Full Stack Software Engineer
                </h3>
                <h4 class="base-search-card__subtitle"><a class="hidden-nested-link">
                  Cypress &amp; Co
                </a></h4>
                <div class="base-search-card__metadata">
                  <span class="job-search-card__location">
                    São Paulo, Brasil
                  </span>
                  <time class="job-search-card__listdate" datetime="2026-10-15">há 1 dia</time>
                </div></div></div></li>
            <li><div data-entity-urn="urn:li:jobPosting:2"><h3 class="base-search-card__title">Dev</h3></div></li>
            """;

    @Test
    void leCartoesDaListagem() {
        List<Vaga> vagas = LinkedInDriver.parse(HTML, 10);
        assertEquals(2, vagas.size());
        Vaga v = vagas.get(0);
        assertEquals("4469936945", v.id());
        assertEquals("Full Stack Software Engineer", v.cargo());
        assertEquals("Cypress & Co", v.empresa());
        assertEquals("São Paulo, Brasil", v.local());
        assertEquals(LocalDate.of(2026, 10, 15), v.publicada());
        assertEquals("https://www.linkedin.com/jobs/view/4469936945/", v.url());
        assertEquals(1, LinkedInDriver.parse(HTML, 1).size());
    }
}
