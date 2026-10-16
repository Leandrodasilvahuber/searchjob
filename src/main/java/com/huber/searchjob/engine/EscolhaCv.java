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

    /** cv escolhido e se a escolha foi clara (false = nenhuma palavra-chave bateu ou houve empate entre stacks). */
    public record Escolha(Cv cv, boolean definida) {}

    private static final String[] LIDERANCA = {"arquiteto", "arquiteta", "lider", "tech lead", "staff", "principal", "coordenador", "coordenadora"};

    public Optional<Cv> escolher(Vaga v) {
        return escolherDefinido(v).map(Escolha::cv);
    }

    public Optional<Escolha> escolherDefinido(Vaga v) {
        List<Cv> todos = cvs.todos();
        if (todos.isEmpty()) return Optional.empty();
        String titulo = Texto.normalizar(v.cargo());
        String texto = Texto.normalizar(v.cargo() + " " + v.descricao());
        boolean lideranca = Arrays.stream(LIDERANCA).anyMatch(titulo::contains);
        Cv padrao = todos.stream().filter(c -> c.nome().toLowerCase().contains("nodejs")).findFirst().orElse(todos.get(0));

        // arquiteto/líder/staff: vai para o CV de sistemas distribuídos, que é o de perfil sênior/liderança
        if (lideranca) {
            Optional<Cv> dist = todos.stream().filter(c -> c.nome().toLowerCase().contains("distribu")).findFirst();
            if (dist.isPresent()) return Optional.of(new Escolha(dist.get(), true));
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
        if (topo.isEmpty()) return Optional.of(new Escolha(padrao, false));
        if (topo.size() == 1) return Optional.of(new Escolha(topo.get(0), true));
        // empate: NodeJS se estiver entre os empatados, mas sem certeza
        return Optional.of(new Escolha(topo.contains(padrao) ? padrao : topo.get(0), false));
    }

    static int pontuar(String textoNormalizado, String regras) {
        if (regras == null || regras.isBlank()) return 0;
        return (int) Arrays.stream(regras.split(",")).map(Texto::normalizar)
                .filter(r -> !r.isEmpty() && textoNormalizado.contains(r)).count();
    }
}
