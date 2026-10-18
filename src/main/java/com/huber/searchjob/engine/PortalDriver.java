package com.huber.searchjob.engine;

import com.huber.searchjob.db.Cv;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface PortalDriver {
    String nome();

    List<Vaga> buscar(String termo, int max) throws Exception;

    /** Candidata-se à vaga. Em dry-run, preenche tudo mas não envia. */
    Desfecho candidatar(Vaga vaga, Cv cv, boolean dryRun, BooleanSupplier continuar) throws Exception;

    /**
     * Preenche o login que está aberto no navegador com o usuário/senha salvos. Devolve false se o portal não
     * tem esse recurso ou não há formulário de login visível.
     */
    default boolean entrar(String usuario, String senha) {
        return false;
    }

    /** True se a página aberta no navegador mostra CAPTCHA (o login automático não adianta). */
    default boolean pedeCaptcha() {
        return false;
    }

    /** Descrição curta da página aberta (URL e avisos), para diagnosticar um login que não entrou. */
    default String diagnostico() {
        return "";
    }

    /** Valores digitados agora no formulário de login do navegador: {usuário, senha} (null onde vazio). */
    default String[] lerLogin() {
        return new String[] {null, null};
    }

    /** Completa a vaga antes dos filtros (ex.: descrição que a busca não traz); devolve a própria vaga se não há o que buscar. */
    default Vaga detalhar(Vaga v) {
        return v;
    }

    /** Libera recursos (navegador). */
    default void fechar() {}

    enum Status { ENVIADA, PRONTA_DRY_RUN, PULADA, ERRO, PRECISA_USUARIO }

    record Desfecho(Status status, String motivo) {}
}
