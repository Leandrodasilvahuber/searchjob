package com.huber.searchjob.engine;

import com.huber.searchjob.db.KvRepo;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Cria o driver de cada portal pelo nome configurado. Separado do Orquestrador para poder ser trocado nos testes. */
@Component
public class FabricaDrivers {
    private final SituacaoService situacoes;
    private final KvRepo kv;

    public FabricaDrivers(SituacaoService situacoes, KvRepo kv) {
        this.situacoes = situacoes;
        this.kv = kv;
    }

    /** Vazio se o portal não é suportado. */
    public Optional<PortalDriver> criar(String portal, NavegadorFirefox navegador) {
        return Optional.ofNullable(switch (portal.trim().toLowerCase()) {
            case "gupy" -> new GupyDriver(navegador, situacoes);
            case "programathor" -> new ProgramaThorDriver(navegador, situacoes);
            case "catho" -> new CathoDriver(navegador, situacoes);
            case "indeed" -> new IndeedDriver(navegador, situacoes, kv.inteiro(KvRepo.PREF, "recencia_dias", 14));
            default -> null;
        });
    }
}
