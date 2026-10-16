package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;

import com.huber.searchjob.engine.ChaveVaga;
import org.junit.jupiter.api.Test;

class ChaveVagaTest {
    @Test
    void mesmoJobEmFormatosDiferentesTemMesmaChave() {
        // token base64 de {"jobId":12418941,"source":"gupy_portal"}
        String md = "https://finnet.gupy.io/job/eyJqb2JJZCI6MTI0MTg5NDEsInNvdXJjZSI6Imd1cHlfcG9ydGFsIn0=";
        String api = md + "?jobBoardSource=gupy_portal";
        assertEquals("gupy:12418941", ChaveVaga.de(md));
        assertEquals(ChaveVaga.de(md), ChaveVaga.de(api));
        assertEquals("gupy:12722191", ChaveVaga.de("https://vissimo.gupy.io/jobs/12722191"));
    }
}
