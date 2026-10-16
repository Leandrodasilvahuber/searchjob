package com.huber.searchjob.engine;

import org.springframework.stereotype.Component;

/** Bips sonoros do sistema (mesmos sons da skill): complete = preciso do usuário, bell = enviada. */
@Component
public class Aviso {
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
        tocar("message-new-instant");
    }

    private void tocar(String som) {
        try {
            // volume do stream em 300% (196608 = 3x o normal); o do sistema continua o que o usuário configurou
            new ProcessBuilder("paplay", "--volume=196608", "/usr/share/sounds/freedesktop/stereo/" + som + ".oga").start();
        } catch (Exception ignored) {
            // sem áudio disponível: a tela também sinaliza a pendência
        }
    }
}
