package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huber.searchjob.db.IaProvedorRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.engine.IaClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class IaClientConfiguradoTest {
    private static IaProvedorRepo.Provedor provedor(String chave, boolean ativo, List<String> modelos) {
        return new IaProvedorRepo.Provedor(1, "P", "http://x", chave, modelos, 0, ativo, 0, 10, 1);
    }

    @Test
    void basta_um_provedor_ativo_com_chave_e_modelos() {
        IaProvedorRepo repo = mock(IaProvedorRepo.class);
        IaClient ia = new IaClient(mock(KvRepo.class), repo, null);
        when(repo.todos()).thenReturn(List.of(provedor("", true, List.of("m")), provedor("k", false, List.of("m"))));
        assertFalse(ia.configurado(), "sem chave ou inativo não conta");
        when(repo.todos()).thenReturn(List.of(provedor("k", true, List.of())));
        assertFalse(ia.configurado(), "sem modelos não conta");
        when(repo.todos()).thenReturn(List.of(provedor("", true, List.of("m")), provedor("k", true, List.of("m"))));
        assertTrue(ia.configurado());
    }
}
