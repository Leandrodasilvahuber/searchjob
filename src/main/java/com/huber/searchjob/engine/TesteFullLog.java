package com.huber.searchjob.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/** TEMP-TESTE-FULL: grava na Área de Trabalho tudo que a IA escreve e as estatísticas da sessão. Remover após o teste full inicial. */
final class TesteFullLog {
    static final Path PASTA = Path.of(System.getProperty("user.home"), "Área de Trabalho");
    static final Path TEXTOS = PASTA.resolve("searchjob-textos-ia.log");
    static final Path STATS = PASTA.resolve("searchjob-estatisticas.txt");

    private TesteFullLog() {}

    static synchronized void texto(String modelo, String pedido, String resposta) {
        String p = pedido.length() > 600 ? pedido.substring(0, 600) + "…" : pedido;
        anexar(TEXTOS, "===== " + LocalDateTime.now().withNano(0) + " · " + modelo + " =====\n--- PEDIDO (início) ---\n"
                + p + "\n--- RESPOSTA ---\n" + resposta + "\n\n");
    }

    static synchronized void falha(String modelo, String erro) {
        anexar(TEXTOS, "===== " + LocalDateTime.now().withNano(0) + " · " + modelo + " FALHOU =====\n" + erro + "\n\n");
    }

    static synchronized void estatisticas(String texto) {
        anexar(STATS, "===== " + LocalDateTime.now().withNano(0) + " =====\n" + texto + "\n\n");
    }

    /** Ligado pelo checkbox "Gerar relatório na Área de Trabalho" do painel (config relatorio_desktop). */
    static volatile boolean ativo;

    private static void anexar(Path arq, String s) {
        if (!ativo) return;
        try {
            Files.createDirectories(PASTA);
            Files.writeString(arq, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // log de teste: nunca derruba a sessão
        }
    }
}
