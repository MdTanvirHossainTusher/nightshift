package com.nightshift.service.scan;

import java.time.Instant;

/**
 * Parsed representation of one logback log event (a header line plus optional
 * continuation / stack-trace lines).
 *
 * <p>Log format expected:
 * <pre>
 * 2026-09-18 04:05:37.805 ERROR [service-name,traceId,spanId] [thread-name] logger.Name - message
 * optional.Exception: message
 *     at frame1
 *     at frame2
 * </pre>
 */
public record LogEvent(

        /** Parsed timestamp from the log header. */
        Instant timestamp,

        /** Log level: TRACE, DEBUG, INFO, WARN, ERROR. */
        String level,

        /** Logger class name. */
        String loggerName,

        /** Thread name (from the bracket after the MDC prefix). */
        String threadName,

        /** Service name extracted from the MDC prefix {@code [service-name,...]}. */
        String serviceName,

        /**
         * Trace ID extracted from the MDC prefix {@code [service-name,traceId,spanId]}.
         * May be null for log lines that do not carry MDC context.
         */
        String traceId,

        /** The log message on the header line (after the {@code -} separator). */
        String message,

        /**
         * Full stack trace text as it appeared in the log (joined continuation lines).
         * Null when the event has no stack trace attached.
         */
        String stacktrace,

        /** 1-based line number of the header line in the source file. */
        int lineNumber
) {}
