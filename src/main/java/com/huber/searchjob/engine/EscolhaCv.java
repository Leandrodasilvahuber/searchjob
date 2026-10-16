package com.huber.searchjob.engine;

import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.CvRepo;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Escolhe o CV pela stack da vaga usando as palavras-chave de cada CV. Padrão em empate: o de "nodejs". */
@Component
public class EscolhaCv {
    private final CvRepo cvs;

    public EscolhaCv(CvRepo cvs) {
        this.cvs = cvs;
    }

    public Optional<Cv> escolher(Vaga v) {
        List<Cv> todos = cvs.todos();
        if (todos.isEmpty()) return Optional.empty();
        String texto = Texto.normalizar(v.cargo() + " " + v.descricao());
        Cv melhor = null;
        int melhorPontos = -1;
        for (Cv cv : todos) {
            int pontos = pontuar(texto, cv.regrasStack());
            if (pontos > melhorPontos) {
                melhorPontos = pontos;
                melhor = cv;
            }
        }
        if (melhorPontos <= 0) {
            return todos.stream().filter(c -> c.nome().toLowerCase().contains("nodejs")).findFirst()
                    .or(() -> Optional.of(todos.get(0)));
        }
        // empate: prefere o nodejs
        final int max = melhorPontos;
        return todos.stream().filter(c -> pontuar(texto, c.regrasStack()) == max)
                .min(Comparator.comparing((Cv c) -> !c.nome().toLowerCase().contains("nodejs")));
    }

    static int pontuar(String textoNormalizado, String regras) {
        if (regras == null || regras.isBlank()) return 0;
        return (int) Arrays.stream(regras.split(",")).map(Texto::normalizar)
                .filter(r -> !r.isEmpty() && textoNormalizado.contains(r)).count();
    }
}
