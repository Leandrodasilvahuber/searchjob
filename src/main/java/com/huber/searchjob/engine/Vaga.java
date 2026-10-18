package com.huber.searchjob.engine;

import java.time.LocalDate;
import java.util.Comparator;

public record Vaga(String portal, String id, String empresa, String cargo, String url, String local,
                   String modalidade, LocalDate publicada, String descricao) {
    /** Mais recentes primeiro; vaga sem data vai para o fim (a ordem do portal entre elas é mantida: o sort é estável). */
    public static final Comparator<Vaga> MAIS_RECENTE = Comparator.comparing(Vaga::publicada,
            Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()));
}
