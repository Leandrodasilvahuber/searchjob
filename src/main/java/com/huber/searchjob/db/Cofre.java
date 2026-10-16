package com.huber.searchjob.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra as senhas dos portais (AES-256-GCM) antes de irem ao SQLite. A chave fica num arquivo próprio
 * (data/cofre.key, permissão 600), separado do banco: copiar só o banco não revela as senhas.
 * Não protege contra quem já tem acesso à sua conta de usuário na máquina.
 */
@Component
public class Cofre {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final Path arquivoChave;
    private SecretKey chave;

    public Cofre(@Value("${searchjob.cofre-chave:data/cofre.key}") String arquivoChave) {
        this.arquivoChave = Path.of(arquivoChave).toAbsolutePath();
    }

    public String cifrar(String claro) {
        try {
            byte[] iv = new byte[IV_BYTES];
            ALEATORIO.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, chave(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(claro.getBytes(StandardCharsets.UTF_8));
            byte[] saida = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, saida, 0, iv.length);
            System.arraycopy(ct, 0, saida, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(saida);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar a senha", e);
        }
    }

    public String decifrar(String cifrado) {
        try {
            byte[] tudo = Base64.getDecoder().decode(cifrado);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, chave(), new GCMParameterSpec(TAG_BITS, tudo, 0, IV_BYTES));
            return new String(c.doFinal(tudo, IV_BYTES, tudo.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Não foi possível decifrar a senha (a chave " + arquivoChave
                    + " mudou?). Cadastre a senha de novo.", e);
        }
    }

    private synchronized SecretKey chave() {
        if (chave == null) {
            try {
                if (!Files.exists(arquivoChave)) {
                    byte[] nova = new byte[32];
                    ALEATORIO.nextBytes(nova);
                    Path pasta = arquivoChave.getParent();
                    if (pasta != null) Files.createDirectories(pasta);
                    try {
                        Files.write(Files.createFile(arquivoChave,
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))), nova);
                    } catch (UnsupportedOperationException e) {
                        Files.write(arquivoChave, nova); // sistema sem permissões POSIX
                    }
                }
                chave = new SecretKeySpec(Files.readAllBytes(arquivoChave), "AES");
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível ler/criar a chave do cofre " + arquivoChave, e);
            }
        }
        return chave;
    }
}
