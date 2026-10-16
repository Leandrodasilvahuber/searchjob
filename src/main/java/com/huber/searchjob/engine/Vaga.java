package com.huber.searchjob.engine;

import java.time.LocalDate;

public record Vaga(String portal, String id, String empresa, String cargo, String url, String local,
                   String modalidade, LocalDate publicada, String descricao) {}
