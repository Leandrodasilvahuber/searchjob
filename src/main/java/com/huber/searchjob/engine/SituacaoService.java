package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.Situacao;
import com.huber.searchjob.db.SituacaoRepo;
import com.huber.searchjob.db.TextoGeradoRepo;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

/**
 * Núcleo do aprendizado:
 * 1) acha situação ativa (hash exato ou similaridade) e aplica sem LLM;
 * 2) senão pergunta à IA; se ela resolve, fica pendente_confirmacao e chama o usuário;
 * 3) se ela não resolve, fica aguardando_usuario e o usuário relata a resposta.
 */
@Service
public class SituacaoService {
    static final double LIMIAR_SIMILARIDADE = 0.8;

    public enum Tipo { APLICADA, CONFIRMADA_PELO_USUARIO, RESPONDIDA_PELO_USUARIO, SEM_RESPOSTA, SENSIVEL }

    public record Resultado(Tipo tipo, String resposta, long situacaoId) {}

    private static final String[] SENSIVEL = {"rg", "numero do rg", "nome do pai", "nome completo do pai",
            "senha", "conta bancaria", "agencia", "dados bancarios", "cartao"};

    private static final long BIP_A_CADA_MS = 10_000;
    private final SituacaoRepo repo;
    private final KvRepo kv;
    private final IaClient ia;
    private final CvContexto cvContexto;
    private final Aviso aviso;
    private final TextoGeradoRepo textos;

    public SituacaoService(SituacaoRepo repo, KvRepo kv, IaClient ia, CvContexto cvContexto, Aviso aviso,
                           TextoGeradoRepo textos) {
        this.aviso = aviso;
        this.textos = textos;
        this.repo = repo;
        this.kv = kv;
        this.ia = ia;
        this.cvContexto = cvContexto;
    }

    public Optional<Situacao> buscar(String portal, String pergunta) {
        String hash = Texto.hash(pergunta);
        Optional<Situacao> exata = repo.ativaPorHash(hash);
        if (exata.isPresent()) return exata;
        return repo.ativas().stream()
                .filter(s -> Texto.equivalentes(pergunta, s.padraoPergunta()))
                .max(Comparator.comparingDouble(s -> Texto.similaridade(pergunta, s.padraoPergunta())));
    }

    public static boolean sensivel(String pergunta) {
        String n = " " + Texto.normalizar(pergunta) + " ";
        for (String s : SENSIVEL) if (n.contains(" " + s + " ")) return true;
        return false;
    }

    /**
     * Resolve uma pergunta. {@code aguardarUsuario} é consultado em laço enquanto a situação não for
     * resolvida pela UI; devolve false para desistir (ex.: execução parada).
     */
    public Resultado resolver(String portal, String pergunta, String contextoVaga, BooleanSupplier continuar) {
        return resolver(portal, pergunta, contextoVaga, null, false, continuar);
    }

    private static final String[] DISCURSIVAS = {"apresent", "conte", "fale sobre", "descreva", "por que", "porque voce",
            "motivo", "motivacao", "carta", "sobre voce", "diferencial", "mensagem para", "fale um pouco"};
    static final String INSTRUCAO_PADRAO =
            "Responda direto ao que foi perguntado, de forma natural e curta, citando só a experiência do currículo que importa para esta vaga.";
    /** Instrução antiga, gravada nas regras "personalizada" já existentes: vale a nova. */
    private static final String INSTRUCAO_ANTIGA =
            "Escreva uma resposta personalizada para esta vaga, destacando as experiências do currículo mais relevantes para ela.";

    /** Campo de dado objetivo (salário, matrícula, links): resposta curta vinda dos dados/preferências, nunca um texto de apresentação. */
    private static final String[] FATUAIS = {"salari", "remunera", "pretens", "expectativa", "matricula", "indicad",
            "linkedin", "github", "portfolio", "site", "url", "link"};

    public static boolean fatual(String pergunta) {
        String n = Texto.normalizar(pergunta);
        for (String k : FATUAIS) if (n.contains(k)) return true;
        return false;
    }

    /** Texto livre (área de texto ou pergunta aberta): a IA escreve sob medida para cada vaga em vez de reaproveitar. */
    public static boolean discursiva(String pergunta, boolean areaDeTexto) {
        if (areaDeTexto) return true;
        String n = Texto.normalizar(pergunta);
        for (String k : DISCURSIVAS) if (n.contains(k)) return true;
        return false;
    }

    /**
     * @param cv currículo escolhido para a vaga (usado nos textos personalizados)
     * @param areaDeTexto true quando o campo é um textarea
     */
    public Resultado resolver(String portal, String pergunta, String contextoVaga, Cv cv, boolean areaDeTexto,
                              BooleanSupplier continuar) {
        return resolver(portal, pergunta, contextoVaga, cv, areaDeTexto, null, continuar);
    }

    /** @param limite restrição do campo (tamanho máximo, só número) repassada à IA; null quando não há */
    public Resultado resolver(String portal, String pergunta, String contextoVaga, Cv cv, boolean areaDeTexto,
                              String limite, BooleanSupplier continuar) {
        this.limite = limite == null || limite.isBlank() ? "" : "\n\nLIMITE DO CAMPO (obrigatório, a resposta tem que caber inteira e continuar completa e natural):\n" + limite;
        long inicio = System.currentTimeMillis();
        ia.esquecerModelo();
        Resultado r = resolverPergunta(portal, pergunta, contextoVaga, cv, areaDeTexto, continuar);
        EventoJson.pergunta(portal, contextoVaga, pergunta, areaDeTexto, r, System.currentTimeMillis() - inicio, ia.ultimoModelo());
        return r;
    }

    private volatile String limite = "";

    private Resultado resolverPergunta(String portal, String pergunta, String contextoVaga, Cv cv, boolean areaDeTexto,
                                       BooleanSupplier continuar) {
        if (!sensivel(pergunta) && !fatual(pergunta) && discursiva(pergunta, areaDeTexto) && ia.configurado()) {
            Resultado r = gerarPersonalizado(portal, pergunta, contextoVaga, cv);
            if (r != null) return r;
        }
        if (sensivel(pergunta)) {
            if (!naoInteragir()) aviso.chamarUsuario();
            return new Resultado(Tipo.SENSIVEL, null, 0);
        }

        // a instrução de "texto personalizado" não é resposta: nunca vai para o campo
        // pretensão sem regime na pergunta: o valor (CLT ou PJ) depende da vaga, então a resposta de outra vaga não serve
        Optional<Situacao> achada = salarioSemRegime(pergunta) ? Optional.empty()
                : buscar(portal, pergunta).filter(x -> !"personalizada".equals(x.tipo()));
        if (achada.isPresent()) {
            Situacao s = achada.get();
            repo.incrementarUso(s.id());
            return new Resultado(Tipo.APLICADA, s.promptOuResposta(), s.id());
        }

        String hash = Texto.hash(pergunta);
        long id;
        // já existe pendência aberta para esta pergunta (outra tentativa ou rodada): reusa em vez de duplicar
        Optional<Situacao> aberta = repo.pendentePorHash(hash);
        if (aberta.isPresent()) {
            // resposta que a IA já achou nos dados: vale agora; a revisão em Pendências fica para depois, sem travar a vaga
            if (SituacaoRepo.PENDENTE_CONFIRMACAO.equals(aberta.get().status()) && !nulo(aberta.get().promptOuResposta())) {
                repo.incrementarUso(aberta.get().id());
                return new Resultado(Tipo.APLICADA, aberta.get().promptOuResposta(), aberta.get().id());
            }
            if (naoInteragir()) return new Resultado(Tipo.SEM_RESPOSTA, null, aberta.get().id());
            return aguardar(portal, pergunta, aberta.get().id(),
                    SituacaoRepo.PENDENTE_CONFIRMACAO.equals(aberta.get().status()) ? Tipo.CONFIRMADA_PELO_USUARIO : Tipo.RESPONDIDA_PELO_USUARIO,
                    continuar);
        }
        if (ia.configurado()) {
            JsonNode r;
            try {
                r = ia.conversarJson(SYSTEM, montarPedido(pergunta, contextoVaga));
            } catch (IaClient.IaIndisponivel e) {
                throw e; // todas as IAs falharam: a vaga é pulada (Orquestrador)
            } catch (RuntimeException e) {
                r = null;
            }
            if (r != null && r.path("resolvido").asBoolean(false) && !nulo(r.path("resposta").asText(""))) {
                // o que a IA achou nos dados vale como regra na hora: o usuário só é chamado quando não há resposta
                id = repo.inserir(portal, "pergunta", pergunta, hash, r.path("resposta").asText(),
                        "ia", SituacaoRepo.ATIVA, contextoVaga);
                return new Resultado(Tipo.APLICADA, r.path("resposta").asText(), id);
            }
        }
        // campo declarado opcional e sem dado para preencher: segue em branco em vez de chamar o usuário
        if (opcional(pergunta)) return new Resultado(Tipo.APLICADA, "", 0);
        id = repo.inserir(portal, "pergunta", pergunta, hash, null, "usuario",
                SituacaoRepo.AGUARDANDO_USUARIO, contextoVaga);
        if (naoInteragir()) return new Resultado(Tipo.SEM_RESPOSTA, null, id);
        return aguardar(portal, pergunta, id, Tipo.RESPONDIDA_PELO_USUARIO, continuar);
    }

    static boolean salarioSemRegime(String pergunta) {
        String n = " " + Texto.normalizar(pergunta) + " ";
        return PortalWebDriver.ehSalario(pergunta) && !n.contains(" pj ") && !n.contains(" clt ") && !n.contains("prestador")
                && !n.contains("cooperad") && !n.contains("atual") && !n.contains("ultima");
    }

    /** Modo "não interagir": pergunta sem resposta vira vaga pulada, sem bip nem espera (a pendência fica registrada). */
    private boolean naoInteragir() {
        return kv.booleano(KvRepo.CONFIG, "nao_interagir", false);
    }

    /** Pergunta que está pausando a vaga agora (o painel mostra o alerta com o link para Pendências). */
    public record Espera(String portal, String pergunta) {}

    private volatile Espera esperando;
    private volatile boolean pularEspera;

    public Espera esperando() {
        return esperando;
    }

    /** O usuário escolheu pular a vaga em vez de responder. */
    public void pularEspera() {
        if (esperando != null) pularEspera = true;
    }

    private Resultado aguardar(String portal, String pergunta, long id, Tipo tipoOk, BooleanSupplier continuar) {
        pularEspera = false;
        esperando = new Espera(portal, pergunta);
        long ultimoBip = 0;
        try {
            while (continuar.getAsBoolean() && !pularEspera) {
                Optional<Situacao> s = repo.porId(id);
                if (s.isEmpty()) return new Resultado(Tipo.SEM_RESPOSTA, null, id);
                if (SituacaoRepo.ATIVA.equals(s.get().status()))
                    return new Resultado(tipoOk, s.get().promptOuResposta(), id);
                if (SituacaoRepo.INATIVA.equals(s.get().status())) return new Resultado(Tipo.SEM_RESPOSTA, null, id);
                long agora = System.currentTimeMillis();
                if (agora - ultimoBip >= BIP_A_CADA_MS) {
                    ultimoBip = agora;
                    aviso.chamarUsuario();
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new Resultado(Tipo.SEM_RESPOSTA, null, id);
        } finally {
            esperando = null;
            pularEspera = false;
        }
    }

    private Resultado gerarPersonalizado(String portal, String pergunta, String contextoVaga, Cv cv) {
        Optional<Situacao> achada = buscar(portal, pergunta);
        String instrucao = INSTRUCAO_PADRAO;
        String aprovada = "";
        long id;
        if (achada.isPresent()) {
            Situacao s = achada.get();
            id = s.id();
            // regra "personalizada" guarda instrução (a antiga vale como a padrão); só "pergunta" traz resposta aprovada
            if ("personalizada".equals(s.tipo())) {
                if (!INSTRUCAO_ANTIGA.equals(s.promptOuResposta())) instrucao = nvl(s.promptOuResposta(), INSTRUCAO_PADRAO);
            } else aprovada = nvl(s.promptOuResposta(), "");
            repo.incrementarUso(id);
        } else {
            id = repo.inserir(portal, "personalizada", pergunta, Texto.hash(pergunta), INSTRUCAO_PADRAO,
                    "ia", SituacaoRepo.ATIVA, "");
        }
        try {
            String pedido = "PERGUNTA/CAMPO:\n" + pergunta + "\n\nINSTRUÇÕES DO CANDIDATO PARA ESTE TIPO DE CAMPO:\n" + instrucao
                    + (aprovada.isBlank() ? "" : "\n\nRESPOSTA APROVADA ANTERIORMENTE (base de fatos e de tom):\n" + aprovada)
                    + limite
                    + "\n\nVAGA:\n" + nvl(contextoVaga, "")
                    + "\n\nDADOS FIXOS:\n" + cvContexto.dadosParaPrompt()
                    + (cv != null && cv.textoExtraido() != null
                        ? "\n\nCURRÍCULO ENVIADO PARA ESTA VAGA:\n" + cv.textoExtraido()
                          + "\n\nOUTROS CURRÍCULOS DO CANDIDATO (mesma pessoa, outro foco; os fatos também valem):\n"
                          + cvContexto.textoCvs()
                        : "\n\nCURRÍCULOS DO CANDIDATO:\n" + cvContexto.textoCvs());
            String texto = ia.conversar(SYSTEM_TEXTO, pedido).trim();
            if (texto.isBlank() || "null".equalsIgnoreCase(texto)) return null;
            textos.inserir(portal, pergunta, primeiraLinha(contextoVaga), cv == null ? "" : cv.nome(), texto);
            return new Resultado(Tipo.APLICADA, texto, id);
        } catch (IaClient.IaIndisponivel e) {
            throw e; // todas as IAs falharam: a vaga é pulada (Orquestrador)
        } catch (RuntimeException e) {
            return null; // IA fora do ar: cai no fluxo normal (resposta salva ou chamar o usuário)
        }
    }

    /** Rascunho da IA para uma pendência (botão da tela de pendências): texto livre ou uma das opções, sem gravar nada. */
    public String sugerirResposta(long id) {
        Situacao s = repo.porId(id).orElseThrow(() -> new IllegalArgumentException("pendência não encontrada"));
        boolean escolha = s.padraoPergunta().contains("[opções:");
        String pedido = "PERGUNTA/CAMPO:\n" + s.padraoPergunta()
                + (escolha ? "\n\nResponda SOMENTE com o texto exato de UMA das opções, sem mais nada." : "")
                + "\n\nINSTRUÇÕES DO CANDIDATO PARA ESTE TIPO DE CAMPO:\n" + INSTRUCAO_PADRAO
                + "\n\nVAGA:\n" + nvl(s.contexto(), "")
                + "\n\nDADOS FIXOS:\n" + cvContexto.dadosParaPrompt()
                + "\n\nRESPOSTAS JÁ SALVAS (as mais parecidas com a pergunta):\n" + cvContexto.respostasSalvas(s.padraoPergunta(), 40)
                + "\n\nCURRÍCULOS:\n" + cvContexto.textoCvs();
        ia.esquecerModelo();
        String texto = ia.conversar(SYSTEM_TEXTO, pedido).trim();
        EventoJson.sugestao(s.portal(), s.padraoPergunta(), s.contexto(), texto, ia.ultimoModelo());
        return texto;
    }

    public static boolean opcional(String pergunta) {
        String n = Texto.normalizar(pergunta);
        return !n.contains("opcoes") && (n.contains("opcional") || n.contains("nao e necessario o preenchimento")
                || n.contains("caso voce") || n.contains("se aplicavel") || n.contains("quando aplicavel"));
    }

    private static boolean nulo(String s) {
        return s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim());
    }

    private static String nvl(String s, String padrao) {
        return s == null || s.isBlank() ? padrao : s;
    }

    private static String primeiraLinha(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static final String SYSTEM_TEXTO = """
            Você escreve, em primeira pessoa e em português do Brasil, a resposta de um candidato para um campo de texto livre
            de uma candidatura. O candidato é um programador sênior: responda com a segurança e a objetividade de um
            desenvolvedor sênior (fale de arquitetura, decisões técnicas e resultados concretos do currículo, sem se
            diminuir nem exagerar). Se houver LIMITE DO CAMPO no pedido, ele manda: a resposta já nasce nesse tamanho. Escreva como uma pessoa de verdade escreveria num formulário: direto, simples e natural.
            Responda exatamente o que foi perguntado, sem rodeios e sem repetir a pergunta. Seja breve e sintético: em geral
            uma frase curta (até 150 caracteres); no máximo 2 frases curtas (até 250 caracteres) em apresentação, motivação
            ou pergunta que pede "comente". Cite no máximo uma experiência do currículo, a que mais importa para a vaga.
            Valores (salário) só com o número, ex.: 10.000.
            Perfil técnico do candidato: full stack, com backend em Node.js/TypeScript, Java (Spring Boot) e PHP (Laravel) e
            frontend em React e Vue.js. Quando a pergunta falar de experiência, stack ou perfil, mostre esse leque: puxe primeiro
            o que a vaga pede e, se couber, junte Node.js, Java, React e Vue.js ao PHP/Laravel numa frase natural (ex.: "backend
            em Node.js, Java e Laravel, frontend em React e Vue"). Nunca omita PHP/Laravel quando a vaga pedir PHP.
            Se a vaga pedir uma tecnologia que ele não tem, diga isso numa frase e aponte a mais próxima que ele usa.
            Regras: use somente fatos do currículo e dos dados fixos; nunca invente empresa, tecnologia, tempo ou resultado;
            se a vaga pedir algo que o currículo não mostra, não mencione. Inglês: nunca afirme fluência oral.
            Evite tom de propaganda, clichês, adjetivos exagerados ("apaixonado", "dinâmico"), listas de siglas e saudações.
            Sem markdown, sem títulos e sem aspas. Responda SOMENTE com o texto final, pronto para colar no campo.
            """;

    /** Usuário confirma (ou corrige) a resposta da IA: vira regra ativa, origem ia_confirmada. */
    public void confirmar(long id, String respostaFinal) {
        Situacao s = repo.porId(id).orElseThrow();
        repo.atualizar(id, s.padraoPergunta(), s.hash(), respostaFinal, SituacaoRepo.ATIVA, "ia_confirmada");
    }

    /** Usuário relata a resposta quando a IA não resolveu: vira regra ativa, origem usuario. */
    public void responder(long id, String resposta) {
        Situacao s = repo.porId(id).orElseThrow();
        repo.atualizar(id, s.padraoPergunta(), s.hash(), resposta, SituacaoRepo.ATIVA, "usuario");
    }

    private static final String SYSTEM = """
            Você preenche formulários de candidatura em nome de um programador sênior: as respostas têm a segurança e a
            objetividade de um desenvolvedor sênior. Se houver LIMITE DO CAMPO no pedido (tamanho máximo ou só número), a
            resposta já tem que respeitá-lo. Responda SOMENTE com JSON:
            {"resolvido": true|false, "resposta": "o mínimo que responde: palavra, número ou frase curta (até 100 caracteres)", "motivo": "por quê"}.
            Regras: use apenas fatos do CV, dos dados fixos e das respostas já salvas. Nunca invente experiência.
            Se faltar informação, responda {"resolvido": false, "resposta": "", "motivo": "o que falta"}.
            Pretensão salarial: só o valor mensal, sem R$ e sem texto (ex.: 10.000). Regime: o da pergunta; se ela não diz, o
            da VAGA (PJ, CLT, cooperado = PJ); valores conforme as PREFERÊNCIAS. Anos de experiência: só o número (ex.: 5); tecnologia que não aparece no CV => resolvido com "0". Dados sensíveis (RG, nome do pai, senhas, bancários) => não resolvido.
            """;

    private String montarPedido(String pergunta, String contextoVaga) {
        StringBuilder sb = new StringBuilder();
        sb.append("DATA DE HOJE: ").append(java.time.LocalDate.now()).append(" (use para calcular tempo de experiência)\n\n")
                .append("PERGUNTA DO FORMULÁRIO:\n").append(pergunta)
                .append(pergunta.contains("[opções:")
                        ? pergunta.startsWith(Checkboxes.MARCADOR) ? "\n(Responda com o texto exato das opções que se aplicam, separadas por \" | \".)"
                        : "\n(Responda com o texto exato de UMA das opções.)" : "")
                .append(limite)
                .append("\n\nVAGA:\n").append(contextoVaga == null ? "" : contextoVaga);
        sb.append("\n\nDADOS FIXOS:\n").append(cvContexto.dadosParaPrompt());
        sb.append("\n\nPREFERÊNCIAS:\n").append(kv.todos(KvRepo.PREF));
        sb.append("\n\nRESPOSTAS JÁ SALVAS (as mais parecidas com a pergunta):\n").append(cvContexto.respostasSalvas(pergunta, 40));
        sb.append("\n\nCURRÍCULO:\n").append(cvContexto.textoCvs());
        return sb.toString();
    }
}
