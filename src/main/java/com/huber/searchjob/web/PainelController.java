package com.huber.searchjob.web;

import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.SituacaoRepo;
import com.huber.searchjob.engine.Encerramento;
import com.huber.searchjob.engine.Orquestrador;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PainelController {
    private final Orquestrador orq;
    private final KvRepo kv;
    private final SituacaoRepo situacoes;
    private final CandidaturaRepo candidaturas;

    private final Encerramento encerramento;

    public PainelController(Orquestrador orq, KvRepo kv, SituacaoRepo situacoes, CandidaturaRepo candidaturas,
                            Encerramento encerramento) {
        this.encerramento = encerramento;
        this.orq = orq;
        this.kv = kv;
        this.situacoes = situacoes;
        this.candidaturas = candidaturas;
    }

    @GetMapping("/")
    public String painel(Model m) {
        m.addAttribute("estado", orq.estado());
        m.addAttribute("modo", kv.get(KvRepo.CONFIG, "modo", "codigo"));
        m.addAttribute("dryRun", Boolean.parseBoolean(kv.get(KvRepo.CONFIG, "dry_run", "true")));
        m.addAttribute("relatorio", kv.booleano(KvRepo.CONFIG, "relatorio_desktop", false));
        m.addAttribute("naoInteragir", kv.booleano(KvRepo.CONFIG, "nao_interagir", false));
        m.addAttribute("pendencias", pendencias());
        m.addAttribute("pagina", "painel");
        return "painel";
    }

    @GetMapping("/api/estado")
    @ResponseBody
    public Map<String, Object> estado() {
        return Map.of("estado", orq.estado(), "pendencias", pendencias());
    }

    @PostMapping("/iniciar")
    public String iniciar() {
        orq.iniciar();
        return "redirect:/";
    }

    /** Resposta ao alerta de login/pendência: "continuar" (já logou/resolveu) ou "pular". */
    @PostMapping("/chamado/{decisao}")
    @ResponseBody
    public Map<String, Boolean> chamado(@org.springframework.web.bind.annotation.PathVariable String decisao) {
        orq.decidir(decisao);
        return Map.of("ok", true);
    }

    @PostMapping("/limparlog")
    @ResponseBody
    public Map<String, Boolean> limparLog() {
        orq.limparLog();
        return Map.of("ok", true);
    }

    @PostMapping("/parar")
    public String parar() {
        orq.parar();
        return "redirect:/";
    }

    @PostMapping(value = "/encerrar", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String encerrar() {
        encerramento.encerrar();
        return "<!doctype html><meta charset=utf-8><title>SearchJob encerrado</title>"
                + "<body style=\"font:16px system-ui;max-width:480px;margin:15vh auto;padding:0 16px\">"
                + "<h1>SearchJob encerrado</h1><p>A sessão foi parada, o Firefox do robô foi fechado e o serviço saiu da memória. "
                + "Para usar de novo, abra o SearchJob pelo atalho ou com <code>./run.sh</code>.</p></body>";
    }

    @PostMapping("/modo")
    public String modo(@RequestParam String modo) {
        kv.put(KvRepo.CONFIG, "modo", "ia".equals(modo) ? "ia" : "codigo");
        return "redirect:/";
    }

    @PostMapping("/dryrun")
    public String dryRun(@RequestParam boolean ligado) {
        kv.put(KvRepo.CONFIG, "dry_run", String.valueOf(ligado));
        return "redirect:/";
    }

    @PostMapping("/naointeragir")
    public String naoInteragir(@RequestParam boolean ligado) {
        kv.put(KvRepo.CONFIG, "nao_interagir", String.valueOf(ligado));
        return "redirect:/";
    }

    @PostMapping("/relatorio")
    public String relatorio(@RequestParam boolean ligado) {
        kv.put(KvRepo.CONFIG, "relatorio_desktop", String.valueOf(ligado));
        return "redirect:/";
    }

    private int pendencias() {
        return situacoes.porStatus(SituacaoRepo.PENDENTE_CONFIRMACAO, SituacaoRepo.AGUARDANDO_USUARIO).size();
    }
}
