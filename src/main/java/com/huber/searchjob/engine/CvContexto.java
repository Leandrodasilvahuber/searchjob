package com.huber.searchjob.engine;

import com.huber.searchjob.db.CvRepo;
import com.huber.searchjob.db.Situacao;
import com.huber.searchjob.db.SituacaoRepo;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Monta o contexto (CV + respostas salvas) enviado à z.ai. Sempre lido do banco, nunca do .md. */
@Component
public class CvContexto {
    private final CvRepo cvs;
    private final SituacaoRepo situacoes;

    public CvContexto(CvRepo cvs, SituacaoRepo situacoes) {
        this.cvs = cvs;
        this.situacoes = situacoes;
    }

    /** Usa o CV de Sistemas Distribuídos/NodeJS como base: texto completo do primeiro CV com texto. */
    public String textoCvs() {
        return cvs.todos().stream().filter(c -> c.textoExtraido() != null && !c.textoExtraido().isBlank())
                .map(c -> "### " + c.nome() + "\n" + c.textoExtraido())
                .limit(1).collect(Collectors.joining("\n"));
    }

    public String respostasSalvas(int max) {
        return situacoes.ativas().stream().limit(max)
                .map((Situacao s) -> "- " + s.padraoPergunta() + " => " + s.promptOuResposta())
                .collect(Collectors.joining("\n"));
    }
}
