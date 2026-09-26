package com.nightshift.config;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.LogSource;
import com.nightshift.repository.LogSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Auto-registers log sources on startup if none are present in the database.
 *
 * <p>Ensures that runs in standalone mode, Docker compose, or on cloud hosts
 * immediately have log sources configured to ingest the demo logs and resolve
 * the target repository without requiring manual database seeding.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogSourceInitializer implements ApplicationRunner {

    private final LogSourceRepository logSourceRepository;
    private final NightshiftProperties props;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initializeSourcesIfEmpty();
    }

    @Transactional
    public void initializeSourcesIfEmpty() {
        if (logSourceRepository.count() > 0) {
            return;
        }

        log.info("No log sources configured in database. Auto-registering demo log sources...");

        Path logRoot = Path.of(props.getLogRoot()).toAbsolutePath().normalize();
        String targetRepo = "demo/target-repo";
        Path workspaceRepo = Path.of(props.getWorkspace(), "target-repo");
        if (Files.exists(workspaceRepo)) {
            targetRepo = workspaceRepo.toAbsolutePath().normalize().toString();
        } else if (Files.exists(Path.of("demo/target-repo"))) {
            targetRepo = Path.of("demo/target-repo").toAbsolutePath().normalize().toString();
        }

        List<String> services = List.of("farmer-service", "payment-service", "sync-service");
        boolean registeredAny = false;

        for (String service : services) {
            Path serviceDir = logRoot.resolve(service);
            if (Files.isDirectory(serviceDir) || !Files.exists(logRoot)) {
                LogSource source = LogSource.builder()
                        .name(service)
                        .rootPath(serviceDir.toString())
                        .fileGlob("*.log")
                        .serviceName(service)
                        .targetRepo(targetRepo)
                        .enabled(true)
                        .build();
                logSourceRepository.save(source);
                log.info("Auto-registered log source '{}' at {}", service, source.getRootPath());
                registeredAny = true;
            }
        }

        if (!registeredAny) {
            LogSource defaultSource = LogSource.builder()
                    .name("default")
                    .rootPath(logRoot.toString())
                    .fileGlob("**/*.log")
                    .serviceName("default-service")
                    .targetRepo(targetRepo)
                    .enabled(true)
                    .build();
            logSourceRepository.save(defaultSource);
            log.info("Auto-registered default log source at {}", defaultSource.getRootPath());
        }
    }
}