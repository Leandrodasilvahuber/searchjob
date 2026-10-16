package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.Cv;
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

    private volatile boolean rodando;
    private volatile String mensagem = "parado";
    private final AtomicInteger processadas = new AtomicInteger();
    private final AtomicInteger enviadas = new AtomicInteger();
    private final AtomicInteger puladas = new AtomicInteger();
    private final AtomicInteger erros = new AtomicInteger();
    private final Deque<String> log = new ArrayDeque<>();
    private Thread thread;
    private final Set<String> bloqueados = new HashSet<>();

    public Orquestrador(KvRepo kv, CandidaturaRepo candidaturas, CvRepo cvs, FiltroVaga filtro, EscolhaCv escolhaCv,
                        SituacaoService situacoes, ZaiClient zai, CvContexto cvContexto, Aviso aviso,
                        FabricaDrivers fabrica) {
        this.fabrica = fabrica;
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
                         List<String> log) {}

    public synchronized Estado estado() {
        return new Estado(rodando, mensagem, processadas.get(), enviadas.get(), puladas.get(), erros.get(), new ArrayList<>(log));
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
        synchronized (this) {
            log.addFirst(java.time.LocalTime.now().withNano(0) + " " + linha);
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
                    List<Vaga> vagas;
                    try {
                        vagas = driver.buscar(termo.trim(), max);
                    } catch (Exception e) {
                        registrar("erro na busca (" + driver.nome() + "): " + e.getMessage());
                        if (e.getMessage() != null && e.getMessage().contains("CAPTCHA")) {
                            bloqueados.add(driver.nome());
                            aviso.login();
                        }
                        continue;
                    }
                    for (Vaga v : vagas) {
                        if (!rodando || bloqueados.contains(driver.nome())) break;
                        String chave = ChaveVaga.de(v.url());
                        if (!vistas.add(chave) || feitas.contains(chave)) continue;
                        processar(driver, v, zaiAvalia, dryRun);
                    }
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
        Optional<String> descarte = filtro.motivoDescarte(v, LocalDate.now());
        if (descarte.isPresent()) {
            candidaturas.gravar(v.portal(), v.empresa(), v.cargo(), v.url(), null, "pulada", descarte.get());
            puladas.incrementAndGet();
            aviso.pulada();
            registrar("pulada: " + rotulo + " (" + descarte.get() + ")");
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
                    registrar("pulada: " + rotulo + " (" + motivo + ")");
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
        registrar("candidatando: " + rotulo + " · CV " + cv.get().nome());
        // uma tentativa por rodada: repetir depois de um clique em "enviar" poderia candidatar duas vezes
        PortalDriver.Desfecho d;
        try {
            d = driver.candidatar(v, cv.get(), dryRun, () -> rodando);
        } catch (Exception e) {
            d = new PortalDriver.Desfecho(PortalDriver.Status.ERRO, String.valueOf(e.getMessage()));
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
                registrar("enviada: " + rotulo);
            }
            case PRONTA_DRY_RUN -> {
                candidaturas.atualizarStatus(id, "dry_run", d.motivo());
                registrar("pronta (dry-run): " + rotulo);
            }
            case PRECISA_USUARIO -> {
                candidaturas.atualizarStatus(id, "aguardando_usuario", d.motivo());
                if (d.motivo().contains("login") || d.motivo().toLowerCase().contains("captcha")) aviso.login();
                else aviso.chamarUsuario();
                registrar("preciso de você: " + rotulo + " (" + d.motivo() + ")");
                if (d.motivo().contains("login") || d.motivo().toLowerCase().contains("captcha")) {
                    // só este portal fica de fora; os demais seguem. Logue na janela do Firefox e inicie de novo.
                    bloqueados.add(driver.nome());
                    registrar(driver.nome() + " pulado nesta rodada até você logar");
                }
            }
            case PULADA -> {
                candidaturas.atualizarStatus(id, "pulada", d.motivo());
                puladas.incrementAndGet();
                aviso.pulada();
                registrar("pulada: " + rotulo + " (" + d.motivo() + ")");
            }
            default -> {
                candidaturas.atualizarStatus(id, "erro", d.motivo());
                erros.incrementAndGet();
                registrar("erro: " + rotulo + " (" + d.motivo() + ")");
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
