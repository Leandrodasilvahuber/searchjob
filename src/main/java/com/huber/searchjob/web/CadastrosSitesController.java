package com.huber.searchjob.web;

import com.huber.searchjob.db.CadastroRepo;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Aba Cadastros: as contas que o robô criou nos sites das empresas (Full search), com login e senha. */
@Controller
public class CadastrosSitesController {
    private final CadastroRepo cadastros;

    public CadastrosSitesController(CadastroRepo cadastros) {
        this.cadastros = cadastros;
    }

    @GetMapping("/cadastros")
    public String pagina(Model m) {
        m.addAttribute("itens", cadastros.todos());
        m.addAttribute("pagina", "cadastros");
        return "cadastros";
    }

    /** A senha só sai do banco quando você clica em "mostrar" (POST: o filtro de origem barra outros sites). */
    @PostMapping("/cadastros/{id}/senha")
    @ResponseBody
    public Map<String, String> senha(@PathVariable long id) {
        return Map.of("senha", cadastros.senha(id));
    }

    @PostMapping("/cadastros/{id}/excluir")
    public String excluir(@PathVariable long id) {
        cadastros.excluir(id);
        return "redirect:/cadastros";
    }
}
