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
 * 2) senão pergunta à z.ai; se ela resolve, fica pendente_confirmacao e chama o usuário;
 * 3) se ela não resolve, fica aguardando_usuario e o usuário relata a resposta.
 */
@Service
public class SituacaoService {
    static final double LIMIAR_SIMILARIDADE = 0.8;

    public enum Tipo { APLICADA, CONFIRMADA_PELO_USUARIO, RESPONDIDA_PELO_USUARIO, SEM_RESPOSTA, SENSIVEL }

    public record Resultado(Tipo tipo, String resposta, long situacaoId) {}

    private static final String[] SENSIVEL = {"rg", "numero do rg", "nome do pai", "nome completo do pai",
            "senha", "conta bancaria", "agencia", "dados bancarios", "cartao"};

    private final SituacaoRepo repo;
    private final KvRepo kv;
    private final ZaiClient zai;
    private final CvContexto cvContexto;
    private final Aviso aviso;
    private final TextoGeradoRepo textos;

    public SituacaoService(SituacaoRepo repo, KvRepo kv, ZaiClient zai, CvContexto cvContexto, Aviso aviso,
                           TextoGeradoRepo textos) {
        this.aviso = aviso;
        this.textos = textos;
        this.repo = repo;
        this.kv = kv;
        this.zai = zai;
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
            "Escreva uma resposta personalizada para esta vaga, destacando as experiências do currículo mais relevantes para ela.";

    /** Texto livre (área de texto ou pergunta aberta): a z.ai escreve sob medida para cada vaga em vez de reaproveitar. */
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
        if (!sensivel(pergunta) && discursiva(pergunta, areaDeTexto) && zai.configurado()) {
            Resultado r = gerarPersonalizado(portal, pergunta, contextoVaga, cv);
            if (r != null) return r;
        }
        if (sensivel(pergunta)) {
            aviso.chamarUsuario();
            return new Resultado(Tipo.SENSIVEL, null, 0);
        }

        Optional<Situacao> achada = buscar(portal, pergunta);
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
            aviso.chamarUsuario();
            return aguardar(aberta.get().id(),
                    SituacaoRepo.PENDENTE_CONFIRMACAO.equals(aberta.get().status()) ? Tipo.CONFIRMADA_PELO_USUARIO : Tipo.RESPONDIDA_PELO_USUARIO,
                    continuar);
        }
        if (zai.configurado()) {
            JsonNode r;
            try {
                r = zai.conversarJson(SYSTEM, montarPedido(pergunta, contextoVaga));
            } catch (RuntimeException e) {
                r = null;
            }
            if (r != null && r.path("resolvido").asBoolean(false) && !r.path("resposta").asText("").isBlank()) {
                id = repo.inserir(portal, "pergunta", pergunta, hash, r.path("resposta").asText(),
                        "zai", SituacaoRepo.PENDENTE_CONFIRMACAO, contextoVaga);
                aviso.chamarUsuario();
                return aguardar(id, Tipo.CONFIRMADA_PELO_USUARIO, continuar);
            }
        }
        id = repo.inserir(portal, "pergunta", pergunta, hash, null, "usuario",
                SituacaoRepo.AGUARDANDO_USUARIO, contextoVaga);
        aviso.chamarUsuario();
        return aguardar(id, Tipo.RESPONDIDA_PELO_USUARIO, continuar);
    }

    private Resultado aguardar(long id, Tipo tipoOk, BooleanSupplier continuar) {
        while (continuar.getAsBoolean()) {
            Optional<Situacao> s = repo.porId(id);
            if (s.isEmpty()) return new Resultado(Tipo.SEM_RESPOSTA, null, id);
            if (SituacaoRepo.ATIVA.equals(s.get().status()))
                return new Resultado(tipoOk, s.get().promptOuResposta(), id);
            if (SituacaoRepo.INATIVA.equals(s.get().status())) return new Resultado(Tipo.SEM_RESPOSTA, null, id);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new Resultado(Tipo.SEM_RESPOSTA, null, id);
    }

    private Resultado gerarPersonalizado(String portal, String pergunta, String contextoVaga, Cv cv) {
        Optional<Situacao> achada = buscar(portal, pergunta);
        String instrucao = INSTRUCAO_PADRAO;
        String aprovada = "";
        long id;
        if (achada.isPresent()) {
            Situacao s = achada.get();
            id = s.id();
            if ("personalizada".equals(s.tipo())) instrucao = nvl(s.promptOuResposta(), INSTRUCAO_PADRAO);
            else aprovada = nvl(s.promptOuResposta(), "");
            repo.incrementarUso(id);
        } else {
            id = repo.inserir(portal, "personalizada", pergunta, Texto.hash(pergunta), INSTRUCAO_PADRAO,
                    "zai", SituacaoRepo.ATIVA, "");
        }
        try {
            String pedido = "PERGUNTA/CAMPO:\n" + pergunta + "\n\nINSTRUÇÕES DO CANDIDATO PARA ESTE TIPO DE CAMPO:\n" + instrucao
                    + (aprovada.isBlank() ? "" : "\n\nRESPOSTA APROVADA ANTERIORMENTE (base de fatos e de tom):\n" + aprovada)
                    + "\n\nVAGA:\n" + nvl(contextoVaga, "")
                    + "\n\nDADOS FIXOS:\n" + cvContexto.dadosParaPrompt()
                    + "\n\nCURRÍCULO ESCOLHIDO PARA ESTA VAGA:\n"
                    + (cv != null && cv.textoExtraido() != null ? cv.textoExtraido() : cvContexto.textoCvs());
            String texto = zai.conversar(SYSTEM_TEXTO, pedido).trim();
            if (texto.isBlank()) return null;
            textos.inserir(portal, pergunta, primeiraLinha(contextoVaga), cv == null ? "" : cv.nome(), texto);
            return new Resultado(Tipo.APLICADA, texto, id);
        } catch (RuntimeException e) {
            return null; // z.ai fora do ar: cai no fluxo normal (resposta salva ou chamar o usuário)
        }
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
            (apresentação, motivação, experiência) de uma candidatura. Adapte ao máximo à vaga indicada, destacando do currículo
            fornecido apenas o que for relevante para ela.
            Regras: use somente fatos do currículo e dos dados fixos; nunca invente empresa, tecnologia, tempo ou resultado;
            se a vaga pedir algo que o currículo não mostra, não mencione. Inglês: nunca afirme fluência oral.
            Tom profissional e direto, sem clichês nem saudações genéricas, de 3 a 6 frases (até 800 caracteres), sem markdown,
            sem títulos e sem aspas. Responda SOMENTE com o texto final, pronto para colar no campo.
            """;

    /** Usuário confirma (ou corrige) a resposta da z.ai: vira regra ativa, origem zai_confirmada. */
    public void confirmar(long id, String respostaFinal) {
        Situacao s = repo.porId(id).orElseThrow();
        repo.atualizar(id, s.padraoPergunta(), s.hash(), respostaFinal, SituacaoRepo.ATIVA, "zai_confirmada");
    }

    /** Usuário relata a resposta quando a z.ai não resolveu: vira regra ativa, origem usuario. */
    public void responder(long id, String resposta) {
        Situacao s = repo.porId(id).orElseThrow();
        repo.atualizar(id, s.padraoPergunta(), s.hash(), resposta, SituacaoRepo.ATIVA, "usuario");
    }

    private static final String SYSTEM = """
            Você preenche formulários de candidatura em nome do candidato. Responda SOMENTE com JSON:
            {"resolvido": true|false, "resposta": "texto curto pronto para o campo", "motivo": "por quê"}.
            Regras: use apenas fatos do CV, dos dados fixos e das respostas já salvas. Nunca invente experiência.
            Se faltar informação, responda {"resolvido": false, "resposta": "", "motivo": "o que falta"}.
            Pretensão: PJ e CLT conforme as preferências. Dados sensíveis (RG, nome do pai, senhas, bancários) => não resolvido.
            """;

    private String montarPedido(String pergunta, String contextoVaga) {
        StringBuilder sb = new StringBuilder();
        sb.append("PERGUNTA DO FORMULÁRIO:\n").append(pergunta).append("\n\nVAGA:\n").append(contextoVaga == null ? "" : contextoVaga);
        sb.append("\n\nDADOS FIXOS:\n").append(cvContexto.dadosParaPrompt());
        sb.append("\n\nPREFERÊNCIAS:\n").append(kv.todos(KvRepo.PREF));
        sb.append("\n\nRESPOSTAS JÁ SALVAS (amostra):\n").append(cvContexto.respostasSalvas(40));
        sb.append("\n\nCURRÍCULO:\n").append(cvContexto.textoCvs());
        return sb.toString();
    }
}
