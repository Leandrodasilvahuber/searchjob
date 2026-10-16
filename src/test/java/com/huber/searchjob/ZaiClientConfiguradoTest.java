package com.huber.searchjob;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.engine.ZaiClient;
import org.junit.jupiter.api.Test;

class ZaiClientConfiguradoTest {
    @Test
    void configuradoDependeSoDaChaveDoOpenRouter() {
        KvRepo kv = mock(KvRepo.class);
        when(kv.get(any(), any(), any())).thenAnswer(i -> i.getArgument(2));
        when(kv.get(KvRepo.CONFIG, "zai_chave", "")).thenReturn("chave-antiga-da-zai");
        assertFalse(new ZaiClient(kv).configurado(), "chave antiga da z.ai não pode contar");

        when(kv.get(KvRepo.CONFIG, "openrouter_chave", "")).thenReturn("sk-or-teste");
        assertTrue(new ZaiClient(kv).configurado());
    }
}
