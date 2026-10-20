package com.huber.searchjob.db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Instrução extra de cada modelo de IA (aba Prompts dos modelos), enviada junto com todo pedido a ele. */
@Repository
public class IaPromptRepo {
    /** Semeado para os modelos sem prompt: trata as respostas que saíram erradas no teste de textos. */
    public static final String PADRAO = """
            Valores e anos: responda só o número, sem texto, moeda ou unidade (ex.: 7000, 15).
            Pretensão salarial: UM único valor. Use o regime que a pergunta ou a vaga indicar; se a vaga aceitar CLT e PJ \
            (ou não disser), use o valor de CLT. Nunca devolva dois valores juntos (errado: 7000,10000).
            Escolha entre opções: devolva o texto exato da opção, sem explicação.""";

    private final JdbcClient jdbc;

    public IaPromptRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Prompt do modelo, ou vazio. */
    public String de(String modelo) {
        return jdbc.sql("SELECT prompt FROM ia_modelo_prompt WHERE modelo = ?").param(modelo.trim())
                .query(String.class).optional().orElse("");
    }

    /** Prompt de cada modelo pedido, na ordem dada (vazio para os que não têm). */
    public Map<String, String> de(List<String> modelos) {
        Map<String, String> out = new LinkedHashMap<>();
        modelos.forEach(m -> out.put(m, de(m)));
        return out;
    }

    public void salvar(String modelo, String prompt) {
        if (modelo.isBlank()) throw new IllegalArgumentException("Informe o modelo");
        jdbc.sql("INSERT INTO ia_modelo_prompt(modelo, prompt) VALUES (?, ?) ON CONFLICT(modelo) DO UPDATE SET prompt = excluded.prompt")
                .param(modelo.trim()).param(prompt.strip()).update();
    }

    /** Cria o prompt padrão para os modelos que ainda não têm linha; não mexe nos já editados (nem nos apagados de propósito). */
    public void semear(List<String> modelos) {
        for (String m : modelos)
            jdbc.sql("INSERT OR IGNORE INTO ia_modelo_prompt(modelo, prompt) VALUES (?, ?)").param(m).param(PADRAO).update();
    }
}
