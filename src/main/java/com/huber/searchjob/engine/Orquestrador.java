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
    private final IaClient ia;
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
    private final AtomicInteger encontradasTotal = new AtomicInteger(); // TEMP-TESTE-FULL
    private final AtomicInteger jaNoHistorico = new AtomicInteger(); // TEMP-TESTE-FULL
    private volatile long idInicial; // TEMP-TESTE-FULL
    private final Deque<Evento> log = new ArrayDeque<>();
    private Thread thread;
    private final Set<String> bloqueados = new HashSet<>();

    public Orquestrador(KvRepo kv, CandidaturaRepo candidaturas, CvRepo cvs, FiltroVaga filtro, EscolhaCv escolhaCv,
                        SituacaoService situacoes, IaClient ia, CvContexto cvContexto, Aviso aviso,
                        FabricaDrivers fabrica, CredencialRepo credenciais) {
        this.fabrica = fabrica;
        this.credenciais = credenciais;
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
                         List<Evento> log, Chamado chamado) {}

    /** Linha do log: tipo = info|encontrada|aberta|preenchida|enviada|aguardando|pulada|erro. */
    public record Evento(String hora, String tipo, String texto, String detalhe) {}

    public synchronized Estado estado() {
        return new Estado(rodando, mensagem, processadas.get(), enviadas.get(), puladas.get(), erros.get(),
                new ArrayList<>(log), chamadoAtual());
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
            boolean iaAvalia = "ia".equals(kv.get(KvRepo.CONFIG, "modo", "codigo"));
            boolean dryRun = Boolean.parseBoolean(kv.get(KvRepo.CONFIG, "dry_run", "true"));
            int max = kv.inteiro(KvRepo.CONFIG, "max_vagas", 30);
            if (iaAvalia && !ia.configurado()) {
                registrar("modo IA sem chave configurada — voltando ao modo código");
                iaAvalia = false;
            }
            registrar("sessão iniciada · modo " + (iaAvalia ? "IA avalia" : "código") + (dryRun ? " · dry-run" : ""));
            encontradasTotal.set(0); // TEMP-TESTE-FULL
            jaNoHistorico.set(0); // TEMP-TESTE-FULL
            idInicial = candidaturas.listar("", "").stream().mapToLong(c -> c.id()).max().orElse(0); // TEMP-TESTE-FULL
            Set<String> vistas = new HashSet<>();
            Set<String> feitas = new HashSet<>();
            bloqueados.clear();
            candidaturas.urlsProcessadas(dryRun).forEach(u -> feitas.add(ChaveVaga.de(u)));
            for (PortalDriver driver : drivers) {
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
                        processar(driver, v, iaAvalia);
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
        TesteFullLog.ativo = true; // só chega aqui com o checkbox marcado
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

    private void processar(PortalDriver driver, Vaga vaga, boolean iaAvalia) {
        processadas.incrementAndGet();
        Vaga v = driver.detalhar(vaga);
        String rotulo = v.empresa() + " — " + v.cargo();
        registrar("encontrada", "vaga encontrada: " + rotulo, null);
        // modo IA: só recência e vaga em inglês são decididas por código; stack, local e tipo de vaga ficam com a IA
        Optional<String> descarte = filtro.motivoIdioma(v).or(() -> iaAvalia ? filtro.motivoRecencia(v, LocalDate.now()) : filtro.motivoDescarte(v, LocalDate.now()));
        if (descarte.isPresent()) {
            pular(v, rotulo, descarte.get());
            return;
        }
        Optional<EscolhaCv.Escolha> escolha = escolhaCv.escolherDefinido(v);
        Optional<Cv> cv = escolha.map(EscolhaCv.Escolha::cv);
        // stack sem definição clara (nenhuma palavra-chave ou empate): pergunta à IA qual CV mandar
        if (!iaAvalia && escolha.isPresent() && !escolha.get().definida() && ia.configurado()) {
            try {
                JsonNode r = ia.conversarJson(CV_SYSTEM, avaliaPedido(v));
                String nomeCv = r.path("cv").asText("");
                Optional<Cv> sugerido = cvs.todos().stream().filter(c -> c.nome().equalsIgnoreCase(nomeCv)).findFirst();
                if (sugerido.isPresent()) {
                    cv = sugerido;
                    registrar("CV indefinido pelas regras — IA escolheu " + nomeCv);
                }
            } catch (RuntimeException e) {
                registrar("IA indisponível para escolher CV, usando o padrão: " + e.getMessage());
            }
        }
        if (iaAvalia) {
            try {
                JsonNode r = ia.conversarJson(AVALIA_SYSTEM, avaliaPedido(v));
                if (!r.path("candidatar").asBoolean(false)) {
                    pular(v, rotulo, "IA: " + r.path("motivo").asText("fora do perfil"));
                    return;
                }
                String nomeCv = r.path("cv").asText("");
                if (!nomeCv.isBlank()) {
                    Optional<Cv> atual = cv;
                    cv = cvs.todos().stream().filter(c -> c.nome().equalsIgnoreCase(nomeCv)).findFirst().or(() -> atual);
                }
            } catch (RuntimeException e) {
                registrar("IA indisponível na avaliação, seguindo pelas regras: " + e.getMessage());
                Optional<String> regras = filtro.motivoDescarte(v, LocalDate.now());
                if (regras.isPresent()) {
                    pular(v, rotulo, regras.get());
                    return;
                }
            }
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
        boolean pulouPorRepeticao = false;
        while (true) {
            try {
                d = driver.candidatar(v, cv.get(), kv.booleano(KvRepo.CONFIG, "dry_run", true), () -> rodando); // lê a cada tentativa: o checkbox vale na hora
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
                pulouPorRepeticao = true;
                break;
            }
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
                        (pulouPorRepeticao ? "pulada (pendência repetida): "
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

    private static final String CV_SYSTEM = """
            Escolha qual currículo enviar para a vaga. Responda SOMENTE com JSON: {"cv": "nome exato de um dos CVs"}.
            Considere o nível (pleno, sênior, arquiteto, líder) e a stack da vaga. Arquiteto/líder/staff/plataforma/microsserviços
            => o CV de sistemas distribuídos; PHP/Laravel/Vue => o CV PHP; Node/TypeScript/full stack JS => o CV NodeJS.
            """;

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
