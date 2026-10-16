package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.CredencialRepo;
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
    private final ZaiClient zai;
    private final CvContexto cvContexto;
    private final Aviso aviso;
    private final FabricaDrivers fabrica;
    private final CredencialRepo credenciais;

    /** Pedido de ajuda que pausa a sessão: {@code tipo} é LOGIN (login/CAPTCHA) ou PENDENCIA (pergunta sem resposta). */
    public record Chamado(String tipo, String portal, String vaga, String motivo) {}

    private static final long BIP_A_CADA_MS = 10_000;
    private volatile Chamado chamado;
    private final LinkedBlockingQueue<String> decisoes = new LinkedBlockingQueue<>();

    private volatile boolean rodando;
    private volatile String mensagem = "parado";
    private final AtomicInteger processadas = new AtomicInteger();
    private final AtomicInteger enviadas = new AtomicInteger();
    private final AtomicInteger puladas = new AtomicInteger();
    private final AtomicInteger erros = new AtomicInteger();
    private final Deque<Evento> log = new ArrayDeque<>();
    private Thread thread;
    private final Set<String> bloqueados = new HashSet<>();

    public Orquestrador(KvRepo kv, CandidaturaRepo candidaturas, CvRepo cvs, FiltroVaga filtro, EscolhaCv escolhaCv,
                        SituacaoService situacoes, ZaiClient zai, CvContexto cvContexto, Aviso aviso,
                        FabricaDrivers fabrica, CredencialRepo credenciais) {
        this.fabrica = fabrica;
        this.credenciais = credenciais;
        this.kv = kv;
        this.candidaturas = candidaturas;
        this.cvs = cvs;
        this.filtro = filtro;
        this.escolhaCv = escolhaCv;
        this.situacoes = situacoes;
        this.zai = zai;
        this.cvContexto = cvContexto;
        this.aviso = aviso;
    }

    public record Estado(boolean rodando, String mensagem, int processadas, int enviadas, int puladas, int erros,
                         List<Evento> log, Chamado chamado) {}

    /** Linha do log: tipo = info|encontrada|aberta|preenchida|enviada|aguardando|pulada|erro. */
    public record Evento(String hora, String tipo, String texto, String detalhe) {}

    public synchronized Estado estado() {
        return new Estado(rodando, mensagem, processadas.get(), enviadas.get(), puladas.get(), erros.get(),
                new ArrayList<>(log), chamado);
    }

    /** Decisão do usuário para o chamado aberto: "continuar" (já logou/resolveu) ou "pular". */
    public void decidir(String decisao) {
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
        if (!c.tipo().equals("LOGIN") && kv.booleano(KvRepo.CONFIG, "nao_interagir", false)) {
            registrar("não interagir: pulando sem chamar você (" + c.motivo() + ")");
            return "pular";
        }
        decisoes.clear();
        chamado = c;
        registrar("aguardando", "vaga aguardando intervenção: " + (c.tipo().equals("LOGIN")
                ? "faça login em " + c.portal() : "pendência em " + c.portal()) + " (" + c.motivo() + ")", c.tipo());
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
        return motivo.contains("login") && !motivo.toLowerCase().contains("captcha");
    }

    /** Usa o login ativo do portal no cofre. Devolve true se preencheu e enviou o formulário. */
    private boolean entrarAutomatico(PortalDriver driver) {
        Optional<CredencialRepo.Login> login;
        try {
            login = credenciais.ativa(driver.nome());
        } catch (RuntimeException e) {
            registrar("login salvo de " + driver.nome() + " ilegível: " + e.getMessage());
            return false;
        }
        if (login.isEmpty()) {
            registrar("sem login salvo para " + driver.nome() + " — cadastre em Credenciais para entrar sozinho");
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

    private static boolean ehLogin(String motivo) {
        return motivo.contains("login") || motivo.toLowerCase().contains("captcha");
    }

    public synchronized void iniciar() {
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
        log.clear();
        thread = new Thread(this::executar, "searchjob-sessao");
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

    private void executar() {
        NavegadorFirefox navegador = new NavegadorFirefox();
        List<PortalDriver> drivers = new ArrayList<>();
        try {
            for (String p : kv.get(KvRepo.CONFIG, "portais", "gupy").split(",")) {
                if (p.isBlank()) continue;
                Optional<PortalDriver> d = fabrica.criar(p, navegador);
                if (d.isPresent()) drivers.add(d.get());
                else registrar("portal \"" + p.trim() + "\" ainda não é suportado — ignorado");
            }
            boolean zaiAvalia = "zai".equals(kv.get(KvRepo.CONFIG, "modo", "codigo"));
            boolean dryRun = Boolean.parseBoolean(kv.get(KvRepo.CONFIG, "dry_run", "true"));
            int max = kv.inteiro(KvRepo.CONFIG, "max_vagas", 30);
            if (zaiAvalia && !zai.configurado()) {
                registrar("modo z.ai sem chave configurada — voltando ao modo código");
                zaiAvalia = false;
            }
            registrar("sessão iniciada · modo " + (zaiAvalia ? "z.ai avalia" : "código") + (dryRun ? " · dry-run" : ""));
            Set<String> vistas = new HashSet<>();
            Set<String> feitas = new HashSet<>();
            bloqueados.clear();
            candidaturas.urlsProcessadas(dryRun).forEach(u -> feitas.add(ChaveVaga.de(u)));
            for (PortalDriver driver : drivers) {
                for (String termo : kv.get(KvRepo.PREF, "termos", "").split(",")) {
                    if (!rodando || termo.isBlank() || bloqueados.contains(driver.nome())) continue;
                    registrar(driver.nome() + ": buscando \"" + termo.trim() + "\"");
                    List<Vaga> vagas = null;
                    while (vagas == null) {
                        try {
                            vagas = driver.buscar(termo.trim(), max);
                        } catch (Exception e) {
                            registrar("erro na busca (" + driver.nome() + "): " + e.getMessage());
                            if (e.getMessage() == null || !(e.getMessage().contains("CAPTCHA") || e.getMessage().contains("LOGIN necessário"))) break;
                            if ("pular".equals(aguardar(new Chamado("LOGIN", driver.nome(), "busca", e.getMessage())))) {
                                bloqueados.add(driver.nome());
                                break;
                            }
                        }
                    }
                    if (vagas == null) continue;
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
                        processar(driver, v, zaiAvalia, dryRun);
                        int maxEnvios = kv.inteiro(KvRepo.CONFIG, "max_envios", 0);
                        if (maxEnvios > 0 && enviadas.get() >= maxEnvios) {
                            registrar("limite de " + maxEnvios + " envio(s) atingido — encerrando a sessão");
                            rodando = false;
                        }
                    }
                    if (jaFeitas > 0) registrar(driver.nome() + ": " + jaFeitas + " de " + vagas.size()
                            + " vaga(s) de \"" + termo.trim() + "\" já estavam no histórico e foram ignoradas");
                }
            }
            registrar("✨ terminei — " + processadas.get() + " processadas · " + enviadas.get() + " enviadas · "
                    + puladas.get() + " puladas · " + erros.get() + " erros");
            if (rodando) aviso.fim(); // não toca se o usuário parou a sessão
        } catch (Exception e) {
            registrar("sessão interrompida: " + e.getMessage());
        } finally {
            drivers.forEach(PortalDriver::fechar);
            navegador.fechar();
            rodando = false;
        }
    }

    private void processar(PortalDriver driver, Vaga v, boolean zaiAvalia, boolean dryRun) {
        processadas.incrementAndGet();
        String rotulo = v.empresa() + " — " + v.cargo();
        registrar("encontrada", "vaga encontrada: " + rotulo, null);
        Optional<String> descarte = filtro.motivoDescarte(v, LocalDate.now());
        if (descarte.isPresent()) {
            candidaturas.gravar(v.portal(), v.empresa(), v.cargo(), v.url(), null, "pulada", descarte.get());
            puladas.incrementAndGet();
            aviso.pulada();
            registrar("pulada", "vaga pulada: " + rotulo + " (" + descarte.get() + ")", null);
            return;
        }
        Optional<EscolhaCv.Escolha> escolha = escolhaCv.escolherDefinido(v);
        Optional<Cv> cv = escolha.map(EscolhaCv.Escolha::cv);
        // stack sem definição clara (nenhuma palavra-chave ou empate): pergunta à z.ai qual CV mandar
        if (!zaiAvalia && escolha.isPresent() && !escolha.get().definida() && zai.configurado()) {
            try {
                JsonNode r = zai.conversarJson(CV_SYSTEM, avaliaPedido(v));
                String nomeCv = r.path("cv").asText("");
                Optional<Cv> sugerido = cvs.todos().stream().filter(c -> c.nome().equalsIgnoreCase(nomeCv)).findFirst();
                if (sugerido.isPresent()) {
                    cv = sugerido;
                    registrar("CV indefinido pelas regras — z.ai escolheu " + nomeCv);
                }
            } catch (RuntimeException e) {
                registrar("z.ai indisponível para escolher CV, usando o padrão: " + e.getMessage());
            }
        }
        if (zaiAvalia) {
            try {
                JsonNode r = zai.conversarJson(AVALIA_SYSTEM, avaliaPedido(v));
                if (!r.path("candidatar").asBoolean(false)) {
                    String motivo = "z.ai: " + r.path("motivo").asText("fora do perfil");
                    candidaturas.gravar(v.portal(), v.empresa(), v.cargo(), v.url(), null, "pulada", motivo);
                    puladas.incrementAndGet();
                    aviso.pulada();
                    registrar("pulada", "vaga pulada: " + rotulo + " (" + motivo + ")", null);
                    return;
                }
                String nomeCv = r.path("cv").asText("");
                if (!nomeCv.isBlank()) {
                    Optional<Cv> atual = cv;
                    cv = cvs.todos().stream().filter(c -> c.nome().equalsIgnoreCase(nomeCv)).findFirst().or(() -> atual);
                }
            } catch (RuntimeException e) {
                registrar("z.ai indisponível na avaliação, seguindo pelas regras: " + e.getMessage());
            }
        }
        if (cv.isEmpty()) {
            registrar("sem CV cadastrado — pare e importe os CVs");
            rodando = false;
            return;
        }
        long id = candidaturas.gravar(v.portal(), v.empresa(), v.cargo(), v.url(), cv.get().nome(), "pendente", "em andamento");
        registrar("aberta", "vaga aberta: " + rotulo + " · CV " + cv.get().nome(), null);
        // uma tentativa por rodada: repetir depois de um clique em "enviar" poderia candidatar duas vezes
        PortalDriver.Desfecho d;
        boolean pulouChamado = false;
        int tentativasLogin = 0;
        while (true) {
            try {
                d = driver.candidatar(v, cv.get(), dryRun, () -> rodando);
            } catch (Exception e) {
                d = new PortalDriver.Desfecho(PortalDriver.Status.ERRO, String.valueOf(e.getMessage()));
            }
            if (d.status() != PortalDriver.Status.PRECISA_USUARIO || !rodando) break;
            // login pedido no meio da busca: entra sozinho com o login salvo (até 2 tentativas por vaga)
            boolean captchaNoLogin = false;
            if (pedeLogin(d.motivo()) && tentativasLogin < 2 && entrarAutomatico(driver)) {
                tentativasLogin++;
                captchaNoLogin = driver.pedeCaptcha();
                if (!captchaNoLogin) continue;
                registrar(driver.nome() + " exige CAPTCHA no login — o login automático não adianta");
            }
            candidaturas.atualizarStatus(id, "aguardando_usuario", d.motivo());
            boolean login = pedeLogin(d.motivo());
            String tipo = ehLogin(d.motivo()) ? "LOGIN" : "PENDENCIA";
            String motivo = d.motivo();
            if (login) {
                motivo += captchaNoLogin
                        ? " — o portal exige CAPTCHA: resolva e entre manualmente (marque \"lembrar-me\" para a sessão durar)"
                        : tentativasLogin > 0
                        ? " — o login automático não funcionou; entre manualmente (o novo login será salvo)"
                        : " — entre manualmente; o login digitado será salvo para entrar sozinho nas próximas vezes";
            }
            String[] digitado = {null, null};
            Runnable capturar = login ? () -> acumular(digitado, driver.lerLogin()) : () -> { };
            String decisao = aguardar(new Chamado(tipo, driver.nome(), rotulo, motivo), capturar);
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
                        (kv.booleano(KvRepo.CONFIG, "nao_interagir", false) ? "pulada (não interagir): "
                                : "pulada pelo usuário: ") + d.motivo());
                puladas.incrementAndGet();
                aviso.pulada();
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
                registrar("preenchida", "vaga preenchida: " + rotulo, null);
                registrar("enviada", "vaga enviada com sucesso: " + rotulo, null);
            }
            case PRONTA_DRY_RUN -> {
                candidaturas.atualizarStatus(id, "dry_run", d.motivo());
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
                registrar("pulada", "vaga pulada: " + rotulo + " (" + d.motivo() + ")", null);
            }
            default -> {
                candidaturas.atualizarStatus(id, "erro", d.motivo());
                erros.incrementAndGet();
                registrar("erro", "erro ao enviar: " + rotulo, d.motivo());
            }
        }
    }

    private static final String CV_SYSTEM = """
            Escolha qual currículo enviar para a vaga. Responda SOMENTE com JSON: {"cv": "nome exato de um dos CVs"}.
            Considere o nível (pleno, sênior, arquiteto, líder) e a stack da vaga. Arquiteto/líder/staff/plataforma/microsserviços
            => o CV de sistemas distribuídos; PHP/Laravel/Vue => o CV PHP; Node/TypeScript/full stack JS => o CV NodeJS.
            """;

    private static final String AVALIA_SYSTEM = """
            Você avalia se uma vaga combina com o candidato. Responda SOMENTE com JSON:
            {"candidatar": true|false, "cv": "nome exato de um dos CVs ou vazio", "motivo": "curto"}.
            Pule vagas que exigem inglês falado/reuniões em inglês, stack principal fora do perfil, estágio ou fora do Brasil.
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
