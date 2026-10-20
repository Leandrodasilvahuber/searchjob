package com.huber.searchjob.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NavegadorFirefoxTest {

    @Test
    void apagaLockDeixadoPorFirefoxQueJaMorreu(@TempDir Path perfil) throws Exception {
        Files.createSymbolicLink(perfil.resolve("lock"), Path.of("127.0.1.1:+4010486"));
        Files.createFile(perfil.resolve(".parentlock"));

        assertTrue(NavegadorFirefox.liberarPerfil(perfil));
        assertFalse(Files.exists(perfil.resolve("lock"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(perfil.resolve(".parentlock")));
    }

    @Test
    void perfilLivreNaoTemNadaParaLiberar(@TempDir Path perfil) {
        assertFalse(NavegadorFirefox.liberarPerfil(perfil));
    }
}
