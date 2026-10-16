package com.huber.searchjob.web;

import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.CvRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.SituacaoRepo;
import com.huber.searchjob.db.TextoGeradoRepo;
import com.huber.searchjob.engine.SituacaoService;
import com.huber.searchjob.engine.Texto;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Telas de edição: configuração, dados fixos, situações, pendências, CVs e histórico. */
@Controller
public class CadastrosController {
    private static final List<String> SEGREDOS = List.of("zai_chave");

    private final KvRepo kv;
    private final SituacaoRepo situacoes;
    private final SituacaoService situacaoService;
    private final CvRepo cvs;
    private final CandidaturaRepo candidaturas;
    private final TextoGeradoRepo textos;

    public CadastrosController(KvRepo kv, SituacaoRepo situacoes, SituacaoService situacaoService, CvRepo cvs,
                               CandidaturaRepo candidaturas, TextoGeradoRepo textos) {
        this.textos = textos;
        this.kv = kv;
        this.situacoes = situacoes;
        this.situacaoService = situacaoService;
        this.cvs = cvs;
        this.candidaturas = candidaturas;
    }

    // --- configuração (preferências + config) e dados fixos: mesma tela de chave/valor ---

    @GetMapping("/kv/{tabela}")
    public String kvTela(@PathVariable String tabela, Model m) {
        m.addAttribute("tabela", tabela);
        m.addAttribute("itens", kv.todos(tabela));
        m.addAttribute("segredos", SEGREDOS);
        m.addAttribute("pagina", tabela);
        return "kv";
    }

    @PostMapping("/kv/{tabela}")
    public String kvSalvar(@PathVariable String tabela, @RequestParam String chave, @RequestParam String valor) {
        if (!chave.isBlank()) kv.put(tabela, chave.trim(), valor);
        return "redirect:/kv/" + tabela;
    }

    @PostMapping("/kv/{tabela}/excluir")
    public String kvExcluir(@PathVariable String tabela, @RequestParam String chave) {
        kv.remover(tabela, chave);
        return "redirect:/kv/" + tabela;
    }

    // --- situações / perguntas ---

    @GetMapping("/situacoes")
    public String situacoesTela(Model m) {
        m.addAttribute("itens", situacoes.todas());
        m.addAttribute("pagina", "situacoes");
        return "situacoes";
    }

    @PostMapping("/situacoes/salvar")
    public String situacaoSalvar(@RequestParam(required = false) Long id, @RequestParam String pergunta,
                                 @RequestParam String resposta, @RequestParam(defaultValue = "ativa") String status) {
        if (id == null) {
            situacoes.inserir("", "pergunta", pergunta, Texto.hash(pergunta), resposta, "usuario", status, "");
        } else {
            String origem = situacoes.porId(id).map(s -> s.origem()).orElse("usuario");
            situacoes.atualizar(id, pergunta, Texto.hash(pergunta), resposta, status, "md".equals(origem) ? "md" : origem);
        }
        return "redirect:/situacoes";
    }

    @PostMapping("/situacoes/{id}/excluir")
    public String situacaoExcluir(@PathVariable long id) {
        situacoes.excluir(id);
        return "redirect:/situacoes";
    }

    @PostMapping("/situacoes/{id}/status")
    public String situacaoStatus(@PathVariable long id, @RequestParam String status) {
        situacoes.definirStatus(id, status);
        return "redirect:/situacoes";
    }

    // --- pendências ---

    @GetMapping("/pendencias")
    public String pendencias(Model m) {
        m.addAttribute("confirmar", situacoes.porStatus(SituacaoRepo.PENDENTE_CONFIRMACAO));
        m.addAttribute("ajuda", situacoes.porStatus(SituacaoRepo.AGUARDANDO_USUARIO));
        m.addAttribute("pagina", "pendencias");
        return "pendencias";
    }

    @PostMapping("/pendencias/{id}/confirmar")
    public String confirmar(@PathVariable long id, @RequestParam String resposta) {
        situacaoService.confirmar(id, resposta);
        return "redirect:/pendencias";
    }

    @PostMapping("/pendencias/{id}/responder")
    public String responder(@PathVariable long id, @RequestParam String resposta) {
        if (!resposta.isBlank()) situacaoService.responder(id, resposta);
        return "redirect:/pendencias";
    }

    // --- CVs ---

    @GetMapping("/cvs")
    public String cvsTela(Model m) {
        m.addAttribute("itens", cvs.todos());
        m.addAttribute("pagina", "cvs");
        return "cvs";
    }

    @PostMapping("/cvs/{id}/salvar")
    public String cvSalvar(@PathVariable long id, @RequestParam String regras, @RequestParam String texto) {
        cvs.atualizarRegras(id, regras, texto);
        return "redirect:/cvs";
    }

    @PostMapping("/cvs/{id}/excluir")
    public String cvExcluir(@PathVariable long id) {
        cvs.excluir(id);
        return "redirect:/cvs";
    }

    // --- histórico ---

    @GetMapping("/historico")
    public String historico(@RequestParam(defaultValue = "") String portal, @RequestParam(defaultValue = "") String status, Model m) {
        m.addAttribute("itens", candidaturas.listar(portal, status));
        m.addAttribute("portal", portal);
        m.addAttribute("status", status);
        m.addAttribute("pagina", "historico");
        return "historico";
    }

    @PostMapping("/historico/{id}/excluir")
    public String historicoExcluir(@PathVariable long id) {
        candidaturas.excluir(id);
        return "redirect:/historico";
    }

    // --- textos que a z.ai escreveu por vaga ---

    @GetMapping("/textos")
    public String textosTela(Model m) {
        m.addAttribute("itens", textos.recentes());
        m.addAttribute("pagina", "textos");
        return "textos";
    }

    @PostMapping("/textos/{id}/excluir")
    public String textoExcluir(@PathVariable long id) {
        textos.excluir(id);
        return "redirect:/textos";
    }
}
