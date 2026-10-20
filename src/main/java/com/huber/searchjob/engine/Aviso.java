package com.huber.searchjob.engine;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.springframework.stereotype.Component;

/**
 * Bips do sistema: complete = preciso do usuário, bell = enviada, message-new-instant = pulada,
 * beep suave (gerado aqui) = login/CAPTCHA, alarm-clock-elapsed = fim da sessão.
 * Os sons tocam em fila (um por vez, volume do stream em 300%); o bip de "pulada" tem intervalo mínimo
 * para uma rajada de vagas puladas não virar dezenas de sons sobrepostos.
 */
@Component
public class Aviso {
    private static final long INTERVALO_PULADA_MS = 3000;
    private static final int VOLUME_SISTEMA = 196608; // 300%: os sons do sistema são baixos
    private static final int VOLUME_BEEP = 65536; // 100%: o beep já sai na altura certa
    private Path beep;
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

    /** Login ou CAPTCHA pendente: beep curto e suave, repetido enquanto espera. */
    public void login() {
        Path som = beep();
        if (som != null) tocarArquivo(som.toString(), VOLUME_BEEP);
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
        tocarArquivo("/usr/share/sounds/freedesktop/stereo/" + som + ".oga", VOLUME_SISTEMA);
    }

    /** Senoide de 880 Hz por 180 ms com entrada/saída suaves (sem estalo), gravada uma vez num WAV temporário. */
    private synchronized Path beep() {
        if (beep != null) return beep;
        try {
            int taxa = 44100;
            int amostras = taxa * 180 / 1000;
            int rampa = taxa * 20 / 1000;
            ByteBuffer pcm = ByteBuffer.allocate(amostras * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < amostras; i++) {
                double envelope = Math.min(1, Math.min(i, amostras - 1 - i) / (double) rampa);
                pcm.putShort((short) (Math.sin(2 * Math.PI * 880 * i / taxa) * envelope * 0.35 * Short.MAX_VALUE));
            }
            Path arquivo = Files.createTempFile("searchjob-beep", ".wav");
            arquivo.toFile().deleteOnExit();
            AudioFormat formato = new AudioFormat(taxa, 16, 1, true, false);
            try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm.array()), formato, amostras)) {
                AudioSystem.write(in, AudioFileFormat.Type.WAVE, arquivo.toFile());
            }
            beep = arquivo;
        } catch (Exception ignored) {
            // sem como gravar o WAV: a tela também sinaliza o login
        }
        return beep;
    }

    private void tocarArquivo(String arquivo, int volume) {
        fila.execute(() -> {
            try {
                Process p = new ProcessBuilder("paplay", "--volume=" + volume, arquivo).redirectErrorStream(true).start();
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
