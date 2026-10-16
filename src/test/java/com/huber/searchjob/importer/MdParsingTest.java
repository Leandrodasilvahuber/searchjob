package com.huber.searchjob.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class MdParsingTest {
    @Test
    void dadosFixosSeparamChaveEValorEIgnoramObservacoes() {
        assertArrayEquals(new String[] {"E-mail", "a@b.com"}, MdImporter.parseDado("- E-mail: a@b.com"));
        assertArrayEquals(new String[] {"CPF", "123"},
                MdImporter.parseDado("- CPF: 123 (**usuário autorizou salvar**)"));
        assertNull(MdImporter.parseDado("texto solto"));
        assertNull(MdImporter.parseDado("- sem valor:"));
    }

    @Test
    void celulasRespeitamPipeEscapadoEIgnoramSeparadorECabecalho() {
        assertEquals(List.of("a | b", "c"), MdImporter.celulas("| a \\| b | c |"));
        assertNull(MdImporter.celulas("|---|---|"));
        assertNull(MdImporter.celulas("| Empresa | Cargo |"));
        assertNull(MdImporter.celulas("não é tabela"));
    }

    @Test
    void regrasPadraoPorNomeDoCv() {
        assertEquals("node,typescript,express,full stack js,react,next", MdImporter.regrasPadrao("Leandro_Huber_CV_NodeJS"));
        assertEquals("php,laravel,vue,cakephp,symfony", MdImporter.regrasPadrao("Leandro_Huber_CV_PHP_Laravel"));
        assertEquals("", MdImporter.regrasPadrao("Outro"));
    }
}
