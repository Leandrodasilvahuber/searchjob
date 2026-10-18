package com.huber.searchjob.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Log estruturado (uma linha JSON por evento) em data/eventos.jsonl, para analisar a sessão depois.
 * Evento "pergunta": portal, empresa, cargo, descricao, pergunta, tipo_campo, opcoes, resposta, origem.
 * Evento "sugestao": rascunho da IA pedido na tela de pendências. A descrição da vaga vai inteira em todos os eventos.
 * Evento "vaga": portal, empresa, cargo, url, descricao, status (cadastrado|pulada|erro), motivo.
 */
final class EventoJson {
    static final Path ARQUIVO = Path.of(System.getProperty("searchjob.eventos", "data/eventos.jsonl"));
    private static final ObjectMapper JSON = new ObjectMapper();

    private EventoJson() {}

    static void pergunta(String portal, String contexto, String pergunta, boolean areaDeTexto, SituacaoService.Resultado r,
                         long duracaoMs, String modelo) {
        String[] vaga = vaga(contexto);
        boolean varias = pergunta.startsWith(Checkboxes.MARCADOR);
        String texto = varias ? pergunta.substring(Checkboxes.MARCADOR.length()) : pergunta;
        List<String> opcoes = new ArrayList<>();
        int i = texto.lastIndexOf(" [opções: ");
        if (i >= 0 && texto.endsWith("]")) {
            for (String o : texto.substring(i + 10, texto.length() - 1).split(" \\| ")) opcoes.add(o);
            texto = texto.substring(0, i);
        }
        Map<String, Object> e = base("pergunta");
        e.put("portal", portal);
        e.put("empresa", vaga[0]);
        e.put("cargo", vaga[1]);
        e.put("descricao", vaga[2]);
        e.put("pergunta", texto);
        e.put("tipo_campo", varias ? "checkbox" : !opcoes.isEmpty() ? "opcoes" : areaDeTexto ? "textarea" : "texto");
        e.put("opcoes", opcoes);
        e.put("resposta", r.resposta());
        e.put("duracao_ms", duracaoMs); // tempo para decidir a resposta (regra, IA ou espera pelo usuário)
        e.put("modelo", modelo); // vazio quando veio de resposta salva, regra ou do usuário
        e.put("origem", switch (r.tipo()) {
            case APLICADA -> "automatica";
            case CONFIRMADA_PELO_USUARIO, RESPONDIDA_PELO_USUARIO -> "usuario";
            case SEM_RESPOSTA -> "sem_resposta";
            case SENSIVEL -> "sensivel";
        });
        gravar(e);
    }

    /** status: cadastrado (enviada ou preenchida em dry-run), pulada ou erro. */
    static void vaga(Vaga v, String status, String motivo) {
        Map<String, Object> e = base("vaga");
        e.put("portal", v.portal());
        e.put("empresa", v.empresa());
        e.put("cargo", v.cargo());
        e.put("url", v.url());
        e.put("descricao", corta(v.descricao()));
        e.put("status", status);
        e.put("motivo", motivo);
        gravar(e);
    }

    private static Map<String, Object> base(String tipo) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", LocalDateTime.now().withNano(0).toString());
        e.put("evento", tipo);
        return e;
    }

    /** Contexto da vaga = "empresa — cargo\ndescrição". */
    private static String[] vaga(String contexto) {
        String c = contexto == null ? "" : contexto;
        int nl = c.indexOf('\n');
        String cab = nl < 0 ? c : c.substring(0, nl);
        int d = cab.indexOf(" — ");
        return new String[] {d < 0 ? cab : cab.substring(0, d), d < 0 ? "" : cab.substring(d + 3),
                corta(nl < 0 ? "" : c.substring(nl + 1))};
    }

    /** Descrição inteira da vaga (sem cortar), só com as pontas aparadas. */
    private static String corta(String s) {
        return s == null ? "" : s.strip();
    }

    /** Rascunho gerado pelo botão "Gerar com a IA" das pendências. */
    static void sugestao(String portal, String pergunta, String contexto, String texto, String modelo) {
        String[] vaga = vaga(contexto);
        Map<String, Object> e = base("sugestao");
        e.put("portal", portal);
        e.put("empresa", vaga[0]);
        e.put("cargo", vaga[1]);
        e.put("descricao", vaga[2]);
        e.put("pergunta", pergunta);
        e.put("resposta", texto);
        e.put("modelo", modelo);
        gravar(e);
    }

    private static synchronized void gravar(Map<String, Object> e) {
        try {
            Path pai = ARQUIVO.toAbsolutePath().getParent();
            if (pai != null) Files.createDirectories(pai);
            Files.writeString(ARQUIVO, JSON.writeValueAsString(e) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            // log auxiliar: nunca derruba a sessão
        }
    }
}
