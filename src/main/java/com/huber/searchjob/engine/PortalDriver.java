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

    /**
     * Abre no navegador uma página que só existe com a sessão ativa e diz se está logado. Null se o portal não sabe
     * conferir (ex.: busca e candidatura sem conta) ou se o navegador falhou.
     */
    default Boolean logado() {
        return null;
    }

    /**
     * Abre a tela de login do portal no navegador, preferindo "Entrar com LinkedIn" quando existe. Devolve true se
     * clicou no LinkedIn; false se ficou no formulário do portal (ou se o portal não tem tela de login).
     */
    default boolean abrirLogin() {
        return false;
    }

    /** True se o LinkedIn/Google está pedindo confirmação no celular (só o usuário resolve). */
    default boolean pedeCelular() {
        return false;
    }

    /** Na tela de login aberta, entra por "Entrar com Google" com a conta {@code email}. False se não há o botão. */
    default boolean entrarComGoogle(String email) {
        return false;
    }

    /** Abre a tela de login do portal sem clicar no LinkedIn (formulário e-mail/senha). False se o portal não tem. */
    default boolean abrirFormularioLogin() {
        return false;
    }

    /** True se a aba aberta é uma tela de login (do portal, LinkedIn ou Google). Portal sem navegador: true (nada a abrir). */
    default boolean telaDeLoginAberta() {
        return true;
    }

    /** True se a página aberta no navegador mostra CAPTCHA (o login automático não adianta). */
    default boolean pedeCaptcha() {
        return false;
    }

    /** Descrição curta da página aberta (URL e avisos), para diagnosticar um login que não entrou. */
    default String diagnostico() {
        return "";
    }

    /** Preenche o login da página aberta agora (ex.: a do LinkedIn, vinda de "Entrar com LinkedIn"), sem navegar antes. */
    default boolean entrarNaPaginaAberta(String usuario, String senha) {
        return false;
    }

    /** Valores digitados agora no formulário de login do navegador: {usuário, senha} (null onde vazio). */
    default String[] lerLogin() {
        return new String[] {null, null};
    }

    /** Completa a vaga antes dos filtros (ex.: descrição que a busca não traz); devolve a própria vaga se não há o que buscar. */
    default Vaga detalhar(Vaga v) {
        return v;
    }

    /** Recebe o log da sessão, para o driver contar o que está fazendo (ex.: os passos no site da empresa). */
    default void observar(java.util.function.Consumer<String> log) {}

    /** Libera recursos (navegador). */
    default void fechar() {}

    enum Status { ENVIADA, PRONTA_DRY_RUN, PULADA, ERRO, PRECISA_USUARIO }

    record Desfecho(Status status, String motivo) {}
}
