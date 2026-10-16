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
        int dias = Integer.parseInt(kv.get(KvRepo.PREF, "recencia_dias", "14"));
        if (v.publicada() != null && v.publicada().isBefore(hoje.minusDays(dias)))
            return Optional.of("fora da recência (" + dias + " dias)");

        String titulo = Texto.normalizar(v.cargo());
        for (String t : lista("excluir_titulos"))
            if (titulo.contains(t)) return Optional.of("tipo de vaga excluído: " + t);

        String tudo = Texto.normalizar(v.cargo() + " " + v.descricao());
        for (String t : lista("exige_ingles_oral"))
            if (tudo.contains(t)) return Optional.of("exige inglês oral: " + t);
        for (String t : lista("stack_proibida"))
            if (!t.isEmpty() && tituloOuStackExclusiva(titulo, t)) return Optional.of("stack fora do perfil: " + t);

        if (v.local() != null && Texto.normalizar(v.local()).matches(".*\\b(portugal|estados unidos|usa|canada|espanha)\\b.*"))
            return Optional.of("vaga fora do Brasil");
        return Optional.empty();
    }

    /** Só descarta por stack quando o termo aparece no título (descrição cita muita tecnologia de apoio). */
    private static boolean tituloOuStackExclusiva(String titulo, String termo) {
        if (termo.endsWith("-only")) return false; // exige análise do texto: tratado pelo modo z.ai
        return titulo.contains(termo);
    }

    private List<String> lista(String chave) {
        return Arrays.stream(kv.get(KvRepo.PREF, chave, "").split(","))
                .map(Texto::normalizar).filter(s -> !s.isEmpty()).toList();
    }
}
