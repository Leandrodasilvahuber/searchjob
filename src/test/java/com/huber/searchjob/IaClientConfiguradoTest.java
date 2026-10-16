package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.engine.IaClient;
import org.junit.jupiter.api.Test;

class IaClientConfiguradoTest {
    @Test
    void configuradoDependeSoDaChaveDoOpenRouter() {
        KvRepo kv = mock(KvRepo.class);
        when(kv.get(any(), any(), any())).thenAnswer(i -> i.getArgument(2));

        when(kv.get(KvRepo.CONFIG, "openrouter_chave", "")).thenReturn("sk-or-teste");
        assertTrue(new IaClient(kv).configurado());
    }
}
