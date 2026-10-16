package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;

import com.huber.searchjob.db.CvRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.engine.EscolhaCv;
import com.huber.searchjob.engine.FiltroVaga;
import com.huber.searchjob.engine.Vaga;
import com.huber.searchjob.importer.MdImporter;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FiltroEscolhaTest extends BaseIT {
    @Autowired FiltroVaga filtro;
    @Autowired EscolhaCv escolha;
    @Autowired CvRepo cvs;
    @Autowired MdImporter importer;
    @Autowired KvRepo kv;

    @BeforeEach
    void prepara() throws Exception {
        importer.importar(Path.of("/nao/existe")); // só sementes de preferências
        if (cvs.todos().isEmpty()) {
            cvs.salvar("Leandro_Huber_CV_NodeJS", "", "", "node,typescript,express,react");
            cvs.salvar("Leandro_Huber_CV_PHP_Laravel", "", "", "php,laravel,vue");
            cvs.salvar("Leandro_Huber_CV_Sistemas_Distribuidos", "", "", "kafka,microsservi,java,spring");
        }
    }

    private Vaga vaga(String cargo, String desc, LocalDate pub) {
        return new Vaga("Gupy", "1", "Acme", cargo, "https://x", "Florianópolis SC Brasil", "remote", pub, desc);
    }

    @Test
    void descartaRecenciaEstagioEInglesOral() {
        LocalDate hoje = LocalDate.of(2026, 10, 16);
        assertTrue(filtro.motivoDescarte(vaga("Dev PHP", "", hoje.minusDays(30)), hoje).isPresent());
        assertTrue(filtro.motivoDescarte(vaga("Estagiário de TI", "", hoje), hoje).isPresent());
        assertTrue(filtro.motivoDescarte(vaga("Dev Node", "Reuniões em inglês diárias", hoje), hoje).isPresent());
        assertTrue(filtro.motivoDescarte(vaga("Dev Node", "inglês técnico para documentação", hoje), hoje).isEmpty());
    }

    @Test
    void escolheCvPelaStack() {
        LocalDate hoje = LocalDate.now();
        assertEquals("Leandro_Huber_CV_PHP_Laravel", escolha.escolher(vaga("Dev Laravel", "php vue", hoje)).orElseThrow().nome());
        assertEquals("Leandro_Huber_CV_Sistemas_Distribuidos", escolha.escolher(vaga("Dev Java", "kafka microsserviços spring", hoje)).orElseThrow().nome());
        assertEquals("Leandro_Huber_CV_NodeJS", escolha.escolher(vaga("Dev", "nada conhecido", hoje)).orElseThrow().nome());
    }
}
