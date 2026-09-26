package com.nightshift.service.scan;

import com.nightshift.config.properties.NightshiftProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a stable 16-hex-character fingerprint for a log event.
 *
 * <p>The fingerprint is derived from:
 * <pre>
 *   SHA-256( exceptionType + ":" + topApplicationFrame + ":" + normalisedMessage )
 *   truncated to 16 hex chars (64 bits)
 * </pre>
 *
 * <p><b>Normalisation</b> — variable tokens are replaced with fixed placeholders so that
 * two occurrences of the same problem produce the same fingerprint even when the log
 * line contains different farmer IDs, batch numbers, UUIDs, or IP addresses:
 * <ul>
 *   <li>UUIDs → {@code <UUID>}
 *   <li>hex strings of ≥ 8 chars (trace/span IDs) → {@code <HEX>}
 *   <li>IP addresses → {@code <IP>}
 *   <li>numbers (integer or decimal) → {@code <N>}
 *   <li>quoted strings → {@code <STR>}
 * </ul>
 *
 * <p><b>Top application frame</b> — the first {@code at …} frame in the stack trace
 * whose class name starts with one of the configured application-package prefixes.
 * Falls back to the first frame if none matches.  When there is no stack trace the
 * logger name is used instead (covers WARN-only incidents such as defect #3).
 */
@Component
public class IncidentFingerprinter {

    // ── normalisation patterns (applied in order) ────────────────────────────

    /** Standard UUID form: 8-4-4-4-12 hex groups. */
    private static final Pattern UUID_PAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Hex strings of 8+ chars that are NOT part of a longer word (trace/span IDs, hashes). */
    private static final Pattern HEX_PAT = Pattern.compile("\\b[0-9a-fA-F]{8,}\\b");

    /** IPv4 addresses. */
    private static final Pattern IP_PAT = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /** Double- or single-quoted strings. */
    private static final Pattern QUOTED_PAT = Pattern.compile("\"[^\"]*\"|'[^']*'");

    /** Remaining standalone numbers (int or decimal). */
    private static final Pattern NUM_PAT = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    // ── stack-frame pattern ───────────────────────────────────────────────────

    /** Matches {@code at some.Class.method(File.java:line)} or {@code at some.Class.method(Native)}. */
    private static final Pattern FRAME_PAT = Pattern.compile(
            "^\\s*at\\s+([^\\s(]+)\\(.*\\)\\s*$");

    // ─────────────────────────────────────────────────────────────────────────

    private final List<String> applicationPackages;

    public IncidentFingerprinter(NightshiftProperties props) {
        this.applicationPackages = List.of(
                props.getLocator().getApplicationPackages().split(","));
    }

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Computes the 16-char fingerprint for the given log event.
     *
     * <p>When a stack trace is present the normalised message is derived from the
     * <em>exception message</em> (first line of the stack trace after the colon), not
     * from the log-header message.  This ensures that two occurrences of the same fault
     * reaching the throwing frame from different callers (defects #2 and #8) collapse
     * into a single incident even though their header messages differ.
     *
     * <p>When there is no stack trace (WARN-only events such as defect #3) the header
     * message is normalised and the logger name acts as the "top frame".
     *
     * @param event the parsed log event
     * @return 16 lowercase hex characters
     */
    public String fingerprint(LogEvent event) {
        String exceptionType = extractExceptionType(event.stacktrace());
        String topFrame = resolveTopFrame(event.stacktrace(), event.loggerName());
        String normMsg;
        if (event.stacktrace() != null && !event.stacktrace().isBlank()) {
            // Use the exception message from the stack trace for stability across callers
            normMsg = normalise(extractExceptionMessage(event.stacktrace()));
        } else {
            normMsg = normalise(event.message());
        }

        String raw = exceptionType + ":" + topFrame + ":" + normMsg;
        return sha256Hex16(raw);
    }

    /**
     * Normalises a log message by replacing variable tokens with stable placeholders.
     */
    public String normalise(String message) {
        if (message == null) return "";
        String s = message;
        s = UUID_PAT.matcher(s).replaceAll("<UUID>");
        s = HEX_PAT.matcher(s).replaceAll("<HEX>");
        s = IP_PAT.matcher(s).replaceAll("<IP>");
        s = QUOTED_PAT.matcher(s).replaceAll("<STR>");
        s = NUM_PAT.matcher(s).replaceAll("<N>");
        return s.strip();
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Extracts the exception class from the first line of the stack trace.
     * Returns {@code ""} when there is no stack trace.
     *
     * <p>The first stacktrace line has the form:
     * {@code some.ExceptionClass: message} or {@code some.ExceptionClass}
     */
    private String extractExceptionType(String stacktrace) {
        if (stacktrace == null || stacktrace.isBlank()) return "";
        String firstLine = stacktrace.lines().findFirst().orElse("").strip();
        int colon = firstLine.indexOf(':');
        String candidate = colon > 0 ? firstLine.substring(0, colon) : firstLine;
        // must look like a class name (letters, dots, $)
        return candidate.matches("[\\w.$]+") ? candidate : "";
    }

    /**
     * Extracts the message part from the first line of the stack trace.
     * Returns {@code ""} when there is no colon separator (bare exception class only).
     *
     * <p>Example: {@code "java.lang.NullPointerException: Cannot invoke foo"} → {@code "Cannot invoke foo"}
     */
    private String extractExceptionMessage(String stacktrace) {
        if (stacktrace == null || stacktrace.isBlank()) return "";
        String firstLine = stacktrace.lines().findFirst().orElse("").strip();
        int colon = firstLine.indexOf(':');
        return colon >= 0 ? firstLine.substring(colon + 1).strip() : "";
    }

    /**
     * Finds the top application frame in the stack trace.
     *
     * <p>Scans stack frames in order; returns the first one whose class is under one of
     * the configured application package prefixes. Falls back to the first frame if none
     * matches.  When there is no stack trace, returns the logger name instead (so WARN-only
     * incidents like defect #3 get a stable, useful fingerprint).
     */
    private String resolveTopFrame(String stacktrace, String loggerName) {
        if (stacktrace == null || stacktrace.isBlank()) {
            return loggerName != null ? loggerName : "";
        }

        String firstFrame = null;
        for (String line : stacktrace.lines().toList()) {
            Matcher m = FRAME_PAT.matcher(line);
            if (!m.matches()) continue;
            String frameClass = m.group(1);
            if (firstFrame == null) firstFrame = frameClass;
            for (String pkg : applicationPackages) {
                String prefix = pkg.strip();
                if (frameClass.startsWith(prefix)) {
                    return frameClass;
                }
            }
        }
        return firstFrame != null ? firstFrame : loggerName != null ? loggerName : "";
    }

    private static String sha256Hex16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // first 8 bytes → 16 hex chars
            byte[] truncated = new byte[8];
            System.arraycopy(digest, 0, truncated, 0, 8);
            return HexFormat.of().formatHex(truncated);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
