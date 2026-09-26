package com.nightshift.service.scan;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.LogSource;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.AgentRole;
import com.nightshift.model.enums.ConfidenceLevel;
import com.nightshift.repository.CodeLocationRepository;
import com.nightshift.repository.LogSourceRepository;
import com.nightshift.util.AgentStepRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Default implementation of {@link CodeLocatorService}.
 *
 * <p>Deterministic locator:
 * <ol>
 *   <li>Parses stack trace for frames matching {@code nightshift.locator.application-packages}.</li>
 *   <li>Maps top application frame to source file in target repo, line number, and ±40 line snippet.
 *       Assigns {@code confidence = HIGH}.</li>
 *   <li>If no stack trace or application frame, falls back to {@code incident.loggerName}.
 *       If logger is in application packages, locates source file.
 *       If line can be resolved from log message or frame marker, assigns {@code confidence = MEDIUM}.
 *       Otherwise assigns {@code confidence = LOW}.</li>
 *   <li>Non-application loggers (e.g. third-party framework notices) produce no code location.</li>
 *   <li>Persists {@link CodeLocation} and audits tool step via {@link AgentStepRecorder}.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeLocatorServiceImpl implements CodeLocatorService {

    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile(
            "^\\s*at\\s+([a-zA-Z0-9_$]+(?:\\.[a-zA-Z0-9_$]+)*)\\.([a-zA-Z0-9_$<>]+\\b)\\s*\\((?:([^:)]+):(\\d+)|[^)]*)\\)");

    private static final Pattern MESSAGE_LINE_PATTERN = Pattern.compile(
            "[\\[\\(]([A-Za-z0-9_$]+\\.java):(\\d+)[\\]\\)]");

    private final NightshiftProperties props;
    private final CodeLocationRepository codeLocationRepository;
    private final LogSourceRepository logSourceRepository;
    private final AgentStepRecorder recorder;

    @Override
    @Transactional
    public Optional<CodeLocation> locate(Incident incident) {
        return locate(incident, null, null);
    }

    @Override
    @Transactional
    public Optional<CodeLocation> locate(Incident incident, ScanRun scanRun) {
        return locate(incident, scanRun, null);
    }

    @Override
    @Transactional
    public Optional<CodeLocation> locate(Incident incident, ScanRun scanRun, Path targetRepoPath) {
        long start = System.currentTimeMillis();
        Path repoPath = resolveTargetRepoPath(incident, targetRepoPath);
        String inputSummary = "incident=" + incident.getFingerprint() + " repo=" + repoPath;

        try {
            List<String> appPackages = getApplicationPackages();
            Optional<ResolvedFrame> frameOpt = findTopApplicationFrame(incident.getSampleStacktrace(), appPackages);

            ResolvedFrame resolved;
            ConfidenceLevel confidence;

            if (frameOpt.isPresent()) {
                resolved = frameOpt.get();
                confidence = ConfidenceLevel.HIGH;
            } else {
                // Fallback to logger name
                Optional<ResolvedFrame> fallbackOpt = findLoggerFallback(incident, repoPath, appPackages);
                if (fallbackOpt.isEmpty()) {
                    long latency = System.currentTimeMillis() - start;
                    recorder.recordToolCall(scanRun, incident, AgentRole.LOCATE, 0,
                            "code_locator", inputSummary, "no application code location found", latency, null);
                    return Optional.empty();
                }
                resolved = fallbackOpt.get();
                confidence = resolved.confidence != null ? resolved.confidence : ConfidenceLevel.LOW;
            }

            // Find file in repository
            Optional<Path> sourceFileOpt = findSourceFile(repoPath, resolved.className, resolved.fileName);
            if (sourceFileOpt.isEmpty()) {
                log.warn("Source file not found in repo: class={} file={} repo={}",
                        resolved.className, resolved.fileName, repoPath);
                long latency = System.currentTimeMillis() - start;
                recorder.recordToolCall(scanRun, incident, AgentRole.LOCATE, 0,
                        "code_locator", inputSummary, "source file not found: " + resolved.fileName, latency, null);
                return Optional.empty();
            }

            Path fullSourcePath = sourceFileOpt.get();
            String relativeFilePath = repoPath.relativize(fullSourcePath).toString().replace('\\', '/');

            // Read source snippet ±40 lines around target line
            List<String> fileLines = Files.readAllLines(fullSourcePath, StandardCharsets.UTF_8);
            int totalLines = fileLines.size();
            int targetLine = Math.max(1, Math.min(resolved.lineNumber, totalLines));
            int startLine = Math.max(1, targetLine - 40);
            int endLine = Math.min(totalLines, targetLine + 40);

            String snippet = String.join("\n", fileLines.subList(startLine - 1, endLine));

            // Persist or update CodeLocation
            CodeLocation location = codeLocationRepository.findByIncidentId(incident.getId())
                    .orElseGet(() -> CodeLocation.builder()
                            .incident(incident)
                            .build());

            location.setTargetRepo(repoPath.toString().replace('\\', '/'));
            location.setFilePath(relativeFilePath);
            location.setStartLine(startLine);
            location.setEndLine(endLine);
            location.setFrameSignature(resolved.signature);
            location.setConfidence(confidence);
            location.setSnippet(snippet);

            location = codeLocationRepository.save(location);

            long latency = System.currentTimeMillis() - start;
            String outputSummary = "file=" + relativeFilePath + ":" + targetLine + " confidence=" + confidence;
            recorder.recordToolCall(scanRun, incident, AgentRole.LOCATE, 0,
                    "code_locator", inputSummary, outputSummary, latency, null);

            log.info("Located code for incident {}: {}:{} ({})",
                    incident.getFingerprint(), relativeFilePath, targetLine, confidence);
            return Optional.of(location);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Failed to locate code for incident {}: {}", incident.getFingerprint(), e.getMessage(), e);
            recorder.recordToolCall(scanRun, incident, AgentRole.LOCATE, 0,
                    "code_locator", inputSummary, "error: " + e.getMessage(), latency, e);
            return Optional.empty();
        }
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private Path resolveTargetRepoPath(Incident incident, Path explicitPath) {
        if (explicitPath != null) {
            return explicitPath;
        }

        if (incident.getServiceName() != null) {
            List<LogSource> sources = logSourceRepository.findAll();
            for (LogSource src : sources) {
                if (incident.getServiceName().equals(src.getServiceName())
                        && src.getTargetRepo() != null
                        && !src.getTargetRepo().isBlank()) {
                    Path p = Path.of(src.getTargetRepo());
                    if (Files.exists(p)) return p;
                }
            }
        }

        Path demoTarget = Path.of("demo/target-repo");
        if (Files.exists(demoTarget)) {
            return demoTarget;
        }

        if (props.getWorkspace() != null) {
            Path ws = Path.of(props.getWorkspace());
            if (Files.exists(ws)) return ws;
        }

        return demoTarget;
    }

    private List<String> getApplicationPackages() {
        String pkgs = props.getLocator() != null && props.getLocator().getApplicationPackages() != null
                ? props.getLocator().getApplicationPackages()
                : "com.example";
        return Arrays.stream(pkgs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private Optional<ResolvedFrame> findTopApplicationFrame(String stacktrace, List<String> appPackages) {
        if (stacktrace == null || stacktrace.isBlank()) {
            return Optional.empty();
        }

        for (String line : stacktrace.lines().toList()) {
            Matcher m = STACK_FRAME_PATTERN.matcher(line);
            if (m.find()) {
                String className = m.group(1);
                String methodName = m.group(2);
                String fileName = m.group(3);
                String lineStr = m.group(4);

                boolean isApp = appPackages.stream().anyMatch(className::startsWith);
                if (isApp) {
                    int lineNum = lineStr != null ? Integer.parseInt(lineStr) : 1;
                    String sig = className + "." + methodName + (fileName != null ? "(" + fileName + ":" + lineNum + ")" : "");
                    return Optional.of(new ResolvedFrame(className, methodName, fileName, lineNum, sig, ConfidenceLevel.HIGH));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ResolvedFrame> findLoggerFallback(Incident incident, Path repoPath, List<String> appPackages) {
        String logger = incident.getLoggerName();
        if (logger == null || logger.isBlank()) {
            return Optional.empty();
        }

        boolean isApp = appPackages.stream().anyMatch(logger::startsWith);
        if (!isApp) {
            return Optional.empty();
        }

        String className = logger;
        String simpleName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        String fileName = simpleName + ".java";

        // Check if message mentions filename:line, e.g. [PaymentRetryClient.java:28] or (InventoryReportService.java:30)
        int resolvedLine = -1;
        String textToSearch = (incident.getSampleMessage() != null ? incident.getSampleMessage() : "") + " "
                + (incident.getNormalizedMessage() != null ? incident.getNormalizedMessage() : "");
        Matcher lineMatcher = MESSAGE_LINE_PATTERN.matcher(textToSearch);
        while (lineMatcher.find()) {
            String fName = lineMatcher.group(1);
            if (fName.equalsIgnoreCase(fileName)) {
                resolvedLine = Integer.parseInt(lineMatcher.group(2));
                break;
            }
        }

        // If not in message, inspect the source file for // NS_FRAME marker
        ConfidenceLevel conf = ConfidenceLevel.LOW;
        if (resolvedLine > 0) {
            conf = ConfidenceLevel.MEDIUM;
        } else {
            Optional<Path> srcFile = findSourceFile(repoPath, className, fileName);
            if (srcFile.isPresent()) {
                try {
                    List<String> lines = Files.readAllLines(srcFile.get(), StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains("NS_FRAME")) {
                            resolvedLine = i + 1;
                            conf = ConfidenceLevel.MEDIUM;
                            break;
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }

        if (resolvedLine <= 0) {
            resolvedLine = 1;
            conf = ConfidenceLevel.LOW;
        }

        String sig = className + ":" + resolvedLine;
        return Optional.of(new ResolvedFrame(className, "", fileName, resolvedLine, sig, conf));
    }

    private Optional<Path> findSourceFile(Path repoPath, String className, String fileName) {
        String outerClass = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        String relativeJavaPath = outerClass.replace('.', '/') + ".java";

        // Common maven/gradle locations
        List<String> candidateRoots = List.of(
                "src/main/java",
                "src/test/java",
                "src",
                ""
        );

        for (String root : candidateRoots) {
            Path candidate = root.isEmpty()
                    ? repoPath.resolve(relativeJavaPath)
                    : repoPath.resolve(root).resolve(relativeJavaPath);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }

        // Walk repo if not at standard relative path
        if (Files.isDirectory(repoPath)) {
            try (Stream<Path> walk = Files.walk(repoPath, 8)) {
                Optional<Path> matched = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(fileName != null ? fileName : outerClass + ".java"))
                        .findFirst();
                if (matched.isPresent()) {
                    return matched;
                }
            } catch (IOException e) {
                log.debug("Error walking repo path {}: {}", repoPath, e.getMessage());
            }
        }

        return Optional.empty();
    }

    private record ResolvedFrame(
            String className,
            String methodName,
            String fileName,
            int lineNumber,
            String signature,
            ConfidenceLevel confidence
    ) {}
}
