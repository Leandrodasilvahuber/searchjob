package com.huber.searchjob.web;

import com.huber.searchjob.db.IaPromptRepo;
import com.huber.searchjob.db.IaProvedorRepo;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Aba Prompts dos modelos: a instrução extra de cada modelo de IA, enviada junto com todo pedido a ele. */
@Controller
public class PromptsController {
    private final IaPromptRepo prompts;
    private final IaProvedorRepo provedores;

    public PromptsController(IaPromptRepo prompts, IaProvedorRepo provedores) {
        this.prompts = prompts;
        this.provedores = provedores;
    }

    @GetMapping("/prompts")
    public String pagina(Model m) {
        var modelos = provedores.todos().stream().flatMap(p -> p.modelos().stream()).distinct().toList();
        m.addAttribute("prompts", prompts.de(modelos));
        m.addAttribute("padrao", IaPromptRepo.PADRAO);
        m.addAttribute("pagina", "prompts");
        return "prompts";
    }

    @PostMapping("/prompts")
    public String salvar(@RequestParam String modelo, @RequestParam(defaultValue = "") String prompt) {
        try {
            prompts.salvar(modelo, prompt);
        } catch (IllegalArgumentException e) {
            return "redirect:/prompts?erro=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8);
        }
        return "redirect:/prompts?salvo=" + URLEncoder.encode(modelo, StandardCharsets.UTF_8);
    }
}
