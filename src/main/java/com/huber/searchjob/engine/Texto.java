package com.huber.searchjob.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;
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
        return Arrays.stream(normalizar(s).split(" ")).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
    }

    private static final Set<String> PALAVRAS_EN = Set.of("the", "and", "with", "for", "you", "your", "will", "our", "we",
            "of", "to", "in", "is", "are", "have", "be", "as", "on", "or", "this", "that", "experience", "work", "team");
    private static final Set<String> PALAVRAS_PT = Set.of("de", "da", "do", "das", "dos", "para", "com", "uma", "que", "em",
            "os", "as", "na", "no", "nas", "nos", "voce", "ou", "um", "por", "se", "experiencia", "equipe", "vaga");

    /** Heurística por palavras funcionais: texto curto demais para decidir não conta como inglês. */
    public static boolean emIngles(String texto) {
        return emIngles(texto, 8);
    }

    /** Igual a {@link #emIngles(String)}, com mínimo próprio de palavras inglesas (formulários são curtos). */
    public static boolean emIngles(String texto, int minimo) {
        long en = 0, pt = 0;
        for (String t : normalizar(texto).split(" ")) {
            if (PALAVRAS_EN.contains(t)) en++;
            if (PALAVRAS_PT.contains(t)) pt++;
        }
        return en >= minimo && en > 2 * pt;
    }

    private static final Set<String> PERGUNTA_EN = Set.of("what", "how", "many", "years", "do", "does", "did", "have", "has",
            "would", "like", "are", "willing", "current", "your", "you", "which", "can", "if", "please", "salary", "expected",
            "completed", "level", "education", "degree", "able", "authorized", "require", "sponsorship", "located",
            "currently", "english", "the", "and", "with", "for", "of", "to", "in", "is", "experience", "work", "my");
    private static final Set<String> PERGUNTA_PT = Set.of("qual", "quais", "quantos", "possui", "tem", "voce", "sua", "seu",
            "anos", "sim", "nao", "e", "de", "da", "do", "para", "com", "uma", "que", "em", "na", "no", "um", "por", "se",
            "experiencia", "vaga", "como", "aceita", "sobre");

    /** Pergunta de formulário (curta) em inglês: 2+ palavras inglesas e mais inglesas que portuguesas; "LinkedIn Profile" não conta. */
    public static boolean perguntaEmIngles(String pergunta) {
        long en = 0, pt = 0;
        for (String t : normalizar(pergunta).split(" ")) {
            if (PERGUNTA_EN.contains(t)) en++;
            if (PERGUNTA_PT.contains(t)) pt++;
        }
        return en >= 2 && en > pt;
    }

    private static final Set<String> STOP = Set.of("o", "a", "os", "as", "um", "uma", "de", "da", "do", "das", "dos",
            "em", "no", "na", "nos", "nas", "para", "por", "com", "que", "voce", "sua", "seu", "suas", "seus", "tem",
            "possui", "qual", "quais", "e", "ou", "ao", "pelo", "pela", "se", "ja", "aqui", "esta", "este", "essa", "esse");

    /**
     * Duas perguntas são equivalentes quando são muito parecidas E as palavras que diferem são só conectivos.
     * Assim "experiência com Go" nunca reaproveita a resposta de "experiência com R", nem "CNH A" a de "CNH B".
     */
    public static boolean equivalentes(String a, String b) {
        if (similaridade(a, b) < 0.8) return false;
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        return Stream.concat(ta.stream().filter(t -> !tb.contains(t)), tb.stream().filter(t -> !ta.contains(t)))
                .allMatch(STOP::contains);
    }

    /** O termo (já normalizável) aparece como palavra inteira no texto? */
    public static boolean contemPalavra(String texto, String termo) {
        String t = normalizar(termo);
        if (t.isEmpty()) return false;
        return java.util.regex.Pattern.compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(t) + "(?![a-z0-9])")
                .matcher(normalizar(texto)).find();
    }

    /** Similaridade de Jaccard entre os conjuntos de palavras. */
    public static double similaridade(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        long inter = ta.stream().filter(tb::contains).count();
        return (double) inter / (ta.size() + tb.size() - inter);
    }
}
