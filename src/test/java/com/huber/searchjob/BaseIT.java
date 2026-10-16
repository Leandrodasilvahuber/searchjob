package com.huber.searchjob;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base dos testes de integração: SQLite em arquivo temporário, sem importação automática. */
public abstract class BaseIT {
    static final Path DB;

    static {
        try {
            DB = Files.createTempFile("searchjob-test", ".db");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("searchjob.pasta-origem", () -> "/nao/existe");
        r.add("searchjob.cofre-chave", () -> DB + ".key");
    }
}
