package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.Situacao;
import com.huber.searchjob.db.SituacaoRepo;
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

    public SituacaoService(SituacaoRepo repo, KvRepo kv, ZaiClient zai, CvContexto cvContexto, Aviso aviso) {
        this.aviso = aviso;
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
                .map(s -> Map2.of(s, Texto.similaridade(pergunta, s.padraoPergunta())))
                .filter(p -> p.score >= LIMIAR_SIMILARIDADE)
                .max(Comparator.comparingDouble(p -> p.score))
                .map(p -> p.s);
    }

    private record Map2(Situacao s, double score) {
        static Map2 of(Situacao s, double d) { return new Map2(s, d); }
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
        sb.append("\n\nDADOS FIXOS:\n").append(kv.todos(KvRepo.DADO));
        sb.append("\n\nPREFERÊNCIAS:\n").append(kv.todos(KvRepo.PREF));
        sb.append("\n\nRESPOSTAS JÁ SALVAS (amostra):\n").append(cvContexto.respostasSalvas(40));
        sb.append("\n\nCURRÍCULO:\n").append(cvContexto.textoCvs());
        return sb.toString();
    }
}
