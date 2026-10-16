package com.huber.searchjob.web;

import com.huber.searchjob.db.CredencialRepo;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Cofre de logins dos portais: cadastrar uma vez, trocar a senha e escolher a conta ativa de cada portal. */
@Controller
public class CredenciaisController {
    private static final List<String> PORTAIS = List.of("gupy", "linkedin", "solides", "indeed", "catho", "programathor");

    private final CredencialRepo repo;

    public CredenciaisController(CredencialRepo repo) {
        this.repo = repo;
    }

    @GetMapping("/credenciais")
    public String tela(Model m) {
        m.addAttribute("itens", repo.todas());
        m.addAttribute("portais", PORTAIS);
        m.addAttribute("pagina", "credenciais");
        return "credenciais";
    }

    /** Cria o login ou troca a senha (senha em branco numa conta existente mantém a atual). */
    @PostMapping("/credenciais")
    public String salvar(@RequestParam String portal, @RequestParam String usuario, @RequestParam(defaultValue = "") String senha) {
        if (!portal.isBlank() && !usuario.isBlank()) repo.salvar(portal, usuario, senha);
        return "redirect:/credenciais";
    }

    @PostMapping("/credenciais/{id}/ativar")
    public String ativar(@PathVariable long id) {
        repo.ativar(id);
        return "redirect:/credenciais";
    }

    @PostMapping("/credenciais/{id}/excluir")
    public String excluir(@PathVariable long id) {
        repo.excluir(id);
        return "redirect:/credenciais";
    }
}
