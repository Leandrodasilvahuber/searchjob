package com.huber.searchjob.engine;

/**
 * A sessão foi parada (thread interrompida) durante uma pausa: sobe até o Orquestrador em vez de seguir dormindo. O
 * Selenium pode consumir a interrupção num comando, então só a pausa não basta para os laços de login e de formulário.
 */
public class SessaoParada extends RuntimeException {
    public SessaoParada() {
        super("sessão parada");
    }
}
