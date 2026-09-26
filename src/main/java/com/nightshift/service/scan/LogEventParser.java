package com.nightshift.service.scan;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses logback-formatted log lines into {@link LogEvent} objects.
 *
 * <p>Handles the pattern:
 * <pre>
 * %d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [service,traceId,spanId] [thread] logger - message
 * </pre>
 *
 * <p>Multi-line events (exception stack traces) are joined onto the preceding header
 * line's event. A new event starts when a line matches the header pattern.
 */
@Component
public class LogEventParser {

    /**
     * Header line pattern.
     * Group 1: timestamp (yyyy-MM-dd HH:mm:ss.SSS)
     * Group 2: level (TRACE/DEBUG/INFO/WARN/ERROR)
     * Group 3: MDC block content (service,traceId,spanId)
     * Group 4: thread name
     * Group 5: logger name
     * Group 6: message
     */
    private static final Pattern HEADER = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})" + // timestamp
            "\\s+(TRACE|DEBUG|INFO |WARN |ERROR|FATAL)" +               // level (padded)
            "\\s+\\[([^\\]]+)]" +                                        // [service,traceId,spanId]
            "\\s+\\[([^\\]]+)]" +                                        // [thread]
            "\\s+(\\S+)" +                                               // logger
            "\\s+-\\s+(.*)$"                                             // - message
    );

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Levels that should be treated as incidents (WARN, ERROR, FATAL). */
    public static final java.util.Set<String> INCIDENT_LEVELS = java.util.Set.of("WARN", "ERROR", "FATAL");

    /**
     * Parses all lines from the given input stream, starting at {@code startLineNumber} offset
     * (1-based; pass 1 to start from the beginning).
     *
     * @param in              stream of log bytes
     * @param startLineNumber 1-based line number where reading begins (for line-number tracking)
     * @return all parsed log events, including DEBUG/INFO
     */
    public List<LogEvent> parse(InputStream in, int startLineNumber) throws IOException {
        List<LogEvent> events = new ArrayList<>();

        String pendingTimestamp = null;
        String pendingLevel = null;
        String pendingMdc = null;
        String pendingThread = null;
        String pendingLogger = null;
        String pendingMessage = null;
        int pendingLineNumber = startLineNumber;
        List<String> stackLines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {

            int currentLine = startLineNumber;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = HEADER.matcher(line);
                if (m.matches()) {
                    // flush the pending event
                    if (pendingTimestamp != null) {
                        events.add(buildEvent(
                                pendingTimestamp, pendingLevel, pendingMdc,
                                pendingThread, pendingLogger, pendingMessage,
                                stackLines, pendingLineNumber));
                    }
                    // start a new pending event
                    pendingTimestamp = m.group(1);
                    pendingLevel = m.group(2).strip();
                    pendingMdc = m.group(3);
                    pendingThread = m.group(4);
                    pendingLogger = m.group(5);
                    pendingMessage = m.group(6);
                    pendingLineNumber = currentLine;
                    stackLines = new ArrayList<>();
                } else if (pendingTimestamp != null) {
                    // continuation / stack-trace line
                    stackLines.add(line);
                }
                currentLine++;
            }
        }

        // flush last event
        if (pendingTimestamp != null) {
            events.add(buildEvent(
                    pendingTimestamp, pendingLevel, pendingMdc,
                    pendingThread, pendingLogger, pendingMessage,
                    stackLines, pendingLineNumber));
        }

        return events;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private LogEvent buildEvent(
            String timestamp, String level, String mdc,
            String thread, String logger, String message,
            List<String> stackLines, int lineNumber) {

        Instant instant = parseTimestamp(timestamp);
        String serviceName = extractService(mdc);
        String traceId = extractTraceId(mdc);
        String stacktrace = stackLines.isEmpty() ? null : String.join("\n", stackLines);

        return new LogEvent(instant, level, logger, thread,
                serviceName, traceId, message, stacktrace, lineNumber);
    }

    private Instant parseTimestamp(String ts) {
        try {
            return LocalDateTime.parse(ts, TIMESTAMP_FMT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    /**
     * MDC block is {@code service,traceId,spanId}. Returns the first segment.
     */
    private String extractService(String mdc) {
        int comma = mdc.indexOf(',');
        return comma > 0 ? mdc.substring(0, comma) : mdc;
    }

    /**
     * Returns the second segment (traceId) or null if the MDC block has only one segment.
     */
    private String extractTraceId(String mdc) {
        int first = mdc.indexOf(',');
        if (first < 0) return null;
        int second = mdc.indexOf(',', first + 1);
        return second > 0 ? mdc.substring(first + 1, second) : mdc.substring(first + 1);
    }
}
