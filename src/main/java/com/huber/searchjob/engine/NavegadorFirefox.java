package com.huber.searchjob.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

/**
 * Firefox dirigido por Selenium com perfil persistente em data/firefox-profile
 * (faça login uma vez e a sessão fica salva). Não usa o Firefox aberto do usuário para não travar o perfil.
 */
public class NavegadorFirefox {
    private WebDriver driver;

    public synchronized WebDriver driver() {
        if (driver == null) {
            try {
                Path perfil = Path.of(System.getProperty("searchjob.firefox-profile", "data/firefox-profile")).toAbsolutePath();
                Files.createDirectories(perfil);
                apontarGeckodriver();
                FirefoxOptions o = new FirefoxOptions();
                String binario = binarioFirefox();
                if (binario != null) o.setBinary(binario);
                o.addArguments("-profile", perfil.toString());
                if (Boolean.getBoolean("searchjob.headless")) o.addArguments("-headless");
                driver = new FirefoxDriver(o);
            } catch (Exception e) {
                throw new IllegalStateException("Não foi possível abrir o Firefox: " + e.getMessage(), e);
            }
        }
        return driver;
    }

    /**
     * Firefox instalado por snap: /snap/bin/firefox é um atalho que o geckodriver não aceita
     * ("binary is not a Firefox executable"), então usa o executável real. Pode ser forçado com -Dsearchjob.firefox-binary.
     */
    static String binarioFirefox() {
        String forcado = System.getProperty("searchjob.firefox-binary");
        if (forcado != null && !forcado.isBlank()) return forcado;
        Path snap = Path.of("/snap/firefox/current/usr/lib/firefox/firefox");
        return Files.isExecutable(snap) ? snap.toString() : null;
    }

    /**
     * Usa o geckodriver do PATH (pré-requisito do README). Sem isso o Selenium Manager tenta baixar o Firefox
     * e registra um aviso 404 em ftp.mozilla.org. Pode ser forçado com -Dwebdriver.gecko.driver.
     */
    static void apontarGeckodriver() {
        if (System.getProperty("webdriver.gecko.driver") != null) return;
        String path = System.getenv("PATH");
        if (path == null) return;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path g = Path.of(dir, "geckodriver");
            if (Files.isExecutable(g)) {
                System.setProperty("webdriver.gecko.driver", g.toString());
                return;
            }
        }
    }

    public synchronized void fechar() {
        if (driver != null) {
            try {
                driver.quit();
            } finally {
                driver = null;
            }
        }
    }
}
