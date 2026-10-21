package com.huber.searchjob.engine;

import com.huber.searchjob.db.CadastroRepo;
import com.huber.searchjob.db.KvRepo;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Cria o driver de cada portal pelo nome configurado. Separado do Orquestrador para poder ser trocado nos testes. */
@Component
public class FabricaDrivers {
    private final SituacaoService situacoes;
    private final KvRepo kv;
    private final IaClient ia;
    private final CadastroRepo cadastros;
    private final CvContexto cvContexto;

    public FabricaDrivers(SituacaoService situacoes, KvRepo kv, IaClient ia, CadastroRepo cadastros, CvContexto cvContexto) {
        this.situacoes = situacoes;
        this.kv = kv;
        this.ia = ia;
        this.cadastros = cadastros;
        this.cvContexto = cvContexto;
    }

    /**
     * Full search: o driver do portal com as vagas externas (site da empresa) conduzidas pela IA em vez de puladas; no
     * LinkedIn, a busca inclui as vagas sem Candidatura simplificada.
     */
    public Optional<PortalDriver> criarFull(String portal, NavegadorFirefox navegador) {
        Optional<PortalDriver> d = "linkedin".equals(portal.trim().toLowerCase())
                ? Optional.of(new LinkedInFullDriver(navegador, situacoes)) : criar(portal, navegador);
        d.ifPresent(x -> {
            if (x instanceof PortalWebDriver w) w.usarAgenteExterno(new AgenteExterno(ia, cadastros, cvContexto, kv));
        });
        return d;
    }

    /** Vazio se o portal não é suportado. */
    public Optional<PortalDriver> criar(String portal, NavegadorFirefox navegador) {
        return Optional.ofNullable(switch (portal.trim().toLowerCase()) {
            case "gupy" -> new GupyDriver(navegador, situacoes);
            case "programathor" -> new ProgramaThorDriver(navegador, situacoes);
            case "catho" -> new CathoDriver(navegador, situacoes);
            case "solides" -> new SolidesDriver(navegador, situacoes,
                    kv.get(KvRepo.CONFIG, "solides_habilidades_base", SolidesDriver.HABILIDADES_BASE),
                    kv.inteiro(KvRepo.PREF, "recencia_dias", 14));
            case "linkedin" -> new LinkedInDriver(navegador, situacoes);
            case "indeed" -> new IndeedDriver(navegador, situacoes, kv.inteiro(KvRepo.PREF, "recencia_dias", 14));
            default -> null;
        });
    }
}
