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
    void motivoRecenciaIgnoraStackETitulo() {
        LocalDate hoje = LocalDate.of(2026, 7, 1);
        assertTrue(filtro.motivoRecencia(vaga("Dev PHP", "", hoje.minusDays(30)), hoje).isPresent());
        assertTrue(filtro.motivoRecencia(vaga("Estagiário de TI", "Reuniões em inglês", hoje), hoje).isEmpty());
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

    @Test
    void javaNaoCasaComJavascript() {
        LocalDate hoje = LocalDate.now();
        var e = escolha.escolherDefinido(vaga("Dev", "experiência sólida em javascript", hoje)).orElseThrow();
        assertFalse(e.definida()); // nenhuma stack clara: nada de mandar o CV de Java por engano
        assertEquals("Leandro_Huber_CV_NodeJS", e.cv().nome());
    }

    @Test
    void kubernetesNaoViraStackProibida() {
        LocalDate hoje = LocalDate.of(2026, 10, 16);
        assertTrue(filtro.motivoDescarte(vaga("Engenheiro Kubernetes", "node", hoje), hoje).isEmpty());
        assertTrue(filtro.motivoDescarte(vaga("Dev .NET Sênior", "", hoje), hoje).isPresent());
    }

    @Test
    void sinalizaQuandoNaoHaDefinicaoEPriorizaLideranca() {
        LocalDate hoje = LocalDate.now();
        var indefinida = escolha.escolherDefinido(vaga("Dev", "nada conhecido", hoje)).orElseThrow();
        assertFalse(indefinida.definida());
        var clara = escolha.escolherDefinido(vaga("Dev Laravel", "php vue", hoje)).orElseThrow();
        assertTrue(clara.definida());
        var arq = escolha.escolherDefinido(vaga("Arquiteto de Software Sênior", "php laravel", hoje)).orElseThrow();
        assertTrue(arq.definida());
        assertEquals("Leandro_Huber_CV_Sistemas_Distribuidos", arq.cv().nome());
        assertEquals("Leandro_Huber_CV_Sistemas_Distribuidos",
                escolha.escolherDefinido(vaga("Tech Lead Node", "typescript", hoje)).orElseThrow().cv().nome());
    }
}
