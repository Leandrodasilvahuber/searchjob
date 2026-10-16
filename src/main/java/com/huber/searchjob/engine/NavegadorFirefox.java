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
                Path perfil = Path.of("data", "firefox-profile").toAbsolutePath();
                Files.createDirectories(perfil);
                FirefoxOptions o = new FirefoxOptions();
                o.addArguments("-profile", perfil.toString());
                driver = new FirefoxDriver(o);
            } catch (Exception e) {
                throw new IllegalStateException("Não foi possível abrir o Firefox: " + e.getMessage(), e);
            }
        }
        return driver;
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
