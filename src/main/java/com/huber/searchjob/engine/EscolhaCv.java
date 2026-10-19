package com.huber.searchjob.engine;

import com.huber.searchjob.db.Cv;
import com.huber.searchjob.db.CvRepo;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Escolhe o CV pela stack da vaga usando as palavras-chave de cada CV. Padrão em empate: o de "nodejs". */
@Component
public class EscolhaCv {
    private final CvRepo cvs;

    public EscolhaCv(CvRepo cvs) {
        this.cvs = cvs;
    }

    private static final String[] LIDERANCA = {"arquiteto", "arquiteta", "lider", "lideranca", "tech lead", "staff", "principal", "coordenador", "coordenadora"};

    /** CV inicial pelas regras; a IA pode trocar na avaliação da vaga. */
    public Optional<Cv> escolher(Vaga v) {
        List<Cv> todos = cvs.todos();
        if (todos.isEmpty()) return Optional.empty();
        String titulo = Texto.normalizar(v.cargo());
        String texto = Texto.normalizar(v.cargo() + " " + v.descricao());
        boolean lideranca = Arrays.stream(LIDERANCA).anyMatch(l -> Texto.contemPalavra(titulo, l));
        Cv padrao = todos.stream().filter(c -> c.nome().toLowerCase().contains("nodejs")).findFirst().orElse(todos.get(0));

        // arquiteto/líder/staff: vai para o CV de sistemas distribuídos, que é o de perfil sênior/liderança
        if (lideranca) {
            Optional<Cv> dist = todos.stream().filter(c -> c.nome().toLowerCase().contains("distribu")).findFirst();
            if (dist.isPresent()) return dist;
        }
        int max = 0;
        List<Cv> topo = new ArrayList<>();
        for (Cv cv : todos) {
            int pontos = pontuar(texto, cv.regrasStack());
            if (pontos > max) {
                max = pontos;
                topo.clear();
            }
            if (pontos == max && pontos > 0) topo.add(cv);
        }
        if (topo.isEmpty()) return Optional.of(padrao);
        if (topo.size() == 1) return Optional.of(topo.get(0));
        // empate: NodeJS se estiver entre os empatados
        return Optional.of(topo.contains(padrao) ? padrao : topo.get(0));
    }

    /**
     * Palavra inteira ("java" não casa com "javascript"), aceitando sufixos de stack (nodejs, springboot).
     * Termos longos (>= 9 letras) são radicais e casam por prefixo (microsservi → microsserviços).
     */
    static boolean casa(String texto, String termo) {
        String fim = termo.length() >= 9 ? "" : "(?:js|boot)?(?![a-z0-9])";
        return java.util.regex.Pattern.compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(termo) + fim)
                .matcher(texto).find();
    }

    static int pontuar(String textoNormalizado, String regras) {
        if (regras == null || regras.isBlank()) return 0;
        return (int) Arrays.stream(regras.split(",")).map(Texto::normalizar)
                .filter(r -> !r.isEmpty() && casa(textoNormalizado, r)).count();
    }
}
