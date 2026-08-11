package com.example.blackbox.wow.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
@Slf4j
public class RaiderIoAbandonedRunJsonImporter implements ApplicationRunner {

    private final Path importDirectory;
    private final RaiderIoAbandonedRunService service;

    public RaiderIoAbandonedRunJsonImporter(
            @Value("${wow.recap.abandoned-import.directory:helper}") String importDirectory,
            RaiderIoAbandonedRunService service
    ) {
        this.importDirectory = Path.of(importDirectory);
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!Files.isDirectory(importDirectory)) {
            log.info("Raider.IO abandoned-run import directory does not exist: {}", importDirectory);
            return;
        }

        List<Path> snapshots;
        try (var files = Files.list(importDirectory)) {
            snapshots = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(
                            "raiderio-stats-mythic-plus-abandoned-runs-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }

        int imported = 0;
        int failed = 0;
        for (Path snapshot : snapshots) {
            try {
                if (service.importSnapshot(snapshot)) {
                    imported++;
                }
            } catch (Exception e) {
                failed++;
                log.error("Could not import Raider.IO abandoned-run snapshot: {}", snapshot, e);
            }
        }
        log.info(
                "Raider.IO abandoned-run snapshots: {} found, {} imported, {} failed",
                snapshots.size(),
                imported,
                failed
        );
    }
}
