package com.huber.searchjob.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Derruba o serviço inteiro (JVM) para liberar a memória; fecha antes a sessão e o Firefox. */
@Component
public class Encerramento {
    private static final Logger log = LoggerFactory.getLogger(Encerramento.class);
    private final Orquestrador orquestrador;
    private final ConfigurableApplicationContext contexto;

    public Encerramento(Orquestrador orquestrador, ConfigurableApplicationContext contexto) {
        this.orquestrador = orquestrador;
        this.contexto = contexto;
    }

    /** Para a sessão, espera o Firefox fechar e encerra o processo em segundo plano (a resposta HTTP ainda sai). */
    public void encerrar() {
        Thread t = new Thread(() -> {
            try {
                orquestrador.parar();
                orquestrador.aguardarFim(10_000);
                Thread.sleep(500); // dá tempo da resposta chegar ao navegador
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                log.info("Encerrando o SearchJob a pedido do usuário");
                contexto.close();
                System.exit(0);
            }
        }, "searchjob-encerramento");
        t.setDaemon(false);
        t.start();
    }
}
