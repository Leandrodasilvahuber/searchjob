package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.engine.IaClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Teste manual contra o OpenRouter real: mvn test -Dtest=ModelosAoVivoTest -Dlive=true -Dmodelos=a,b. Usa uma cópia do banco. */
@EnabledIfSystemProperty(named = "live", matches = "true")
class ModelosAoVivoTest {
    @Test
    void cadaModeloEscreveTextoComVagaECv() throws Exception {
        Path copia = Files.createTempFile("searchjob-live", ".db");
        copia.toFile().deleteOnExit(); // a cópia contém a chave do OpenRouter
        Files.copy(Path.of("data/searchjob.db"), copia, StandardCopyOption.REPLACE_EXISTING);
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + copia);
        JdbcClient jdbc = JdbcClient.create(ds);
        KvRepo kv = new KvRepo(jdbc);
        String cv = jdbc.sql("SELECT texto_extraido FROM cv LIMIT 1").query(String.class).single();
        cv = cv.substring(0, Math.min(1500, cv.length()));
        String user = "VAGA: Desenvolvedor Backend Node.js Pleno, fintech. Requisitos: Node.js, TypeScript, PostgreSQL, AWS, Docker, APIs REST. Remoto.\n\nCV:\n"
                + cv + "\n\nPergunta: Por que você quer trabalhar nesta vaga? (máx 3 frases)";
        String system = "Você escreve respostas curtas em primeira pessoa para candidaturas, em português, usando só fatos do CV.";
        for (String m : System.getProperty("modelos").split(",")) {
            kv.put(KvRepo.CONFIG, "openrouter_modelos", m.trim());
            long t = System.currentTimeMillis();
            try {
                String r = new IaClient(kv).conversar(system, user);
                System.out.printf("LIVE %s OK %.1fs%n%s%n%n", m, (System.currentTimeMillis() - t) / 1000.0, r);
                assertFalse(r.isBlank());
            } catch (RuntimeException e) {
                System.out.printf("LIVE %s FALHOU %.1fs: %s%n%n", m, (System.currentTimeMillis() - t) / 1000.0,
                        String.valueOf(e.getMessage()).substring(0, Math.min(160, String.valueOf(e.getMessage()).length())));
            }
        }
    }
}
