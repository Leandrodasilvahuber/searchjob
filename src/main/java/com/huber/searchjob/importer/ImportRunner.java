package com.huber.searchjob.importer;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ImportRunner implements ApplicationRunner {
    private final MdImporter importer;
    private final String pasta;

    public ImportRunner(MdImporter importer, @Value("${searchjob.pasta-origem}") String pasta) {
        this.importer = importer;
        this.pasta = pasta;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        importer.importar(Path.of(pasta));
    }
}
