package com.huber.searchjob.engine;

import com.huber.searchjob.db.CvRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.Situacao;
import com.huber.searchjob.db.SituacaoRepo;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Monta o contexto (CV + respostas salvas) enviado à z.ai. Sempre lido do banco, nunca do .md. */
@Component
public class CvContexto {
    private final CvRepo cvs;
    private final SituacaoRepo situacoes;
    private final KvRepo kv;

    public CvContexto(CvRepo cvs, SituacaoRepo situacoes, KvRepo kv) {
        this.kv = kv;
        this.cvs = cvs;
        this.situacoes = situacoes;
    }

    /** Usa o CV de Sistemas Distribuídos/NodeJS como base: texto completo do primeiro CV com texto. */
    public String textoCvs() {
        return cvs.todos().stream().filter(c -> c.textoExtraido() != null && !c.textoExtraido().isBlank())
                .map(c -> "### " + c.nome() + "\n" + c.textoExtraido())
                .limit(1).collect(Collectors.joining("\n"));
    }

    private static final java.util.regex.Pattern CPF = java.util.regex.Pattern.compile("\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}");
    private static final java.util.Set<String> CHAVES_SENSIVEIS =
            java.util.Set.of("cpf", "rg", "pai", "mae", "senha", "nascimento", "banc", "documento");

    /** Dados fixos que podem ir a um LLM: sem CPF, RG, filiação, nascimento nem dados bancários. */
    public java.util.Map<String, String> dadosParaPrompt() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        kv.todos(KvRepo.DADO).forEach((k, v) -> {
            if (!sensivel(k) && !CPF.matcher(v).find()) out.put(k, v);
        });
        return out;
    }

    private static boolean sensivel(String texto) {
        String n = Texto.normalizar(texto);
        return java.util.Arrays.stream(n.split(" ")).anyMatch(CHAVES_SENSIVEIS::contains)
                || n.contains("nome da mae") || n.contains("banc");
    }

    public String respostasSalvas(int max) {
        return situacoes.ativas().stream()
                .filter(s -> !sensivel(s.padraoPergunta()) && !CPF.matcher(String.valueOf(s.promptOuResposta())).find())
                .limit(max)
                .map((Situacao s) -> "- " + s.padraoPergunta() + " => " + s.promptOuResposta())
                .collect(Collectors.joining("\n"));
    }
}
