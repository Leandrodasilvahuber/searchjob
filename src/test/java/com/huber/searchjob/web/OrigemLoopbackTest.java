package com.huber.searchjob.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrigemLoopbackTest {
    @Test
    void aceitaSoLoopback() {
        assertTrue(OrigemLocalFilter.loopback("http://localhost:8787"));
        assertTrue(OrigemLocalFilter.loopback("http://127.0.0.1:8787/painel"));
        assertFalse(OrigemLocalFilter.loopback("https://evil.example"));
        assertFalse(OrigemLocalFilter.loopback("http://localhost.evil.example"));
        assertFalse(OrigemLocalFilter.loopback("null"));
        assertFalse(OrigemLocalFilter.loopback(null));
        assertFalse(OrigemLocalFilter.loopback(""));
    }
}
