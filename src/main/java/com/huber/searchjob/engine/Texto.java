package com.huber.searchjob.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

public final class Texto {
    private Texto() {}

    /** Minúsculas, sem acentos nem pontuação, espaços colapsados. */
    public static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    public static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(normalizar(s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Set<String> tokens(String s) {
        return Arrays.stream(normalizar(s).split(" ")).filter(t -> t.length() > 2).collect(Collectors.toSet());
    }

    /** Similaridade de Jaccard entre os conjuntos de palavras. */
    public static double similaridade(String a, String b) {
        Set<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        long inter = ta.stream().filter(tb::contains).count();
        return (double) inter / (ta.size() + tb.size() - inter);
    }
}
