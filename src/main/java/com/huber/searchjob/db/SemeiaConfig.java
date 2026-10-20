package com.huber.searchjob.db;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Na subida, cria os portais e os provedores de IA que faltam (os provedores vêm da config antiga na primeira vez) e o
 * prompt padrão dos modelos que ainda não têm.
 */
@Component
@Order(0)
public class SemeiaConfig implements ApplicationRunner {
    private final PortalRepo portais;
    private final IaProvedorRepo provedores;
    private final KvRepo kv;
    private final IaPromptRepo prompts;

    public SemeiaConfig(PortalRepo portais, IaProvedorRepo provedores, KvRepo kv, IaPromptRepo prompts) {
        this.portais = portais;
        this.provedores = provedores;
        this.kv = kv;
        this.prompts = prompts;
    }

    @Override
    public void run(ApplicationArguments args) {
        portais.semear();
        kv.remover(KvRepo.CONFIG, "portais"); // a lista de portais agora é fixa (Portais e IA)
        provedores.semear(kv);
        // uma vez só: o laguna free leva até 2,5 min num texto longo e o prazo de 120s cortava a resposta
        if (!kv.booleano(KvRepo.CONFIG, "migrou_prazo_ia_180", false)) {
            provedores.prazoMinimo(180);
            kv.put(KvRepo.CONFIG, "migrou_prazo_ia_180", "true");
        }
        prompts.semear(provedores.todos().stream().flatMap(p -> p.modelos().stream()).distinct().toList());
    }
}
