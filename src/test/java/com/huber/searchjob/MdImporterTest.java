package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;

import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.SituacaoRepo;
import com.huber.searchjob.importer.MdImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext
class MdImporterTest extends BaseIT {
    static final Path PASTA = Path.of("/home/huber/Área de Trabalho/buscar trabalho automatico");
    @Autowired MdImporter importer;
    @Autowired KvRepo kv;
    @Autowired SituacaoRepo situacoes;
    @Autowired CandidaturaRepo candidaturas;

    @Test
    void importaMdReal() throws Exception {
        Assumptions.assumeTrue(Files.exists(PASTA.resolve("respostas_candidaturas.md")));
        kv.remover(KvRepo.CONFIG, "md_importado");
        kv.todos(KvRepo.DADO).keySet().forEach(k -> kv.remover(KvRepo.DADO, k)); // banco temporário é compartilhado
        situacoes.todas().forEach(x -> situacoes.excluir(x.id())); // banco temporário é compartilhado entre testes
        var r = importer.importar(PASTA);
        System.out.println("IMPORTADO: " + r);
        assertTrue(r.dadosFixos() >= 8, "dados fixos");
        assertTrue(r.situacoes() >= 30, "situações");
        assertTrue(r.candidaturas() >= 120, "candidaturas");
        assertTrue(kv.get(KvRepo.DADO, "E-mail", "").contains("@"));
        // idempotente
        var r2 = importer.importar(PASTA);
        assertEquals(0, r2.situacoes());
        assertEquals(0, r2.candidaturas());
    }
}
