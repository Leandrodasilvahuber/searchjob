package com.huber.searchjob.web;

import com.huber.searchjob.db.CredencialRepo;
import com.huber.searchjob.db.IaProvedorRepo;
import com.huber.searchjob.db.PortalRepo;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Configuração de acesso: login, senha e modo de login (Google, LinkedIn ou portal) de cada portal, a conta Google
 * usada no "Entrar com Google" e os provedores de IA do fallback (URL, chave, modelos e ordem).
 */
@Controller
public class PortaisController {
    private final PortalRepo portais;
    private final CredencialRepo credenciais;
    private final IaProvedorRepo provedores;

    public PortaisController(PortalRepo portais, CredencialRepo credenciais, IaProvedorRepo provedores) {
        this.portais = portais;
        this.credenciais = credenciais;
        this.provedores = provedores;
    }

    @GetMapping("/portais")
    public String tela(Model m) {
        // usuário da conta ativa de cada portal (a senha nunca vem para a tela)
        Map<String, String> usuarios = new LinkedHashMap<>();
        credenciais.todas().stream().filter(CredencialRepo.Resumo::ativa).forEach(c -> usuarios.put(c.portal(), c.usuario()));
        m.addAttribute("portais", portais.todos());
        m.addAttribute("usuarios", usuarios);
        m.addAttribute("contas", credenciais.todas());
        m.addAttribute("provedores", provedores.resumos());
        m.addAttribute("pagina", "portais");
        return "portais";
    }

    @GetMapping("/credenciais")
    public String antiga() {
        return "redirect:/portais";
    }

    /** Modo de login e conta do portal. Senha em branco mantém a atual; usuário em branco só troca o modo. */
    @PostMapping("/portais/{chave}")
    public String salvarPortal(@PathVariable String chave, @RequestParam(defaultValue = "") String modo,
                               @RequestParam(defaultValue = "") String usuario, @RequestParam(defaultValue = "") String senha) {
        try {
            if (!modo.isBlank()) portais.definirModo(chave, modo);
            salvarConta(chave, usuario, senha);
        } catch (IllegalArgumentException e) {
            return erro(e);
        }
        return "redirect:/portais#portal-" + chave;
    }

    /** Conta Google (e-mail usado ao escolher a conta no "Entrar com Google"). */
    @PostMapping("/portais/conta-google")
    public String salvarGoogle(@RequestParam String usuario, @RequestParam(defaultValue = "") String senha) {
        try {
            salvarConta(PortalRepo.GOOGLE, usuario, senha);
        } catch (IllegalArgumentException e) {
            return erro(e);
        }
        return "redirect:/portais";
    }

    private void salvarConta(String portal, String usuario, String senha) {
        if (usuario.isBlank()) return;
        credenciais.salvar(portal, usuario, senha);
        credenciais.todas().stream()
                .filter(c -> c.portal().equals(portal) && c.usuario().equals(usuario.trim()))
                .findFirst().ifPresent(c -> credenciais.ativar(c.id()));
    }

    @PostMapping("/credenciais/{id}/ativar")
    public String ativarConta(@PathVariable long id) {
        credenciais.ativar(id);
        return "redirect:/portais#contas";
    }

    @PostMapping("/credenciais/{id}/excluir")
    public String excluirConta(@PathVariable long id) {
        credenciais.excluir(id);
        return "redirect:/portais#contas";
    }

    /** Cria (id 0) ou atualiza um provedor de IA. Chave em branco mantém a atual. */
    @PostMapping("/ia")
    public String salvarIa(@RequestParam(defaultValue = "0") long id, @RequestParam String nome, @RequestParam String url,
                           @RequestParam(defaultValue = "") String chave, @RequestParam(defaultValue = "") String modelos,
                           @RequestParam(defaultValue = "0") int ordem, @RequestParam(defaultValue = "false") boolean ativo,
                           @RequestParam(defaultValue = "3000") int intervaloMs, @RequestParam(defaultValue = "120") int timeoutS,
                           @RequestParam(defaultValue = "1") int tentativas) {
        try {
            provedores.salvar(id, nome, url, chave, modelos, ordem, ativo, intervaloMs, timeoutS, tentativas);
        } catch (IllegalArgumentException e) {
            return erro(e);
        }
        return "redirect:/portais#ia";
    }

    @PostMapping("/ia/{id}/excluir")
    public String excluirIa(@PathVariable long id) {
        provedores.excluir(id);
        return "redirect:/portais#ia";
    }

    private static String erro(IllegalArgumentException e) {
        return "redirect:/portais?erro=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8);
    }
}
