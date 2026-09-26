package com.nightshift.util;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.PatchRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates generated unified diffs against safety rules and repository bounds.
 *
 * <p>Enforces:
 * <ul>
 *   <li>{@code max-files} budget (default 3)</li>
 *   <li>{@code max-changed-lines} budget (default 120 lines)</li>
 *   <li>Deny-list paths: build files, db migrations, CI workflows, Docker, secrets, etc.</li>
 *   <li>{@code allowed-paths} matching</li>
 *   <li>Dry-run apply check against the actual files in target repo</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatchGuard {

    private static final List<String> DEFAULT_DENY_PATTERNS = List.of(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "pom.xml",
            "gradlew",
            "gradlew.bat",
            "gradle/**",
            "**/gradle/**",
            "db/migration/**",
            "**/db/migration/**",
            ".github/**",
            "**/.github/**",
            "Dockerfile*",
            "compose*.yml",
            "compose*.yaml",
            "docker-compose*.yml",
            "docker-compose*.yaml",
            ".env*",
            "**/.env*",
            "**/*.key",
            "**/*.pem",
            "**/*.secret"
    );

    private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile(
            "^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");

    private final NightshiftProperties props;

    public record PatchAnalysis(
            int filesChanged,
            int linesAdded,
            int linesRemoved,
            List<String> filesTouched
    ) {}

    /**
     * Analyzes and validates the unified diff.
     *
     * @param unifiedDiff the diff string to inspect
     * @param targetRepo  root path of the target repo
     * @return analysis metrics for the patch
     * @throws PatchRejectedException if any guard condition fails
     */
    public PatchAnalysis validate(String unifiedDiff, Path targetRepo) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY, "Unified diff is empty");
        }

        PatchAnalysis analysis = analyze(unifiedDiff);

        // 1. Max files check
        int maxFiles = props.getPatch() != null ? props.getPatch().getMaxFiles() : 3;
        if (analysis.filesChanged() > maxFiles) {
            throw new PatchRejectedException(ErrorCodes.PATCH_TOO_LARGE,
                    "Patch touches " + analysis.filesChanged() + " files; limit is " + maxFiles);
        }

        // 2. Max changed lines check
        int maxLines = props.getPatch() != null ? props.getPatch().getMaxChangedLines() : 120;
        int totalChanged = analysis.linesAdded() + analysis.linesRemoved();
        if (totalChanged > maxLines) {
            throw new PatchRejectedException(ErrorCodes.PATCH_TOO_LARGE,
                    "Patch changes " + totalChanged + " lines; limit is " + maxLines);
        }

        // 3. Deny-list and allowed-paths checks
        List<String> allowedPatterns = props.getPatch() != null && props.getPatch().getAllowedPaths() != null
                ? props.getPatch().getAllowedPaths()
                : List.of("src/main/java/**", "src/main/resources/**");

        for (String file : analysis.filesTouched()) {
            String normalized = normalizePath(file);

            // Check deny list
            for (String denyPattern : DEFAULT_DENY_PATTERNS) {
                if (matchesGlob(normalized, denyPattern)) {
                    throw new PatchRejectedException(ErrorCodes.PATCH_TOUCHES_DENIED_PATH,
                            "Patch touches denied path: " + normalized + " (matches " + denyPattern + ")");
                }
            }

            // Check allowed list
            if (!allowedPatterns.isEmpty()) {
                boolean allowed = false;
                for (String allowedPattern : allowedPatterns) {
                    if (matchesGlob(normalized, allowedPattern)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    throw new PatchRejectedException(ErrorCodes.PATCH_TOUCHES_DENIED_PATH,
                            "Patch touches path outside allowed-paths: " + normalized);
                }
            }
        }

        // 4. Dry-run apply check
        if (targetRepo != null && Files.exists(targetRepo)) {
            checkApplies(unifiedDiff, targetRepo);
        }

        return analysis;
    }

    /**
     * Parses the diff to count added/removed lines and extract touched files.
     */
    public PatchAnalysis analyze(String diff) {
        Set<String> files = new LinkedHashSet<>();
        int added = 0;
        int removed = 0;

        for (String line : diff.lines().toList()) {
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).strip();
                if (path.startsWith("b/")) {
                    path = path.substring(2);
                }
                if (!"/dev/null".equals(path)) {
                    files.add(normalizePath(path));
                }
            } else if (line.startsWith("--- ")) {
                String path = line.substring(4).strip();
                if (path.startsWith("a/")) {
                    path = path.substring(2);
                }
                if (!"/dev/null".equals(path)) {
                    files.add(normalizePath(path));
                }
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                added++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removed++;
            }
        }

        return new PatchAnalysis(files.size(), added, removed, new ArrayList<>(files));
    }

    /**
     * Verifies that the patch can apply cleanly against the current target files.
     */
    public void checkApplies(String unifiedDiff, Path targetRepo) {
        Map<String, List<String>> fileHunks = splitIntoFileDiffs(unifiedDiff);

        for (Map.Entry<String, List<String>> entry : fileHunks.entrySet()) {
            String relPath = normalizePath(entry.getKey());
            Path filePath = targetRepo.resolve(relPath);

            if (!Files.exists(filePath)) {
                throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY,
                        "Target file does not exist: " + relPath);
            }

            List<String> targetLines;
            try {
                targetLines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY,
                        "Cannot read file " + relPath + ": " + e.getMessage());
            }

            verifyFileHunks(relPath, entry.getValue(), targetLines);
        }
    }

    // ── Internal verification helpers ─────────────────────────────────────────

    private void verifyFileHunks(String filePath, List<String> hunkLines, List<String> targetLines) {
        int i = 0;
        int lastTargetEnd = 0;

        while (i < hunkLines.size()) {
            String line = hunkLines.get(i);
            Matcher matcher = HUNK_HEADER_PATTERN.matcher(line);
            if (matcher.find()) {
                int oldStart = Integer.parseInt(matcher.group(1));
                int nominalIdx = Math.max(0, oldStart - 1);
                i++;

                List<String> currentHunk = new ArrayList<>();
                while (i < hunkLines.size() && !HUNK_HEADER_PATTERN.matcher(hunkLines.get(i)).find()) {
                    currentHunk.add(hunkLines.get(i));
                    i++;
                }

                int matchedStart = findHunkStart(targetLines, nominalIdx, lastTargetEnd, currentHunk);

                if (matchedStart == -1) {
                    throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY,
                            "Hunk mismatch in " + filePath + " around line " + oldStart);
                }

                int consumed = 0;
                for (String hl : currentHunk) {
                    if (hl.startsWith("-") || hl.startsWith(" ") || hl.isEmpty()) {
                        consumed++;
                    }
                }
                lastTargetEnd = matchedStart + consumed;
            } else {
                i++;
            }
        }
    }

    /**
     * Rewrites every hunk header so its start lines and counts match where the hunk body
     * actually sits in the target file. Model-written diffs routinely carry guessed line
     * numbers and miscounted sizes, which JGit's apply rejects even when the body is right.
     * Hunks that cannot be located are left untouched for {@link #checkApplies} to reject.
     */
    public String realign(String unifiedDiff, Path targetRepo) {
        if (unifiedDiff == null || unifiedDiff.isBlank() || targetRepo == null || !Files.exists(targetRepo)) {
            return unifiedDiff;
        }

        List<String> in = unifiedDiff.replace("\r\n", "\n").lines().toList();
        StringBuilder out = new StringBuilder(unifiedDiff.length() + 64);
        List<String> targetLines = null;
        int lastTargetEnd = 0;
        int delta = 0;
        int i = 0;

        while (i < in.size()) {
            String line = in.get(i);
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).strip();
                if (path.startsWith("b/")) path = path.substring(2);
                targetLines = readLinesOrNull(targetRepo.resolve(normalizePath(path)));
                lastTargetEnd = 0;
                delta = 0;
                out.append(line).append('\n');
                i++;
                continue;
            }

            Matcher matcher = HUNK_HEADER_PATTERN.matcher(line);
            if (!matcher.find()) {
                out.append(line).append('\n');
                i++;
                continue;
            }

            List<String> hunk = new ArrayList<>();
            i++;
            while (i < in.size() && !HUNK_HEADER_PATTERN.matcher(in.get(i)).find()
                    && !in.get(i).startsWith("--- ") && !in.get(i).startsWith("+++ ")) {
                String hl = in.get(i);
                hunk.add(hl.isEmpty() ? " " : hl);
                i++;
            }
            while (!hunk.isEmpty() && hunk.get(hunk.size() - 1).isBlank()) {
                hunk.remove(hunk.size() - 1);
            }

            int oldCount = 0;
            int newCount = 0;
            for (String hl : hunk) {
                if (hl.startsWith("-")) oldCount++;
                else if (hl.startsWith("+")) newCount++;
                else if (hl.startsWith(" ")) { oldCount++; newCount++; }
            }

            int oldStart = Integer.parseInt(matcher.group(1));
            if (targetLines != null) {
                int found = findHunkStart(targetLines, Math.max(0, oldStart - 1), lastTargetEnd, hunk);
                if (found >= 0) {
                    oldStart = found + 1;
                    lastTargetEnd = found + oldCount;
                }
            }
            int newStart = oldStart + delta;
            delta += newCount - oldCount;

            out.append("@@ -").append(oldStart).append(',').append(oldCount)
                    .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
            for (String hl : hunk) {
                out.append(hl).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Locates a hunk body in the target: first at the stated line, then within ±5 lines, then
     * anywhere after the previous hunk (nearest to the stated line wins).
     */
    private int findHunkStart(List<String> targetLines, int nominalIdx, int lastTargetEnd, List<String> hunkBody) {
        if (nominalIdx >= lastTargetEnd && matchesAt(targetLines, nominalIdx, hunkBody)) {
            return nominalIdx;
        }
        for (int offset = -5; offset <= 5; offset++) {
            int candidate = nominalIdx + offset;
            if (candidate >= lastTargetEnd && candidate < targetLines.size()
                    && matchesAt(targetLines, candidate, hunkBody)) {
                return candidate;
            }
        }
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int candidate = lastTargetEnd; candidate < targetLines.size(); candidate++) {
            if (matchesAt(targetLines, candidate, hunkBody)) {
                int distance = Math.abs(candidate - nominalIdx);
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private List<String> readLinesOrNull(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean matchesAt(List<String> targetLines, int startIdx, List<String> hunkBody) {
        int idx = startIdx;
        for (String hl : hunkBody) {
            if (hl.startsWith("-") || hl.startsWith(" ") || hl.isEmpty()) {
                if (idx >= targetLines.size()) return false;
                String expected = (hl.startsWith("-") || hl.startsWith(" "))
                        ? (hl.length() > 1 ? hl.substring(1) : "")
                        : "";
                String actual = targetLines.get(idx);
                if (!actual.stripTrailing().equals(expected.stripTrailing())) {
                    return false;
                }
                idx++;
            }
        }
        return true;
    }

    private Map<String, List<String>> splitIntoFileDiffs(String unifiedDiff) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String currentFile = null;
        List<String> currentHunks = null;

        for (String line : unifiedDiff.lines().toList()) {
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).strip();
                if (path.startsWith("b/")) {
                    path = path.substring(2);
                }
                currentFile = path;
                currentHunks = new ArrayList<>();
                result.put(currentFile, currentHunks);
            } else if (currentHunks != null && (line.startsWith("@@") || line.startsWith(" ") || line.startsWith("+") || line.startsWith("-") || line.isEmpty())) {
                currentHunks.add(line);
            }
        }

        return result;
    }

    private boolean matchesGlob(String path, String pattern) {
        String glob = pattern.replace('\\', '/');
        String normPath = path.replace('\\', '/');

        // PathMatcher requires Path
        Path p = Path.of(normPath);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        if (matcher.matches(p)) return true;

        // Also test relative if pattern starts with **/
        if (!glob.startsWith("**/") && matcher.matches(Path.of(normPath))) {
            return true;
        }

        // Subpath matching for directories, e.g. .github/** matching .github/workflows/ci.yml
        if (glob.endsWith("/**")) {
            String prefix = glob.substring(0, glob.length() - 3);
            if (normPath.startsWith(prefix) || normPath.contains("/" + prefix)) {
                return true;
            }
        }

        return normPath.equals(glob) || normPath.endsWith("/" + glob);
    }

    private String normalizePath(String path) {
        String p = path.replace('\\', '/').strip();
        if (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }
}