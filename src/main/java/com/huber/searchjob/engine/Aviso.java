package com.huber.searchjob.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Bips do sistema: complete = preciso do usuário, bell = enviada, message-new-instant = pulada,
 * phone-incoming-call = login/CAPTCHA, alarm-clock-elapsed = fim da sessão.
 * Os sons tocam em fila (um por vez, volume do stream em 300%); o bip de "pulada" tem intervalo mínimo
 * para uma rajada de vagas puladas não virar dezenas de sons sobrepostos.
 */
@Component
public class Aviso {
    private static final long INTERVALO_PULADA_MS = 3000;
    private final ExecutorService fila = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "searchjob-aviso");
        t.setDaemon(true);
        return t;
    });
    private volatile long ultimaPulada;

    public void chamarUsuario() {
        tocar("complete");
    }

    public void enviada() {
        tocar("bell");
    }

    /** Login ou CAPTCHA pendente: som diferente do chamado comum. */
    public void login() {
        tocar("phone-incoming-call");
    }

    /** Fim da sessão. */
    public void fim() {
        tocar("alarm-clock-elapsed");
    }

    public void pulada() {
        long agora = System.currentTimeMillis();
        if (agora - ultimaPulada < INTERVALO_PULADA_MS) return;
        ultimaPulada = agora;
        tocar("message-new-instant");
    }

    private void tocar(String som) {
        fila.execute(() -> {
            try {
                Process p = new ProcessBuilder("paplay", "--volume=196608",
                        "/usr/share/sounds/freedesktop/stereo/" + som + ".oga").redirectErrorStream(true).start();
                p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // sem áudio disponível: a tela também sinaliza a pendência
            }
        });
    }
}
