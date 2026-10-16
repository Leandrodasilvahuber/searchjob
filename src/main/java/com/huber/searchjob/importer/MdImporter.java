package com.huber.searchjob.importer;

import com.huber.searchjob.db.CandidaturaRepo;
import com.huber.searchjob.db.CvRepo;
import com.huber.searchjob.db.KvRepo;
import com.huber.searchjob.db.SituacaoRepo;
import com.huber.searchjob.engine.Texto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Importa o respostas_candidaturas.md e os CVs em PDF. Roda uma vez (idempotente por tabela vazia). */
@Component
public class MdImporter {
    private static final Logger log = LoggerFactory.getLogger(MdImporter.class);
    private static final Pattern PIPE = Pattern.compile("(?<!\\\\)\\|");

    private final KvRepo kv;
    private final SituacaoRepo situacoes;
    private final CandidaturaRepo candidaturas;
    private final CvRepo cvs;

    public MdImporter(KvRepo kv, SituacaoRepo situacoes, CandidaturaRepo candidaturas, CvRepo cvs) {
        this.kv = kv;
        this.situacoes = situacoes;
        this.candidaturas = candidaturas;
        this.cvs = cvs;
    }

    public record Resultado(int dadosFixos, int situacoes, int candidaturas, int cvs) {}

    public Resultado importar(Path pasta) throws IOException {
        semearPadroes();
        int d = 0, s = 0, c = 0;
        Path md = pasta.resolve("respostas_candidaturas.md");
        if (Files.exists(md)) {
            List<String> linhas = Files.readAllLines(md);
            boolean vazioSit = situacoes.todas().isEmpty();
            boolean vazioCand = candidaturas.contar() == 0;
            boolean vazioDado = kv.todos(KvRepo.DADO).isEmpty();
            String secao = "";
            for (String l : linhas) {
                if (l.startsWith("## ")) {
                    secao = l.toLowerCase();
                    continue;
                }
                if (secao.contains("dados fixos") && vazioDado) {
                    String[] p = parseDado(l);
                    if (p != null) {
                        kv.put(KvRepo.DADO, p[0], p[1]);
                        d++;
                    }
                } else if (secao.contains("perguntas din") && vazioSit) {
                    List<String> cel = celulas(l);
                    if (cel != null && cel.size() >= 2 && ehResposta(cel.get(1))) {
                        String obs = cel.size() > 5 ? cel.get(5) : "";
                        String primeira = cel.size() > 3 ? cel.get(3) : "";
                        situacoes.inserir(limpar(cel.size() > 2 ? cel.get(2) : ""), "pergunta", cel.get(0),
                                Texto.hash(cel.get(0)), cel.get(1), "md", SituacaoRepo.ATIVA,
                                (primeira + (obs.isBlank() ? "" : " | " + obs)).trim());
                        s++;
                    }
                } else if (secao.contains("candidaturas j") && vazioCand) {
                    List<String> cel = celulas(l);
                    if (cel != null && cel.size() >= 5) {
                        // algumas linhas antigas não têm a coluna de data
                        boolean temData = cel.size() >= 6;
                        String statusTxt = cel.get(temData ? 5 : 4);
                        String dataTxt = temData && cel.get(4).matches("\\d{4}-\\d{2}-\\d{2}") ? cel.get(4) : null;
                        String st = statusTxt.toLowerCase();
                        String status = st.startsWith("pulada") ? "pulada" : st.contains("erro") ? "erro" : "enviada";
                        candidaturas.inserir(cel.get(2), cel.get(0), cel.get(1), cel.get(3), null, status, statusTxt, dataTxt);
                        c++;
                    }
                }
            }
        }
        int v = importarCvs(pasta);
        Resultado r = new Resultado(d, s, c, v);
        log.info("Importação concluída: {}", r);
        return r;
    }

    private void semearPadroes() {
        padrao(KvRepo.PREF, "pretensao_pj", "10000");
        padrao(KvRepo.PREF, "pretensao_clt", "7000");
        padrao(KvRepo.PREF, "recencia_dias", "14");
        padrao(KvRepo.PREF, "termos", "full stack,Vue.js,Laravel,PHP,React,Node.js,backend");
        padrao(KvRepo.PREF, "stack_proibida", "java-only,.net,golang,python,kotlin,angular,nestjs obrigatorio,react native");
        padrao(KvRepo.PREF, "excluir_titulos", "estagio,estagiario,jovem aprendiz,banco de talentos");
        padrao(KvRepo.PREF, "exige_ingles_oral", "calls em ingles,reunioes em ingles,fluencia oral,ingles fluente,ingles avancado,english fluent,fluent english");
        padrao(KvRepo.CONFIG, "modo", "codigo");
        padrao(KvRepo.CONFIG, "portais", "gupy,programathor,catho,indeed");
        padrao(KvRepo.CONFIG, "dry_run", "true");
        padrao(KvRepo.CONFIG, "zai_url", "https://api.z.ai/api/paas/v4/chat/completions");
        padrao(KvRepo.CONFIG, "zai_modelo", "glm-4.6");
        padrao(KvRepo.CONFIG, "zai_chave", "");
        padrao(KvRepo.CONFIG, "max_vagas", "30");
    }

    private void padrao(String tabela, String chave, String valor) {
        if (kv.get(tabela, chave, null) == null) kv.put(tabela, chave, valor);
    }

    private int importarCvs(Path pasta) throws IOException {
        int n = 0;
        if (!Files.isDirectory(pasta)) return 0;
        try (var stream = Files.list(pasta)) {
            for (Path pdf : stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf")).toList()) {
                String nome = pdf.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
                if (cvs.porNome(nome).isPresent()) continue;
                cvs.salvar(nome, pdf.toString(), extrairTexto(pdf), regrasPadrao(nome));
                n++;
            }
        }
        return n;
    }

    static String regrasPadrao(String nome) {
        String n = nome.toLowerCase();
        if (n.contains("nodejs")) return "node,typescript,express,full stack js,react,next";
        if (n.contains("php")) return "php,laravel,vue,cakephp,symfony";
        if (n.contains("distribu"))
            return "microsservi,kafka,mensageria,event-driven,aws,serverless,arquiteto,tech lead,staff,sre,plataforma,java,spring";
        return "";
    }

    private String extrairTexto(Path pdf) {
        try {
            Process p = new ProcessBuilder("pdftotext", "-layout", pdf.toString(), "-")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            log.warn("pdftotext falhou para {}: {}", pdf, e.getMessage());
            return "";
        }
    }

    // --- parsing ---

    static String[] parseDado(String l) {
        if (!l.startsWith("- ")) return null;
        int i = l.indexOf(':');
        if (i < 0) return null;
        String chave = l.substring(2, i).replace("*", "").trim();
        String valor = l.substring(i + 1).trim();
        int par = valor.indexOf(" (**");
        if (par > 0) valor = valor.substring(0, par);
        return chave.isEmpty() || valor.isEmpty() ? null : new String[] {chave, valor.trim()};
    }

    static List<String> celulas(String l) {
        if (!l.startsWith("|") || l.startsWith("|---")) return null;
        String[] partes = PIPE.split(l.substring(1, l.endsWith("|") ? l.length() - 1 : l.length()), -1);
        List<String> out = new ArrayList<>();
        for (String p : partes) out.add(p.replace("\\|", "|").trim());
        if (!out.isEmpty() && (out.get(0).equals("Pergunta (como aparece no formulário)") || out.get(0).equals("Empresa")))
            return null;
        return out;
    }

    private static boolean ehResposta(String r) {
        return !r.isBlank() && !r.equals("—");
    }

    private static String limpar(String s) {
        return s.equals("—") ? "" : s;
    }
}
