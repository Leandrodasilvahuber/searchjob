package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.CredencialRepo;
import com.huber.searchjob.db.PortalRepo;
import com.huber.searchjob.db.CvRepo;
import com.huber.searchjob.db.KvRepo;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/** Executa a sessão de busca/candidatura em uma thread, vaga por vaga. */
@Service
public class Orquestrador {
    private final KvRepo kv;
    private final CandidaturaRepo candidaturas;
    private final CvRepo cvs;
    private final FiltroVaga filtro;
    private final EscolhaCv escolhaCv;
    private final SituacaoService situacoes;
    private final IaClient ia;
    private final CvContexto cvContexto;
    private final Aviso aviso;
    private final FabricaDrivers fabrica;
    private final CredencialRepo credenciais;
    private final PortalRepo portais;

    /** Pedido de ajuda que pausa a sessão: {@code tipo} é LOGIN (login/CAPTCHA), PENDENCIA (pergunta a responder em Pendências) ou MANUAL (ação na janela do Firefox, ex.: teste do portal). */
    public record Chamado(String tipo, String portal, String vaga, String motivo) {}

    private static final long BIP_A_CADA_MS = 10_000;
    private volatile Chamado chamado;
    /** Motivo de a sessão ter parado sozinha (o painel mostra num alert); vazio se não houve. */
    private volatile String alerta = "";
    /** A IA já caiu de vez nesta sessão (o alerta só abre na primeira vez). */
    private volatile boolean iaJaFalhou;
    private final LinkedBlockingQueue<String> decisoes = new LinkedBlockingQueue<>();

    private volatile boolean rodando;
    private volatile String mensagem = "parado";
    private final AtomicInteger processadas = new AtomicInteger();
    private final AtomicInteger enviadas = new AtomicInteger();
    private final AtomicInteger puladas = new AtomicInteger();
    private final AtomicInteger erros = new AtomicInteger();
    private final AtomicInteger encontradasTotal = new AtomicInteger(); // TEMP-TESTE-FULL
    private final AtomicInteger jaNoHistorico = new AtomicInteger(); // TEMP-TESTE-FULL
    private volatile long idInicial; // TEMP-TESTE-FULL
    private final Deque<Evento> log = new ArrayDeque<>();
    private Thread thread;
    private final Set<String> bloqueados = new HashSet<>();

    public Orquestrador(KvRepo kv, CandidaturaRepo candidaturas, CvRepo cvs, FiltroVaga filtro, EscolhaCv escolhaCv,
                        SituacaoService situacoes, IaClient ia, CvContexto cvContexto, Aviso aviso,
                        FabricaDrivers fabrica, CredencialRepo credenciais, PortalRepo portais) {
        this.fabrica = fabrica;
        this.credenciais = credenciais;
        this.portais = portais;
        this.kv = kv;
        this.candidaturas = candidaturas;
        this.cvs = cvs;
        this.filtro = filtro;
        this.escolhaCv = escolhaCv;
        this.situacoes = situacoes;
        this.ia = ia;
        this.cvContexto = cvContexto;
        this.aviso = aviso;
    }

    public record Estado(boolean rodando, String mensagem, int processadas, int enviadas, int puladas, int erros,
                         List<Evento> log, Chamado chamado, String alerta) {}

    /** Linha do log: tipo = info|encontrada|aberta|preenchida|enviada|aguardando|pulada|erro. */
    public record Evento(String hora, String tipo, String texto, String detalhe) {}

    public synchronized Estado estado() {
        // depois do Parar a thread ainda fecha o Firefox: segue "rodando" na tela (com o Parar) até terminar de fato
        boolean ativo = rodando || thread != null && thread.isAlive();
        return new Estado(ativo, mensagem, processadas.get(), enviadas.get(), puladas.get(), erros.get(),
                new ArrayList<>(log), chamadoAtual(), alerta);
    }

    /** Chamado do login/CAPTCHA ou, se a vaga está parada numa pergunta sem resposta, a pendência dela. */
    private Chamado chamadoAtual() {
        Chamado c = chamado;
        if (c != null) return c;
        SituacaoService.Espera e = situacoes.esperando();
        return e == null ? null : new Chamado("PENDENCIA", e.portal(), "", e.pergunta().replace(Checkboxes.MARCADOR, "") + " — responda em Pendências");
    }

    /** Decisão do usuário para o chamado aberto: "continuar" (já logou/resolveu) ou "pular". */
    public void decidir(String decisao) {
        if (chamado == null && "pular".equals(decisao)) situacoes.pularEspera();
        if (chamado != null && ("continuar".equals(decisao) || "pular".equals(decisao))) decisoes.offer(decisao);
    }

    /**
     * Pausa a sessão até o usuário decidir, repetindo o bip a cada 10 s. O bip só para quando o usuário clica em Resolvido ou Pular. Devolve "continuar" ou "pular" ("pular" se a sessão for parada).
     */
    private String aguardar(Chamado c) {
        return aguardar(c, () -> { });
    }

    /** Igual a {@link #aguardar(Chamado)}, mas chama {@code aCadaSegundo} enquanto espera (ex.: capturar o login digitado). */
    private String aguardar(Chamado c, Runnable aCadaSegundo) {
        return aguardar(c, aCadaSegundo, () -> false);
    }

    /** Igual a {@link #aguardar(Chamado, Runnable)}, mas segue sozinho ("continuar") assim que {@code resolvido} for true (ex.: pendência respondida). */
    private String aguardar(Chamado c, Runnable aCadaSegundo, java.util.function.BooleanSupplier resolvido) {
        if (!c.tipo().equals("LOGIN") && kv.booleano(KvRepo.CONFIG, "nao_interagir", false)) {
            registrar("não interagir: pulando sem chamar você (" + c.motivo() + ")");
            return "pular";
        }
        decisoes.clear();
        chamado = c;
        registrar("aguardando", (c.tipo().equals("LOGIN")
                ? "não logado em " + c.portal() + ", aguardando você entrar" : "vaga aguardando intervenção: pendência em " + c.portal())
                + " (" + c.motivo() + ")", c.tipo());
        long ultimoBip = 0;
        try {
            while (rodando) {
                long agora = System.currentTimeMillis();
                if (agora - ultimoBip >= BIP_A_CADA_MS) {
                    ultimoBip = agora;
                    if (c.tipo().equals("LOGIN")) aviso.login();
                    else aviso.chamarUsuario();
                }
                String d = decisoes.poll(1, TimeUnit.SECONDS);
                if (d != null) return d;
                if (resolvido.getAsBoolean()) return "continuar";
                aCadaSegundo.run();
            }
            return "pular";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "pular";
        } finally {
            chamado = null;
        }
    }

    /** Guarda o último valor não vazio de cada campo (em logins de duas etapas, usuário e senha aparecem em páginas diferentes). */
    private static void acumular(String[] digitado, String[] lido) {
        for (int i = 0; i < 2; i++) if (lido[i] != null) digitado[i] = lido[i];
    }

    private void salvarLoginDigitado(String portal, String[] digitado) {
        if (digitado[0] == null || digitado[1] == null) return;
        try {
            credenciais.salvar(portal, digitado[0], digitado[1]);
            registrar("login de " + portal + " salvo (" + digitado[0] + ") — das próximas vezes entro sozinho");
        } catch (RuntimeException e) {
            registrar("não consegui salvar o login de " + portal + ": " + e.getMessage());
        }
    }

    private static boolean pedeLogin(String motivo) {
        return motivo.contains("login") && !motivo.toLowerCase().contains("captcha") && !externo(motivo);
    }

    /** Login ativo do portal salvo em Portais e IA. */
    private Optional<CredencialRepo.Login> loginProprio(String portal) {
        return credenciais.ativa(portal);
    }

    /** Conta Google para "Entrar com Google": a conta Google cadastrada ou, sem ela, o usuário do login do portal. */
    private String emailGoogle(String portal) {
        try {
            return loginProprio(PortalRepo.GOOGLE).or(() -> loginProprio(portal)).map(CredencialRepo.Login::usuario).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private boolean temLoginProprio(String portal) {
        try {
            return loginProprio(portal).isPresent();
        } catch (RuntimeException e) {
            return false; // login salvo ilegível: entrarAutomatico registra o motivo
        }
    }

    /** Tem como entrar: login do próprio portal ou a conta do modo escolhido (Google ou LinkedIn) salva em Portais e IA. */
    private boolean configurado(PortalRepo.Portal p) {
        if (temLoginProprio(p.chave())) return true;
        return switch (p.modoLogin()) {
            case PortalRepo.GOOGLE -> temLoginProprio(PortalRepo.GOOGLE);
            case PortalRepo.LINKEDIN -> temLoginProprio(PortalRepo.LINKEDIN);
            default -> false;
        };
    }

    /** Usa o login próprio do portal (Portais e IA). Devolve true se preencheu e enviou o formulário. */
    private boolean entrarAutomatico(PortalDriver driver) {
        Optional<CredencialRepo.Login> login;
        try {
            login = loginProprio(driver.nome());
        } catch (RuntimeException e) {
            registrar("login salvo de " + driver.nome() + " ilegível: " + e.getMessage());
            return false;
        }
        if (login.isEmpty()) {
            // sem login próprio: "Entrar com LinkedIn" (Sólides, Gupy) com a sessão ou o login salvo do LinkedIn
            if (driver.abrirLogin()) {
                registrar(driver.nome() + ": sem login salvo — tentando \"Entrar com LinkedIn\"");
                return Boolean.TRUE.equals(driver.logado()) || entrarNoLinkedin(driver);
            }
            registrar("sem login salvo para " + driver.nome() + " — cadastre em Portais e IA para entrar sozinho");
            return false;
        }
        try {
            registrar("entrando em " + driver.nome() + " com " + login.get().usuario());
            boolean enviou = driver.entrar(login.get().usuario(), login.get().senha());
            registrar(enviou ? "login enviado em " + driver.nome() + " — " + driver.diagnostico()
                    : "não achei formulário de login em " + driver.nome() + " — " + driver.diagnostico());
            return enviou;
        } catch (RuntimeException e) {
            registrar("login automático em " + driver.nome() + " falhou: " + e.getMessage());
            return false;
        }
    }

    /** Na tela de login do LinkedIn aberta pelo "Entrar com LinkedIn" de outro portal, preenche o login salvo do LinkedIn. */
    private boolean entrarNoLinkedin(PortalDriver driver) {
        Optional<CredencialRepo.Login> login;
        try {
            login = loginProprio("linkedin");
        } catch (RuntimeException e) {
            return false;
        }
        if (login.isEmpty()) return false;
        registrar(driver.nome() + ": entrando no LinkedIn com " + login.get().usuario());
        boolean enviou = driver.entrarNaPaginaAberta(login.get().usuario(), login.get().senha());
        if (enviou) dormir(4000); // o LinkedIn devolve para o portal, que troca o código pela sessão
        return enviou;
    }

    /**
     * Antes de chamar você para entrar: se a aba não está numa tela de login (o "Entrar com LinkedIn" redirecionou, a
     * vaga parou em outra página), abre a do portal pelo LinkedIn e, se nem assim, o formulário e-mail/senha.
     */
    private void garantirTelaDeLogin(PortalDriver driver) {
        try {
            if (driver.telaDeLoginAberta()) return;
            driver.abrirLogin();
            if (!driver.telaDeLoginAberta()) driver.abrirFormularioLogin();
            if (!driver.telaDeLoginAberta()) registrar(driver.nome() + ": não consegui deixar a tela de login aberta — " + driver.diagnostico());
        } catch (RuntimeException e) {
            registrar(driver.nome() + ": falha ao abrir a tela de login: " + e.getMessage());
        }
    }

    /** Resultado do login automático: entrou, parou na confirmação do celular, ou passou pela tela do LinkedIn. */
    private record Tentativa(boolean logado, boolean celular, boolean viaLinkedin) {}

    /**
     * Login automático: primeiro o modo escolhido para o portal em Portais e IA (Google, LinkedIn ou e-mail e senha do
     * portal) e, se não entrar, os outros na ordem Google → LinkedIn → portal. Confirmação no celular (LinkedIn/Google):
     * para de navegar, para não perder a tela que espera a aprovação.
     */
    private Tentativa tentarEntrar(PortalDriver driver) {
        String modo;
        try {
            modo = portais.modo(driver.nome());
        } catch (RuntimeException e) {
            modo = PortalRepo.GOOGLE;
        }
        Tentativa t = new Tentativa(false, false, false);
        for (String m : PortalRepo.ordemLogin(modo)) {
            t = switch (m) {
                case PortalRepo.GOOGLE -> entrarPeloGoogle(driver, t);
                case PortalRepo.LINKEDIN -> entrarPeloLinkedin(driver, t);
                default -> entrarComSenha(driver, t);
            };
            if (t.logado() || t.celular()) return t;
        }
        return t;
    }

    private Tentativa entrarPeloGoogle(PortalDriver driver, Tentativa t) {
        if (!driver.abrirFormularioLogin() || !driver.entrarComGoogle(emailGoogle(driver.nome()))) return t;
        registrar(driver.nome() + ": tentando \"Entrar com Google\"");
        if (driver.pedeCelular()) return new Tentativa(false, true, t.viaLinkedin());
        if (!Boolean.TRUE.equals(driver.logado())) return t;
        registrar(driver.nome() + ": logado pelo Google");
        return new Tentativa(true, false, t.viaLinkedin());
    }

    private Tentativa entrarPeloLinkedin(PortalDriver driver, Tentativa t) {
        if (!driver.abrirLogin()) return t;
        registrar(driver.nome() + ": tentando \"Entrar com LinkedIn\"");
        if (driver.pedeCelular()) return new Tentativa(false, true, true);
        if (Boolean.TRUE.equals(driver.logado())) {
            registrar(driver.nome() + ": logado pelo LinkedIn");
            return new Tentativa(true, false, true);
        }
        driver.abrirLogin(); // a conferência saiu da tela do LinkedIn: volta para ela e tenta o login salvo
        if (entrarNoLinkedin(driver)) {
            if (driver.pedeCelular()) return new Tentativa(false, true, true);
            if (Boolean.TRUE.equals(driver.logado())) {
                registrar(driver.nome() + ": logado pelo LinkedIn com o login salvo");
                return new Tentativa(true, false, true);
            }
        }
        return new Tentativa(false, false, true);
    }

    private Tentativa entrarComSenha(PortalDriver driver, Tentativa t) {
        if (!temLoginProprio(driver.nome()) || !driver.abrirFormularioLogin()) return t;
        if (entrarAutomatico(driver)) {
            if (driver.pedeCelular()) return new Tentativa(false, true, t.viaLinkedin());
            if (Boolean.TRUE.equals(driver.logado())) {
                registrar(driver.nome() + ": logado com e-mail e senha");
                return new Tentativa(true, false, t.viaLinkedin());
            }
        }
        registrar(driver.nome() + ": e-mail e senha não entraram — " + driver.diagnostico());
        return t;
    }

    /**
     * Ao trocar de portal, confere a sessão antes de buscar. Deslogado, tenta "Entrar com Google", depois "Entrar com
     * LinkedIn" e por último o login próprio do portal (Portais e IA) no formulário e-mail/senha. Se não entrar, deixa a tela de login aberta e chama
     * você (bip até Resolvido ou Pular). Pular bloqueia o portal nesta rodada. Na dúvida (a conferência falhou ou, depois
     * do Resolvido, ainda não confirma o login) também chama você: só segue sem confirmar no segundo Resolvido.
     */
    private void conferirLogin(PortalDriver driver) {
        registrar("trocando para " + driver.nome() + " — aguarde, conferindo o login");
        Boolean logado = driver.logado();
        if (logado == null) logado = driver.logado(); // falha passageira do navegador: confere de novo antes de chamar você
        if (Boolean.TRUE.equals(logado)) {
            registrar(driver.nome() + ": logado");
            return;
        }
        if (logado == null) registrar(driver.nome() + ": não consegui confirmar o login — " + driver.diagnostico());
        Tentativa t = tentarEntrar(driver);
        if (t.logado()) return;
        boolean celular = t.celular();
        boolean viaLinkedin = t.viaLinkedin();
        if (!celular) {
            driver.abrirLogin(); // a conferência saiu da tela de login: volta para ela antes de chamar você
            garantirTelaDeLogin(driver);
        }
        String motivo = celular
                ? "o LinkedIn/Google mandou uma confirmação para o seu celular: aprove no celular e clique em Resolvido"
                : driver.pedeCaptcha()
                ? "o portal exige CAPTCHA: resolva e entre manualmente (marque \"lembrar-me\" para a sessão durar)"
                : viaLinkedin ? "a tela de login (LinkedIn ou do portal) está aberta no Firefox: entre (ou autorize) e clique em Resolvido"
                : "a tela de login está aberta no Firefox; o login digitado será salvo para entrar sozinho nas próximas vezes";
        motivo = (logado == null ? "não consegui confirmar se está logado em " : "não logado em ") + driver.nome() + " — " + motivo;
        for (int vez = 1; ; vez++) {
            String[] digitado = {null, null};
            String decisao = aguardar(new Chamado("LOGIN", driver.nome(), "início", motivo), () -> acumular(digitado, driver.lerLogin()));
            if ("pular".equals(decisao)) {
                bloqueados.add(driver.nome());
                registrar(driver.nome() + " pulado nesta rodada");
                return;
            }
            salvarLoginDigitado(viaLinkedin ? "LinkedIn" : driver.nome(), digitado); // digitado na tela do LinkedIn é login do LinkedIn
            if (Boolean.TRUE.equals(driver.logado())) {
                registrar(driver.nome() + ": logado");
                return;
            }
            // a checagem pode errar: no segundo Resolvido segue de qualquer jeito, só avisa
            if (vez >= 2) {
                registrar(driver.nome() + ": ainda não confirmei o login — seguindo porque você confirmou; se a candidatura pedir login, chamo de novo");
                return;
            }
            motivo = "ainda não consegui confirmar o login em " + driver.nome() + " (" + driver.diagnostico()
                    + "). Confira na janela do Firefox; se já está logado, clique em Resolvido de novo para seguir";
        }
    }

    /**
     * Prefixo do motivo de pular quando o chamado não tem como virar formulário em Pendências; null se tem (ou se é
     * ação manual no portal, como o teste da Sólides).
     */
    private static String semFormulario(String motivo) {
        if (motivo.startsWith("sem resposta para:")) return "pulada pelo usuário: "; // você pulou ou descartou a pendência enquanto a vaga esperava
        if (motivo.startsWith("dado sensível:")) return "pulada (dado sensível, não é salvo como resposta): ";
        if (motivo.contains("ilegível")) return "pulada (campo sem pergunta legível): ";
        return null;
    }

    private static boolean ehLogin(String motivo) {
        return (motivo.contains("login") || motivo.toLowerCase().contains("captcha")) && !externo(motivo);
    }

    /** Chamado do site da empresa (Full search): sempre ação manual no Firefox, mesmo falando de login ou CAPTCHA. */
    private static boolean externo(String motivo) {
        return motivo.startsWith("site externo:");
    }

    /** Sessão normal: todos os portais configurados, na ordem de Portais e IA. */
    public synchronized void iniciar() {
        iniciar(false);
    }

    /**
     * {@code full}: Full search — além do fluxo normal, as vagas com candidatura no site da empresa são conduzidas pela IA
     * (criando conta quando o site pede) em vez de puladas; no LinkedIn entram também as vagas sem Candidatura simplificada.
     */
    public synchronized void iniciar(boolean full) {
        if (rodando) return;
        if (thread != null && thread.isAlive()) {
            registrar("a sessão anterior ainda está encerrando — aguarde alguns segundos");
            return;
        }
        rodando = true;
        processadas.set(0);
        enviadas.set(0);
        puladas.set(0);
        erros.set(0);
        alerta = "";
        iaJaFalhou = false;
        log.clear();
        thread = new Thread(() -> executar(full), "searchjob-sessao");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void limparLog() {
        log.clear();
    }

    public synchronized void parar() {
        rodando = false;
        mensagem = "parando…";
        if (thread != null) thread.interrupt();
    }

    /** Espera a thread da sessão terminar (e o Firefox fechar), por no máximo {@code ms}. */
    public void aguardarFim(long ms) throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null && t != Thread.currentThread()) t.join(ms);
    }

    private void registrar(String linha) {
        registrar("info", linha, null);
    }

    private void registrar(String tipo, String linha, String detalhe) {
        synchronized (this) {
            log.addFirst(new Evento(java.time.LocalTime.now().withNano(0).toString(), tipo, linha, detalhe));
            while (log.size() > 80) log.removeLast();
            mensagem = linha;
        }
    }

    private void executar(boolean full) {
        NavegadorFirefox navegador = new NavegadorFirefox();
        List<PortalDriver> drivers = new ArrayList<>();
        try {
            // portais fixos (os de Portais e IA, na ordem de lá); sem login configurado o portal fica de fora
            for (PortalRepo.Portal p : portais.todos()) {
                if (!configurado(p)) {
                    registrar(p.nome() + ": sem login configurado em Portais e IA — pulado");
                    continue;
                }
                Optional<PortalDriver> d = full ? fabrica.criarFull(p.chave(), navegador) : fabrica.criar(p.chave(), navegador);
                if (d.isPresent()) drivers.add(d.get());
                else registrar("portal \"" + p.nome() + "\" ainda não é suportado — ignorado");
            }
            boolean dryRun = Boolean.parseBoolean(kv.get(KvRepo.CONFIG, "dry_run", "true"));
            int max = kv.inteiro(KvRepo.CONFIG, "max_vagas", 30);
            drivers.forEach(d -> d.observar(this::registrar));
            registrar((full ? "Full search iniciado (vagas externas pela IA)" : "sessão iniciada")
                    + (dryRun ? " · dry-run" : ""));
            encontradasTotal.set(0); // TEMP-TESTE-FULL
            jaNoHistorico.set(0); // TEMP-TESTE-FULL
            idInicial = candidaturas.listar("", "").stream().mapToLong(c -> c.id()).max().orElse(0); // TEMP-TESTE-FULL
            Set<String> vistas = new HashSet<>();
            Set<String> feitas = new HashSet<>();
            bloqueados.clear();
            candidaturas.urlsProcessadas(dryRun).forEach(u -> feitas.add(ChaveVaga.de(u)));
            for (PortalDriver driver : drivers) {
                if (!rodando) break;
                conferirLogin(driver);
                int novas = 0; // o limite de vagas novas vale por portal, somando todos os termos
                for (String termo : kv.get(KvRepo.PREF, "termos", "").split(",")) {
                    if (!rodando || termo.isBlank() || bloqueados.contains(driver.nome()) || novas >= max) continue;
                    registrar(driver.nome() + ": buscando \"" + termo.trim() + "\"");
                    List<Vaga> vagas = null;
                    while (vagas == null) {
                        try {
                            vagas = driver.buscar(termo.trim(), max + candidaturas.contarProcessadas(driver.nome(), dryRun)); // o limite vale para vagas novas
                        } catch (Exception e) {
                            registrar("erro na busca (" + driver.nome() + "): " + e.getMessage());
                            if (e.getMessage() == null || !(e.getMessage().contains("CAPTCHA") || e.getMessage().contains("LOGIN necessário"))) break;
                            if (e.getMessage().contains("LOGIN necessário")) garantirTelaDeLogin(driver);
                            if ("pular".equals(aguardar(new Chamado("LOGIN", driver.nome(), "busca", e.getMessage())))) {
                                bloqueados.add(driver.nome());
                                break;
                            }
                        }
                    }
                    if (vagas == null) continue;
                    encontradasTotal.addAndGet(vagas.size()); // TEMP-TESTE-FULL
                    if (vagas.isEmpty()) registrar(driver.nome() + ": nenhuma vaga para \"" + termo.trim()
                            + "\" (o portal pode não ter essa categoria)");
                    int jaFeitas = 0;
                    for (Vaga v : vagas) {
                        if (!rodando || bloqueados.contains(driver.nome())) break;
                        String chave = ChaveVaga.de(v.url());
                        if (!vistas.add(chave) || feitas.contains(chave)) {
                            jaFeitas++;
                            continue;
                        }
                        if (novas >= max) break;
                        novas++;
                        processar(driver, v);
                        int maxEnvios = kv.inteiro(KvRepo.CONFIG, "max_envios", 0);
                        if (maxEnvios > 0 && enviadas.get() >= maxEnvios) {
                            registrar("limite de " + maxEnvios + " envio(s) atingido — encerrando a sessão");
                            rodando = false;
                        }
                    }
                    jaNoHistorico.addAndGet(jaFeitas); // TEMP-TESTE-FULL
                    if (jaFeitas > 0) registrar(driver.nome() + ": " + jaFeitas + " de " + vagas.size()
                            + " vaga(s) de \"" + termo.trim() + "\" já estavam no histórico e foram ignoradas");
                }
            }
            registrar("✨ terminei — " + processadas.get() + " processadas · " + enviadas.get() + " enviadas · "
                    + puladas.get() + " puladas · " + erros.get() + " erros");
            if (kv.booleano(KvRepo.CONFIG, "relatorio_desktop", false)) escreverEstatisticas(); // TEMP-TESTE-FULL
            if (rodando) aviso.fim(); // não toca se o usuário parou a sessão
        } catch (Exception e) {
            // parar() interrompe a thread: o driver JDBC/Selenium reclama disso, mas não é falha
            registrar(rodando ? "sessão interrompida: " + e.getMessage() : "sessão parada pelo usuário");
        } finally {
            drivers.forEach(PortalDriver::fechar);
            navegador.fechar();
            rodando = false;
        }
    }

    /** TEMP-TESTE-FULL: resumo da sessão por portal e status, com os motivos de pulada/erro. */
    private void escreverEstatisticas() {
        TesteFullLog.ativar(true); // só chega aqui com o checkbox marcado
        var novas = candidaturas.listar("", "").stream().filter(c -> c.id() > idInicial).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("Vagas encontradas nas buscas: ").append(encontradasTotal.get()).append('\n')
                .append("Já no histórico (ignoradas): ").append(jaNoHistorico.get()).append('\n')
                .append("Processadas (novas): ").append(processadas.get()).append('\n')
                .append("Enviadas: ").append(enviadas.get()).append(" · Puladas: ").append(puladas.get())
                .append(" · Erros/falhas: ").append(erros.get()).append("\n\nPor portal e status:\n");
        novas.stream().collect(java.util.stream.Collectors.groupingBy(c -> c.portal() + " · " + c.status(),
                java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        sb.append("\nMotivos de pulada/erro/pendência:\n");
        novas.stream().filter(c -> !"enviada".equals(c.status()) && !"dry_run".equals(c.status()))
                .forEach(c -> sb.append("  [").append(c.portal()).append("] ").append(c.status()).append(" — ")
                        .append(c.empresa()).append(" / ").append(c.cargo()).append(" — ").append(c.motivo()).append('\n'));
        TesteFullLog.estatisticas(sb.toString());
    }

    private void pular(Vaga v, String rotulo, String motivo) {
        candidaturas.gravar(v.portal(), v.empresa(), v.cargo(), v.url(), null, "pulada", motivo, v.descricao());
        puladas.incrementAndGet();
        aviso.pulada();
        EventoJson.vaga(v, "pulada", motivo);
        registrar("pulada", "vaga pulada: " + rotulo + " (" + motivo + ")", null);
    }

    private void processar(PortalDriver driver, Vaga vaga) {
        processadas.incrementAndGet();
        Vaga v = driver.detalhar(vaga);
        String rotulo = v.empresa() + " — " + v.cargo();
        registrar("encontrada", "vaga encontrada: " + rotulo, null);
        // só recência e vaga em inglês são decididas por código; stack, local e tipo de vaga ficam com a IA
        Optional<String> descarte = filtro.motivoIdioma(v).or(() -> filtro.motivoRecencia(v, LocalDate.now()));
        if (descarte.isPresent()) {
            pular(v, rotulo, descarte.get());
            return;
        }
        Optional<Cv> cv = escolhaCv.escolher(v);
        JsonNode r = avaliar(v, rotulo);
        if (r == null) return; // a vaga não é gravada: entra de novo na próxima sessão
        if (!r.path("candidatar").asBoolean(false)) {
            pular(v, rotulo, "IA: " + r.path("motivo").asText("fora do perfil"));
            return;
        }
        String nomeCv = r.path("cv").asText("");
        if (!nomeCv.isBlank()) {
            Optional<Cv> atual = cv;
            cv = cvs.todos().stream().filter(c -> c.nome().equalsIgnoreCase(nomeCv)).findFirst().or(() -> atual);
        }
        if (cv.isEmpty()) {
            registrar("sem CV cadastrado — pare e importe os CVs");
            rodando = false;
            return;
        }
        long id = candidaturas.gravar(v.portal(), v.empresa(), v.cargo(), v.url(), cv.get().nome(), "pendente", "em andamento", v.descricao());
        registrar("aberta", "vaga aberta: " + rotulo + " · CV " + cv.get().nome(), null);
        // uma tentativa por rodada: repetir depois de um clique em "enviar" poderia candidatar duas vezes
        PortalDriver.Desfecho d;
        boolean pulouChamado = false;
        int tentativasLogin = 0;
        String ultimoMotivo = null;
        int repetidos = 0;
        String prefixo = null; // motivo de a vaga ser pulada sem a decisão do usuário
        while (true) {
            try {
                d = driver.candidatar(v, cv.get(), kv.booleano(KvRepo.CONFIG, "dry_run", true), () -> rodando); // lê a cada tentativa: o checkbox vale na hora
            } catch (IaClient.IaIndisponivel e) {
                iaFalhou(rotulo, e);
                d = new PortalDriver.Desfecho(PortalDriver.Status.PULADA, "IA indisponível");
            } catch (Exception e) {
                d = new PortalDriver.Desfecho(PortalDriver.Status.ERRO, String.valueOf(e.getMessage()));
                // Firefox travado/morto (timeout de comando): fecha para a próxima vaga abrir um navegador novo
                if (e instanceof org.openqa.selenium.WebDriverException) driver.fechar();
            }
            if (d.status() != PortalDriver.Status.PRECISA_USUARIO || !rodando) break;
            // a mesma pendência de novo e de novo (resposta que nunca é aceita): pula a vaga em vez de travar a sessão
            repetidos = java.util.Objects.equals(d.motivo(), ultimoMotivo) ? repetidos + 1 : 0;
            ultimoMotivo = d.motivo();
            if (repetidos >= 1 && !pedeLogin(d.motivo())) {
                pulouChamado = true;
                prefixo = "pulada (pendência repetida): ";
                break;
            }
            // login pedido no meio da busca: entra sozinho na mesma ordem do início (até 2 tentativas por vaga)
            boolean captchaNoLogin = false;
            boolean celularNoLogin = false;
            if (pedeLogin(d.motivo()) && tentativasLogin < 2) {
                tentativasLogin++;
                Tentativa t = tentarEntrar(driver);
                if (t.logado()) continue;
                celularNoLogin = t.celular();
                captchaNoLogin = !celularNoLogin && driver.pedeCaptcha();
                if (captchaNoLogin) registrar(driver.nome() + " exige CAPTCHA no login — o login automático não adianta");
            }
            boolean login = pedeLogin(d.motivo());
            // sem formulário possível em Pendências, chamar você não adianta: a vaga recomeçaria do zero e pararia no mesmo campo
            String semFormulario = ehLogin(d.motivo()) ? null : semFormulario(d.motivo());
            if (semFormulario != null) {
                pulouChamado = true;
                prefixo = semFormulario;
                break;
            }
            candidaturas.atualizarStatus(id, "aguardando_usuario", d.motivo());
            long pendencia = ehLogin(d.motivo()) ? 0
                    : situacoes.abrirPendencia(driver.nome(), d.motivo(), rotulo + "\n\n" + (v.descricao() == null ? "" : v.descricao()));
            String tipo = ehLogin(d.motivo()) ? "LOGIN" : pendencia > 0 ? "PENDENCIA" : "MANUAL";
            String motivo = d.motivo();
            if (pendencia > 0) motivo += " — escolha a resposta certa em Pendências; a vaga segue sozinha quando você salvar";
            if (tipo.equals("MANUAL")) motivo += " — resolva na janela do Firefox do robô e clique em Resolvido";
            if (login) {
                motivo += celularNoLogin
                        ? " — o LinkedIn/Google mandou uma confirmação para o seu celular: aprove no celular e clique em Resolvido"
                        : captchaNoLogin
                        ? " — o portal exige CAPTCHA: resolva e entre manualmente (marque \"lembrar-me\" para a sessão durar)"
                        : tentativasLogin > 0
                        ? " — o login automático não funcionou; entre manualmente (o novo login será salvo)"
                        : " — entre manualmente; o login digitado será salvo para entrar sozinho nas próximas vezes";
            }
            if (login && !celularNoLogin && !captchaNoLogin) garantirTelaDeLogin(driver);
            String[] digitado = {null, null};
            Runnable capturar = login ? () -> acumular(digitado, driver.lerLogin()) : () -> { };
            String decisao = aguardar(new Chamado(tipo, driver.nome(), rotulo, motivo), capturar,
                    pendencia > 0 ? () -> situacoes.pendenciaFechada(pendencia) : () -> false);
            if (login && "continuar".equals(decisao)) salvarLoginDigitado(driver.nome(), digitado);
            if ("pular".equals(decisao)) {
                pulouChamado = true;
                break;
            }
            registrar("retomando: " + rotulo);
        }
        if (pulouChamado) {
            if (ehLogin(d.motivo())) {
                bloqueados.add(driver.nome());
                registrar(driver.nome() + " pulado nesta rodada");
                candidaturas.atualizarStatus(id, "aguardando_usuario", d.motivo());
            } else {
                candidaturas.atualizarStatus(id, "pulada",
                        (prefixo != null ? prefixo
                                : kv.booleano(KvRepo.CONFIG, "nao_interagir", false) ? "pulada (não interagir): "
                                : "pulada pelo usuário: ") + d.motivo());
                puladas.incrementAndGet();
                aviso.pulada();
                EventoJson.vaga(v, "pulada", d.motivo());
                registrar("pulada", "vaga pulada: " + rotulo + " (" + d.motivo() + ")", null);
            }
            return;
        }
        if (!rodando && d.status() == PortalDriver.Status.ERRO) {
            candidaturas.atualizarStatus(id, "pendente", "sessão parada");
            return;
        }
        switch (d.status()) {
            case ENVIADA -> {
                candidaturas.atualizarStatus(id, "enviada", d.motivo());
                enviadas.incrementAndGet();
                aviso.enviada();
                EventoJson.vaga(v, "cadastrado", d.motivo());
                registrar("preenchida", "vaga preenchida: " + rotulo, null);
                registrar("enviada", "vaga enviada com sucesso: " + rotulo, null);
            }
            case PRONTA_DRY_RUN -> {
                candidaturas.atualizarStatus(id, "dry_run", d.motivo());
                EventoJson.vaga(v, "cadastrado", "dry-run: " + d.motivo());
                registrar("preenchida", "vaga preenchida (dry-run, não enviada): " + rotulo, null);
            }
            case PRECISA_USUARIO -> {
                candidaturas.atualizarStatus(id, "aguardando_usuario", d.motivo());
                // só chega aqui se a sessão foi parada durante o chamado
                registrar("preciso de você: " + rotulo + " (" + d.motivo() + ")");
            }
            case PULADA -> {
                candidaturas.atualizarStatus(id, "pulada", d.motivo());
                puladas.incrementAndGet();
                aviso.pulada();
                EventoJson.vaga(v, "pulada", d.motivo());
                registrar("pulada", "vaga pulada: " + rotulo + " (" + d.motivo() + ")", null);
            }
            default -> {
                candidaturas.atualizarStatus(id, "erro", d.motivo());
                erros.incrementAndGet();
                EventoJson.vaga(v, "erro", d.motivo());
                registrar("erro", "erro ao enviar: " + rotulo, d.motivo());
            }
        }
    }

    /**
     * Pede à IA a avaliação da vaga (as tentativas e o fallback ficam no IaClient). Sem resposta, pula a vaga sem gravá-la
     * e devolve null; a sessão segue para a próxima.
     */
    private JsonNode avaliar(Vaga v, String rotulo) {
        try {
            return ia.conversarJson(AVALIA_SYSTEM, avaliaPedido(v));
        } catch (RuntimeException e) {
            if (!rodando) return null; // parado pelo usuário
            iaFalhou(rotulo, e);
            puladas.incrementAndGet();
            registrar("pulada", "vaga pulada: " + rotulo + " (IA indisponível)", e.getMessage());
            return null;
        }
    }

    /** Todas as IAs falharam: na primeira vez da sessão abre o alerta no painel e bipa; depois só registra no log. */
    private void iaFalhou(String rotulo, RuntimeException e) {
        if (!rodando || iaJaFalhou) return;
        iaJaFalhou = true;
        alerta = "A IA não respondeu (OpenRouter e Z.ai falharam) na vaga " + rotulo
                + ". A vaga foi pulada e a busca segue; se continuar falhando, confira as chaves em Config.";
        registrar("erro", "IA indisponível", e.getMessage());
        aviso.chamarUsuario();
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final String AVALIA_SYSTEM = """
            Você avalia se uma vaga combina com o candidato. Responda SOMENTE com JSON:
            {"candidatar": true|false, "cv": "nome exato de um dos CVs ou vazio", "motivo": "curto"}.
            Pule vagas que exigem inglês falado/reuniões em inglês, stack principal fora do perfil, estágio ou fora do Brasil.
            Local e modalidade (remoto, híbrido ou presencial, em qualquer cidade do Brasil) NUNCA são motivo para pular: o candidato aceita todos.
            """;

    private String avaliaPedido(Vaga v) {
        String descr = v.descricao() == null ? "" : v.descricao();
        return "VAGA: " + v.empresa() + " — " + v.cargo() + " (" + v.local() + ", " + v.modalidade() + ")\n"
                + (descr.length() > 4000 ? descr.substring(0, 4000) : descr)
                + "\n\nPREFERÊNCIAS: " + kv.todos(KvRepo.PREF)
                + "\nCVs DISPONÍVEIS: " + Arrays.toString(cvs.todos().stream().map(Cv::nome).toArray())
                + "\n\nCURRÍCULO:\n" + cvContexto.textoCvs();
    }
}
