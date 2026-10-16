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
        kv.put(KvRepo.CONFIG, "modo", "zai".equals(modo) ? "zai" : "codigo");
        return "redirect:/";
    }

    @PostMapping("/dryrun")
    public String dryRun(@RequestParam boolean ligado) {
        kv.put(KvRepo.CONFIG, "dry_run", String.valueOf(ligado));
        return "redirect:/";
    }

    private int pendencias() {
        return situacoes.porStatus(SituacaoRepo.PENDENTE_CONFIRMACAO, SituacaoRepo.AGUARDANDO_USUARIO).size();
    }
}
