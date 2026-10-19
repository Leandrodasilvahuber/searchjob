package com.huber.searchjob.engine;

import com.huber.searchjob.db.KvRepo;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Descartes objetivos feitos antes da IA (data e idioma). Devolve o motivo, ou vazio se a vaga passa. */
@Component
public class FiltroVaga {
    private final KvRepo kv;

    public FiltroVaga(KvRepo kv) {
        this.kv = kv;
    }

    /** Stack, nível, local e inglês oral são julgamento: ficam com a IA. */
    public Optional<String> motivoRecencia(Vaga v, LocalDate hoje) {
        int dias = kv.inteiro(KvRepo.PREF, "recencia_dias", 14);
        if (v.publicada() != null && v.publicada().isBefore(hoje.minusDays(dias)))
            return Optional.of("fora da recência (" + dias + " dias)");
        return Optional.empty();
    }

    /** Vaga escrita em inglês é sempre descartada. */
    public Optional<String> motivoIdioma(Vaga v) {
        return Texto.emIngles(v.cargo() + " " + v.descricao()) ? Optional.of("vaga em inglês") : Optional.empty();
    }
}
