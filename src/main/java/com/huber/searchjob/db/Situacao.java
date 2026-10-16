package com.huber.searchjob.db;

public record Situacao(Long id, String portal, String tipo, String padraoPergunta, String hash,
                       String promptOuResposta, String origem, String status, int usos,
                       String contexto, String criadoEm) {}
