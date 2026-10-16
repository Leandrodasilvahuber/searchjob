package com.huber.searchjob.engine;

import com.huber.searchjob.db.KvRepo;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Filtros por código (sem LLM). Devolve o motivo do descarte, ou vazio se a vaga passa. */
@Component
public class FiltroVaga {
    private final KvRepo kv;

    public FiltroVaga(KvRepo kv) {
        this.kv = kv;
    }

    public Optional<String> motivoDescarte(Vaga v, LocalDate hoje) {
        int dias = kv.inteiro(KvRepo.PREF, "recencia_dias", 14);
        if (v.publicada() != null && v.publicada().isBefore(hoje.minusDays(dias)))
            return Optional.of("fora da recência (" + dias + " dias)");

        String titulo = Texto.normalizar(v.cargo());
        for (String t : lista("excluir_titulos"))
            if (Texto.contemPalavra(titulo, t)) return Optional.of("tipo de vaga excluído: " + t);

        String tudo = Texto.normalizar(v.cargo() + " " + v.descricao());
        for (String t : lista("exige_ingles_oral"))
            if (Texto.contemPalavra(tudo, t)) return Optional.of("exige inglês oral: " + t);
        for (String t : lista("stack_proibida"))
            if (!t.isEmpty() && stackExclusiva(titulo, tudo, t)) return Optional.of("stack fora do perfil: " + t);

        if (v.local() != null && Texto.normalizar(v.local()).matches(".*\\b(portugal|estados unidos|usa|canada|espanha)\\b.*"))
            return Optional.of("vaga fora do Brasil");
        return Optional.empty();
    }

    /**
     * Termo simples: descarta se aparece no título (a descrição cita muita tecnologia de apoio).
     * Termo "x-only" (normalizado "x only"): descarta se x está no título e nem o título nem a descrição citam alguma stack aceita
     * (preferência stack_aceita; padrão node, php, laravel, react, vue, typescript).
     */
    private boolean stackExclusiva(String titulo, String tudo, String termo) {
        // os termos chegam normalizados ("java-only" vira "java only")
        if (!termo.endsWith(" only")) return Texto.contemPalavra(titulo, termo);
        String base = termo.substring(0, termo.length() - " only".length());
        if (!Texto.contemPalavra(titulo, base)) return false;
        for (String aceita : listaOu("stack_aceita", "node,php,laravel,react,vue,typescript"))
            if (Texto.contemPalavra(tudo, aceita)) return false;
        return true;
    }

    private List<String> listaOu(String chave, String padrao) {
        return Arrays.stream(kv.get(KvRepo.PREF, chave, padrao).split(","))
                .map(Texto::normalizar).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> lista(String chave) {
        return Arrays.stream(kv.get(KvRepo.PREF, chave, "").split(","))
                .map(Texto::normalizar).filter(s -> !s.isEmpty()).toList();
    }
}
