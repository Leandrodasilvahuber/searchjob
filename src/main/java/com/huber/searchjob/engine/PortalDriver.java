package com.huber.searchjob.engine;

import com.huber.searchjob.db.Cv;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface PortalDriver {
    String nome();

    List<Vaga> buscar(String termo, int max) throws Exception;

    /** Candidata-se à vaga. Em dry-run, preenche tudo mas não envia. */
    Desfecho candidatar(Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar) throws Exception;

    /** Libera recursos (navegador). */
    default void fechar() {}

    enum Status { ENVIADA, PRONTA_DRY_RUN, PULADA, ERRO, PRECISA_USUARIO }

    record Desfecho(Status status, String motivo) {}
}
