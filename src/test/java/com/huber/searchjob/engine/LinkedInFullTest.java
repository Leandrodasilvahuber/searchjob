package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LinkedInFullTest {
    @Test
    void reconheceCandidaturaNoSiteDaEmpresa() {
        assertTrue(LinkedInFullDriver.externa("<button data-tracking-control-name=\"public_jobs_apply-link-offsite_sign-up-modal\">Candidatar-se</button>"));
        assertFalse(LinkedInFullDriver.externa("<button class=\"apply-button apply-button--default\">Candidatura simplificada</button>"));
    }

    @Test
    void chamadoDoSiteExternoTemPrefixoProprio() {
        assertTrue(AgenteExterno.motivo("resolva o CAPTCHA").startsWith("site externo: "));
        assertTrue(AgenteExterno.motivo("").startsWith("site externo: preciso de você"));
    }

    @Test
    void redirectDoLinkedInSegueParaOSiteDaEmpresa() {
        assertEquals("https://empresa.com/vaga?id=1", LinkedInFullDriver.destinoRedirect(
                "https://www.linkedin.com/redir/redirect?url=https%3A%2F%2Fempresa.com%2Fvaga%3Fid%3D1&urlhash=x"));
        assertEquals("https://empresa.com/vaga", LinkedInFullDriver.destinoRedirect("https://empresa.com/vaga"));
    }
}
