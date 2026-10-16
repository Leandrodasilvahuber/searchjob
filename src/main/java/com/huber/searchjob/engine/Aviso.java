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

    private void tocar(String som) {
        try {
            new ProcessBuilder("paplay", "/usr/share/sounds/freedesktop/stereo/" + som + ".oga").start();
        } catch (Exception ignored) {
            // sem áudio disponível: a tela também sinaliza a pendência
        }
    }
}
