package com.nightshift.service.scan;

import com.nightshift.config.properties.NightshiftProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link LogEventParser} and {@link IncidentFingerprinter}.
 * No Spring context — instantiated directly.
 */
class LogEventParserAndFingerprintTest {

    private final LogEventParser parser = new LogEventParser();
    private final IncidentFingerprinter fingerprinter;

    LogEventParserAndFingerprintTest() {
        NightshiftProperties props = new NightshiftProperties();
        props.getLocator().setApplicationPackages("com.example");
        this.fingerprinter = new IncidentFingerprinter(props);
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    @Test
    void parser_parsesHeaderLine() throws Exception {
        String line = "2026-09-18 04:05:37.805 ERROR [farmer-service,09f30bac884d3bed,7f1a89de] " +
                "[http-nio-8080-exec-5] com.example.farmer.FarmerSyncService " +
                "- Sync failed for batch 8953: could not acquire a database connection\n";
        var events = parser.parse(new java.io.ByteArrayInputStream(line.getBytes()), 1);
        assertThat(events).hasSize(1);
        var ev = events.get(0);
        assertThat(ev.level()).isEqualTo("ERROR");
        assertThat(ev.loggerName()).isEqualTo("com.example.farmer.FarmerSyncService");
        assertThat(ev.serviceName()).isEqualTo("farmer-service");
        assertThat(ev.traceId()).isEqualTo("09f30bac884d3bed");
        assertThat(ev.threadName()).isEqualTo("http-nio-8080-exec-5");
        assertThat(ev.stacktrace()).isNull();
    }

    @Test
    void parser_joinsStackTrace() throws Exception {
        String text =
                "2026-09-18 04:05:37.805 ERROR [farmer-service,trace1,span1] [thread-1] " +
                "com.example.FooService - Something went wrong\n" +
                "java.lang.NullPointerException: Cannot invoke foo\n" +
                "\tat com.example.FooService.bar(FooService.java:42)\n" +
                "\tat java.base/Thread.run(Thread.java:1)\n";
        var events = parser.parse(new java.io.ByteArrayInputStream(text.getBytes()), 1);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).stacktrace()).isNotNull();
        assertThat(events.get(0).stacktrace()).contains("NullPointerException");
        assertThat(events.get(0).stacktrace()).contains("FooService.bar");
    }

    @Test
    void parser_separatesMultipleEvents() throws Exception {
        String text =
                "2026-09-18 04:05:37.805 ERROR [s,t,sp] [th] com.example.A - Msg1\n" +
                "java.lang.RuntimeException: oops\n" +
                "\tat com.example.A.foo(A.java:1)\n" +
                "2026-09-18 04:06:00.000 WARN  [s,t2,sp2] [th2] com.example.B - Msg2\n";
        var events = parser.parse(new java.io.ByteArrayInputStream(text.getBytes()), 1);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).message()).isEqualTo("Msg1");
        assertThat(events.get(0).stacktrace()).contains("RuntimeException");
        assertThat(events.get(1).message()).isEqualTo("Msg2");
        assertThat(events.get(1).stacktrace()).isNull();
    }

    // ── Normaliser ────────────────────────────────────────────────────────────

    @Test
    void normalise_replacesUuids() {
        String msg = "entity 550e8400-e29b-41d4-a716-446655440000 not found";
        assertThat(fingerprinter.normalise(msg)).isEqualTo("entity <UUID> not found");
    }

    @Test
    void normalise_replacesNumbers() {
        String msg = "Sync failed for batch 8953: could not acquire a database connection";
        String norm = fingerprinter.normalise(msg);
        assertThat(norm).doesNotContain("8953");
        assertThat(norm).contains("<N>");
    }

    @Test
    void normalise_replacesIpAddresses() {
        String msg = "Request from 192.0.2.134 missing X-Request-Id header";
        assertThat(fingerprinter.normalise(msg)).contains("<IP>").doesNotContain("192.0.2.134");
    }

    // ── Fingerprinter ─────────────────────────────────────────────────────────

    @Test
    void fingerprint_sameInputProducesSameHash() {
        LogEvent ev = new LogEvent(Instant.now(), "ERROR", "com.example.Svc", "thread",
                "svc", "trace", "Something went wrong 123", null, 1);
        assertThat(fingerprinter.fingerprint(ev)).isEqualTo(fingerprinter.fingerprint(ev));
    }

    @Test
    void fingerprint_differentVariablesSameStructureProduceSameHash() {
        // Batch number should be normalised away
        LogEvent ev1 = new LogEvent(Instant.now(), "ERROR", "com.example.Svc", "thread",
                "svc", "trace", "Sync failed for batch 8953: connection lost", null, 1);
        LogEvent ev2 = new LogEvent(Instant.now(), "ERROR", "com.example.Svc", "thread",
                "svc", "trace2", "Sync failed for batch 9999: connection lost", null, 2);
        assertThat(fingerprinter.fingerprint(ev1)).isEqualTo(fingerprinter.fingerprint(ev2));
    }

    @Test
    void fingerprint_defects2and8_produceSameHash() {
        // farmer-service NPE via CardRenderJob → FarmerSyncService → NameFormatter.initials
        String farmerStack =
                "java.lang.NullPointerException: Cannot invoke \"String.charAt(int)\" because \"middleName\" is null\n" +
                "\tat com.example.common.NameFormatter.initials(NameFormatter.java:22)\n" +
                "\tat com.example.farmer.FarmerSyncService.cardLabel(FarmerSyncService.java:53)\n" +
                "\tat com.example.farmer.CardRenderJob.render(CardRenderJob.java:74)\n" +
                "\tat java.base/java.lang.Thread.run(Thread.java:1583)\n";

        // payment-service NPE via PayoutController → PaymentProfileEnricher → NameFormatter.initials
        String paymentStack =
                "java.lang.NullPointerException: Cannot invoke \"String.charAt(int)\" because \"middleName\" is null\n" +
                "\tat com.example.common.NameFormatter.initials(NameFormatter.java:22)\n" +
                "\tat com.example.payment.PaymentProfileEnricher.payeeInitials(PaymentProfileEnricher.java:15)\n" +
                "\tat com.example.payment.PayoutController.preview(PayoutController.java:112)\n" +
                "\tat java.base/java.lang.Thread.run(Thread.java:1583)\n";

        LogEvent farmerEv = new LogEvent(Instant.now(), "ERROR",
                "com.example.farmer.CardRenderJob", "thread1",
                "farmer-service", "trace1",
                "Failed to render card label for farmer 76598",
                farmerStack, 1);

        LogEvent paymentEv = new LogEvent(Instant.now(), "ERROR",
                "com.example.payment.PayoutController", "thread2",
                "payment-service", "trace2",
                "Payout preview failed for payee 69325",
                paymentStack, 5);

        String fp1 = fingerprinter.fingerprint(farmerEv);
        String fp2 = fingerprinter.fingerprint(paymentEv);

        assertThat(fp1)
                .as("farmer NPE and payment NPE must share the same fingerprint (defects #2 and #8)")
                .isEqualTo(fp2);
    }
}
