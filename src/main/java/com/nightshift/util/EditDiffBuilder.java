package com.nightshift.util;

import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.PatchRejectedException;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns model-proposed search/replace edits into a unified diff computed against the real
 * file on disk.
 *
 * <p>Models are poor at writing unified diffs by hand: they guess hunk line numbers, miscount
 * hunk sizes and drop the {@code -} line for code they replace. Asking for
 * {@code old_code}/{@code new_code} pairs instead and diffing here makes the line numbers and
 * counts correct by construction. The only thing the model must get right is quoting the
 * existing code, and even that is matched tolerantly (indentation and trailing whitespace).
 */
@Component
public class EditDiffBuilder {

    private static final int CONTEXT_LINES = 3;

    /** One search/replace edit as returned by the fix agent. */
    public record Edit(String file, String oldCode, String newCode) {}

    /**
     * Applies the edits in memory and returns the resulting unified diff.
     *
     * @param edits    edits to apply, grouped by file in the order given
     * @param repoRoot target repo root the file paths are relative to
     * @param hintLine 1-based line the incident points at; picks between duplicate matches
     * @return unified diff with {@code a/} and {@code b/} path prefixes; empty if nothing changed
     * @throws PatchRejectedException {@code PATCH_DOES_NOT_APPLY} when an edit's old code is
     *                                not found in its file
     */
    public String build(List<Edit> edits, Path repoRoot, int hintLine) {
        Map<String, List<Edit>> byFile = new LinkedHashMap<>();
        for (Edit edit : edits) {
            byFile.computeIfAbsent(normalizePath(edit.file()), k -> new ArrayList<>()).add(edit);
        }

        StringBuilder diff = new StringBuilder();
        for (Map.Entry<String, List<Edit>> entry : byFile.entrySet()) {
            String relPath = entry.getKey();
            Path file = repoRoot.resolve(relPath);
            if (!Files.exists(file)) {
                throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY,
                        "Target file does not exist: " + relPath);
            }

            String original;
            try {
                original = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            } catch (IOException e) {
                throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY,
                        "Cannot read file " + relPath + ": " + e.getMessage());
            }

            List<String> lines = new ArrayList<>(original.lines().toList());
            for (Edit edit : entry.getValue()) {
                applyEdit(lines, edit, relPath, hintLine);
            }

            String updated = String.join("\n", lines) + (original.endsWith("\n") ? "\n" : "");
            if (!updated.equals(original)) {
                diff.append(formatFileDiff(relPath, original, updated));
            }
        }
        return diff.toString();
    }

    // ── Edit application ──────────────────────────────────────────────────────

    private void applyEdit(List<String> lines, Edit edit, String relPath, int hintLine) {
        List<String> oldLines = trimBlankEdges(splitLines(edit.oldCode()));
        if (oldLines.isEmpty()) {
            throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY,
                    "Edit for " + relPath + " has an empty old_code; quote the lines to replace");
        }
        List<String> newLines = splitLines(edit.newCode());

        int start = findBlock(lines, oldLines, hintLine);
        if (start < 0) {
            throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY,
                    "old_code not found in " + relPath + ": \"" + abbreviate(oldLines.get(0).strip()) + "\"");
        }

        // Re-base the replacement onto the file's indentation if the model dedented its quote.
        String fileIndent = leadingWhitespace(lines.get(start));
        String modelIndent = leadingWhitespace(oldLines.get(0));
        List<String> reindented = new ArrayList<>(newLines.size());
        for (String nl : newLines) {
            if (!fileIndent.equals(modelIndent) && !nl.isBlank() && nl.startsWith(modelIndent)) {
                reindented.add(fileIndent + nl.substring(modelIndent.length()));
            } else {
                reindented.add(nl);
            }
        }

        for (int i = 0; i < oldLines.size(); i++) {
            lines.remove(start);
        }
        lines.addAll(start, reindented);
    }

    /** Finds {@code block} in {@code lines} ignoring indentation; nearest to the hint wins. */
    private int findBlock(List<String> lines, List<String> block, int hintLine) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        int hintIdx = Math.max(0, hintLine - 1);
        for (int i = 0; i + block.size() <= lines.size(); i++) {
            if (matchesAt(lines, i, block)) {
                int distance = Math.abs(i - hintIdx);
                if (distance < bestDistance) {
                    best = i;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean matchesAt(List<String> lines, int start, List<String> block) {
        for (int j = 0; j < block.size(); j++) {
            if (!lines.get(start + j).strip().equals(block.get(j).strip())) {
                return false;
            }
        }
        return true;
    }

    // ── Diff formatting ───────────────────────────────────────────────────────

    private String formatFileDiff(String relPath, String original, String updated) {
        RawText a = new RawText(original.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(updated.getBytes(StandardCharsets.UTF_8));
        EditList editList = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, a, b);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setContext(CONTEXT_LINES);
            formatter.format(editList, a, b);
            formatter.flush();
        } catch (IOException e) {
            throw new PatchRejectedException(ErrorCodes.PATCH_DOES_NOT_APPLY,
                    "Could not format diff for " + relPath + ": " + e.getMessage());
        }

        return "--- a/" + relPath + "\n"
                + "+++ b/" + relPath + "\n"
                + out.toString(StandardCharsets.UTF_8);
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(text.replace("\r\n", "\n").lines().toList());
    }

    private static List<String> trimBlankEdges(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isBlank()) from++;
        while (to > from && lines.get(to - 1).isBlank()) to--;
        return new ArrayList<>(lines.subList(from, to));
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        return line.substring(0, i);
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String normalizePath(String path) {
        String p = path == null ? "" : path.replace('\\', '/').strip();
        if (p.startsWith("a/") || p.startsWith("b/")) p = p.substring(2);
        if (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }
}
