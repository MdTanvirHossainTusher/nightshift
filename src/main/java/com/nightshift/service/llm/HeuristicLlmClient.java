package com.nightshift.service.llm;

import org.springframework.stereotype.Component;

/**
 * Fully-offline, rule-based {@link LlmClient} that drives the complete pipeline
 * without any network call or API key.
 *
 * <p><b>Triage output</b> — the {@code complete} method inspects the {@code userPrompt}
 * for the seven known demo-incident patterns and returns a JSON triage response matching
 * the ground truth in {@code demo/expected-findings.json}.
 *
 * <p>Pattern priority (first match wins):
 * <ol>
 *   <li>SQLTransientConnectionException → CRITICAL / RESOURCE_LEAK</li>
 *   <li>NullPointerException → MAJOR / NULL_DEREFERENCE</li>
 *   <li>Slow report build (InventoryReportService) → MAJOR / PERFORMANCE</li>
 *   <li>rate limited (PaymentRetryClient) → CRITICAL / RETRY_STORM</li>
 *   <li>DeserializationException → MAJOR / DATA_LOSS</li>
 *   <li>OptimisticLockException → MINOR / CONCURRENCY (already_handled, no PR)</li>
 *   <li>deprecated (ConfigDataEnvironment) → TRIVIAL / MAINTENANCE (framework_noise, no PR)</li>
 * </ol>
 *
 * <p><b>Fix output</b> — when the prompt contains a {@code fix_request} marker, the client
 * returns a template unified diff for defects 1–5 keyed on the exception / logger pattern.
 * Defects 6 and 7 never reach the fix agent because the triage marks them as not requiring
 * a PR.
 */
@Component
public class HeuristicLlmClient implements LlmClient {

    // ── Pattern constants ────────────────────────────────────────────────────

    private static final String PAT_SQL_CONNECTION  = "SQLTransientConnectionException";
    private static final String PAT_NPE             = "NullPointerException";
    private static final String PAT_SLOW_QUERY      = "Slow report build";
    private static final String PAT_RATE_LIMITED    = "rate limited";
    private static final String PAT_DESER           = "DeserializationException";
    private static final String PAT_OPTLOCK         = "OptimisticLockException";
    private static final String PAT_DEPRECATED      = "deprecated";

    // Logger names used as tie-breakers when patterns are ambiguous
    private static final String LOGGER_INVENTORY    = "com.example.sync.InventoryReportService";
    private static final String LOGGER_PAYMENT_RETRY= "com.example.payment.PaymentRetryClient";
    private static final String LOGGER_CONFIG_DATA  = "org.springframework.boot.context.config.ConfigDataEnvironment";

    // ── LlmClient ────────────────────────────────────────────────────────────

    @Override
    public String provider() {
        return "heuristic";
    }

    @Override
    public String model() {
        return "heuristic-rules";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * Dispatches to the appropriate handler based on the content of
     * {@code request.userPrompt()}.
     *
     * <p>When the prompt contains the literal token {@code fix_request} the client
     * returns a unified diff.  Otherwise it returns a JSON triage object.
     */
    @Override
    public LlmResponse complete(LlmRequest request) {
        String prompt = request.userPrompt() != null ? request.userPrompt() : "";
        if (prompt.contains("verify_request") || (request.systemPromptPath() != null && request.systemPromptPath().contains("verify"))) {
            return LlmResponse.of(buildVerify(prompt));
        }
        if (prompt.contains("fix_request")) {
            return LlmResponse.of(buildDiff(prompt));
        }
        return LlmResponse.of(buildTriage(prompt));
    }

    private String buildVerify(String prompt) {
        if (prompt.contains("reject_me") || prompt.contains("unsupported") || prompt.contains("contradict") || prompt.contains("hallucinated")) {
            return """
                    {
                      "verdict": "FAIL",
                      "notes": "Patch rejected: proposed diff contains unsupported claims and contradicts source logic.",
                      "unsupported_claims": ["Proposed changes cannot be grounded in provided source code"]
                    }
                    """;
        }
        return """
                {
                  "verdict": "PASS",
                  "notes": "Patch verified against source: changes are grounded and correctly resolve the diagnosed issue.",
                  "unsupported_claims": []
                }
                """;
    }

    // ── Triage dispatch ──────────────────────────────────────────────────────

    private String buildTriage(String prompt) {
        if (prompt.contains(PAT_SQL_CONNECTION)) {
            return triageSqlConnection();
        }
        if (prompt.contains(PAT_NPE)) {
            return triageNpe();
        }
        if (prompt.contains(PAT_SLOW_QUERY) || prompt.contains(LOGGER_INVENTORY)) {
            return triageSlowQuery();
        }
        if (prompt.contains(PAT_RATE_LIMITED) || prompt.contains(LOGGER_PAYMENT_RETRY)) {
            return triageRateLimited();
        }
        if (prompt.contains(PAT_DESER)) {
            return triageDeser();
        }
        if (prompt.contains(PAT_OPTLOCK)) {
            return triageOptlock();
        }
        if (prompt.contains(PAT_DEPRECATED) || prompt.contains(LOGGER_CONFIG_DATA)) {
            return triageDeprecated();
        }
        return triageUnknown(prompt);
    }

    // ── Individual triage templates ──────────────────────────────────────────

    /** Defect 1 — SQLTransientConnectionException: connection leaked on exception path. */
    private String triageSqlConnection() {
        return """
                {
                  "title": "Database connection leaked on the exception path",
                  "severity": "CRITICAL",
                  "category": "RESOURCE_LEAK",
                  "root_cause": "The JDBC connection is borrowed before the upstream push call but is not enclosed in try-with-resources. When upstream.push() throws, the connection is never returned to the pool, monotonically exhausting the pool under load.",
                  "future_impact": "The HikariCP pool is capped at 10. Each failed push consumes one slot permanently until the application is restarted. Under sustained error load the pool will be fully exhausted, causing all new requests to time out and cascading service failures.",
                  "recommended_action": "Wrap the connection borrow in try-with-resources so the connection is returned on every code path, including exception paths.",
                  "confidence": 0.97,
                  "should_open_pr": true,
                  "verdict": null
                }
                """;
    }

    /** Defect 2/8 — NullPointerException in NameFormatter.initials. */
    private String triageNpe() {
        return """
                {
                  "title": "NullPointerException on an optional middle name",
                  "severity": "MAJOR",
                  "category": "NULL_DEREFERENCE",
                  "root_cause": "NameFormatter.initials() calls charAt(0) on middleName unconditionally. The database schema allows middleName to be null; any farmer record without a middle name triggers the exception.",
                  "future_impact": "Affects all downstream consumers of NameFormatter.initials (card rendering and payment payee display). Every farmer or payee record without a middle name fails at render time.",
                  "recommended_action": "Treat middleName as optional: skip it when null or blank rather than dereferencing it.",
                  "confidence": 0.95,
                  "should_open_pr": true,
                  "verdict": null
                }
                """;
    }

    /** Defect 3 — N+1 slow query in InventoryReportService. */
    private String triageSlowQuery() {
        return """
                {
                  "title": "N+1 query in the daily dealer report",
                  "severity": "MAJOR",
                  "category": "PERFORMANCE",
                  "root_cause": "InventoryReportService.buildDailyReport issues one findByDealerId query per dealer inside a loop. For a 1200-dealer region this produces 1201 round-trips; the slow-query WARN and high request-duration WARN are the observable symptoms.",
                  "future_impact": "Report generation time grows linearly with the number of dealers. At current growth rates report timeouts are projected within two quarters.",
                  "recommended_action": "Replace the per-dealer query with one batched lookup (findByDealerIdIn) outside the loop, or rewrite as a single JOIN.",
                  "confidence": 0.92,
                  "should_open_pr": true,
                  "verdict": null
                }
                """;
    }

    /** Defect 4 — unbounded retry storm against rate-limited upstream. */
    private String triageRateLimited() {
        return """
                {
                  "title": "Unbounded retry against a rate-limited upstream",
                  "severity": "CRITICAL",
                  "category": "RETRY_STORM",
                  "root_cause": "PaymentRetryClient.settle() retries in a tight while(true) loop with no attempt ceiling, no backoff, and no handling of the Retry-After value in RateLimitedException. When the bank API rate-limits the service, the client hammers it as fast as the thread can run.",
                  "future_impact": "Bursty log patterns show hundreds of retries per second. This will trigger escalating rate-limit windows, potentially leading to IP bans or account suspension on the payment provider.",
                  "recommended_action": "Bound the attempts, add exponential backoff with jitter, and honour the retryAfterSeconds value from RateLimitedException.",
                  "confidence": 0.96,
                  "should_open_pr": true,
                  "verdict": null
                }
                """;
    }

    /** Defect 5 — DeserializationException silently drops Kafka messages. */
    private String triageDeser() {
        return """
                {
                  "title": "Malformed Kafka events dropped with no dead-letter path",
                  "severity": "MAJOR",
                  "category": "DATA_LOSS",
                  "root_cause": "ShipmentEventConsumer.onMessage() catches DeserializationException and returns immediately. The failed payload has no dead-letter topic, no metric increment, and no rethrow. The message is silently lost.",
                  "future_impact": "Each dropped event represents a shipment status that is never processed. Downstream reconciliation will show gaps; the actual loss count equals the WARN occurrence count (49 per week at current rate).",
                  "recommended_action": "Route failed payloads to a dead-letter topic and increment a dropped-message counter instead of returning silently.",
                  "confidence": 0.93,
                  "should_open_pr": true,
                  "verdict": null
                }
                """;
    }

    /** Defect 6 — OptimisticLockException, already handled by the caller. */
    private String triageOptlock() {
        return """
                {
                  "title": "OptimisticLockException on concurrent profile update",
                  "severity": "MINOR",
                  "category": "CONCURRENCY",
                  "root_cause": "FarmerProfileService.rename() raises OptimisticLockException when two threads update the same farmer record concurrently. The exception is already caught and the operation is retried up to MAX_ATTEMPTS=3 times before re-throwing.",
                  "future_impact": "Low. The retry logic bounds the blast radius and prevents runaway contention. The current frequency (117 occurrences per week) is within normal operational parameters.",
                  "recommended_action": "No code change required. Monitor retry counts; alert if the re-throw path starts firing, which would indicate lock contention has grown beyond the retry bound.",
                  "confidence": 0.90,
                  "should_open_pr": false,
                  "verdict": "already_handled"
                }
                """;
    }

    /** Defect 7 — deprecated Spring Boot configuration property (framework noise). */
    private String triageDeprecated() {
        return """
                {
                  "title": "Deprecated configuration property",
                  "severity": "TRIVIAL",
                  "category": "MAINTENANCE",
                  "root_cause": "A Spring Boot configuration property logged by ConfigDataEnvironment as deprecated. This is a framework-level advisory emitted at startup; no application code is broken.",
                  "future_impact": "Negligible before the next major Spring Boot upgrade. The property will be removed in a future release; update it during the next scheduled dependency upgrade.",
                  "recommended_action": "Update the property name to its replacement as documented in the Spring Boot migration guide during the next planned maintenance window.",
                  "confidence": 0.85,
                  "should_open_pr": false,
                  "verdict": "framework_noise"
                }
                """;
    }

    /** Fallback for unrecognised prompts. */
    private String triageUnknown(String prompt) {
        return """
                {
                  "title": "Unrecognised log pattern",
                  "severity": "MINOR",
                  "category": "UNCATEGORIZED",
                  "root_cause": "The heuristic client could not match this log event to a known pattern.",
                  "future_impact": "Unknown.",
                  "recommended_action": "Review the raw log event manually.",
                  "confidence": 0.10,
                  "should_open_pr": false,
                  "verdict": null
                }
                """;
    }

    // ── Fix dispatch ─────────────────────────────────────────────────────────

    private String buildDiff(String prompt) {
        if (prompt.contains(PAT_SQL_CONNECTION)) {
            return diffSqlConnection();
        }
        if (prompt.contains(PAT_NPE)) {
            return diffNpe();
        }
        if (prompt.contains(PAT_SLOW_QUERY) || prompt.contains(LOGGER_INVENTORY)) {
            return diffSlowQuery();
        }
        if (prompt.contains(PAT_RATE_LIMITED) || prompt.contains(LOGGER_PAYMENT_RETRY)) {
            return diffRateLimited();
        }
        if (prompt.contains(PAT_DESER)) {
            return diffDeser();
        }
        // Defects 6 and 7 never reach the fix agent
        return "";
    }

    // ── Unified diffs ────────────────────────────────────────────────────────

    /**
     * Defect 1 — wrap connection borrow in try-with-resources.
     * File: src/main/java/com/example/farmer/FarmerSyncService.java
     */
    private String diffSqlConnection() {
        return """
                --- a/src/main/java/com/example/farmer/FarmerSyncService.java
                +++ b/src/main/java/com/example/farmer/FarmerSyncService.java
                @@ -31,17 +31,16 @@
                     public int pushPending(List<FarmerRecord> pending) throws SQLException {
                         int pushed = 0;
                         for (FarmerRecord record : pending) {
                -            // The borrow is NOT in a try-with-resources. `upstream.push` below can
                -            // throw, and when it does this connection is never returned.
                -            Connection connection = dataSource.getConnection(); // NS_FRAME_POOL
                -
                -            PreparedStatement stmt = connection.prepareStatement(
                -                    "UPDATE farmer SET synced_at = now() WHERE id = ?");
                -            stmt.setLong(1, record.id());
                -
                -            upstream.push(record);
                -            stmt.executeUpdate();
                -
                -            connection.close();
                -            pushed++;
                +            // Connection is now enclosed in try-with-resources: it is returned to
                +            // the pool on every exit path, including when upstream.push() throws.
                +            try (Connection connection = dataSource.getConnection(); // NS_FRAME_POOL
                +                 PreparedStatement stmt = connection.prepareStatement(
                +                         "UPDATE farmer SET synced_at = now() WHERE id = ?")) {
                +                stmt.setLong(1, record.id());
                +                upstream.push(record);
                +                stmt.executeUpdate();
                +                pushed++;
                +            }
                         }
                         return pushed;
                     }
                """;
    }

    /**
     * Defect 2/8 — guard middleName null/blank in NameFormatter.initials.
     * File: src/main/java/com/example/common/NameFormatter.java
     */
    private String diffNpe() {
        return """
                --- a/src/main/java/com/example/common/NameFormatter.java
                +++ b/src/main/java/com/example/common/NameFormatter.java
                @@ -19,7 +19,9 @@
                     public static String initials(String firstName, String middleName, String lastName) {
                         StringBuilder out = new StringBuilder();
                         out.append(Character.toUpperCase(firstName.charAt(0)));
                -        out.append(Character.toUpperCase(middleName.charAt(0))); // NS_FRAME
                +        if (middleName != null && !middleName.isBlank()) {
                +            out.append(Character.toUpperCase(middleName.charAt(0)));
                +        }
                         out.append(Character.toUpperCase(lastName.charAt(0)));
                         return out.toString();
                     }
                """;
    }

    /**
     * Defect 3 — replace per-dealer query with a single batched lookup.
     * File: src/main/java/com/example/sync/InventoryReportService.java
     */
    private String diffSlowQuery() {
        return """
                --- a/src/main/java/com/example/sync/InventoryReportService.java
                +++ b/src/main/java/com/example/sync/InventoryReportService.java
                @@ -1,6 +1,7 @@
                 package com.example.sync;
                 
                 import java.util.ArrayList;
                +import java.util.List;
                 import java.util.List;
                +import java.util.Map;
                +import java.util.stream.Collectors;
                @@ -25,10 +28,15 @@
                     public List<DealerStock> buildDailyReport(String region) {
                         List<DealerStock> report = new ArrayList<>();
                -        for (Dealer dealer : dealers.findByRegion(region)) {
                -            // One round trip per dealer. Should be a single join, or one
                -            // findByDealerIdIn call outside the loop.
                -            List<StockItem> stock = items.findByDealerId(dealer.id()); // NS_FRAME_NPLUSONE
                -            report.add(new DealerStock(dealer, stock));
                -        }
                +        List<Dealer> dealerList = dealers.findByRegion(region);
                +        List<Long> ids = dealerList.stream().map(Dealer::id).toList();
                +        Map<Long, List<StockItem>> stockByDealer = items.findByDealerIdIn(ids)
                +                .stream()
                +                .collect(Collectors.groupingBy(StockItem::dealerId));
                +        for (Dealer dealer : dealerList) {
                +            List<StockItem> stock = stockByDealer.getOrDefault(dealer.id(), List.of());
                +            report.add(new DealerStock(dealer, stock));
                +        }
                         return report;
                     }
                @@ -39,4 +47,5 @@
                 
                     public interface StockItemRepository {
                         List<StockItem> findByDealerId(long dealerId);
                +        List<StockItem> findByDealerIdIn(List<Long> dealerIds);
                     }
                """;
    }

    /**
     * Defect 4 — bounded retry with exponential backoff and Retry-After support.
     * File: src/main/java/com/example/payment/PaymentRetryClient.java
     */
    private String diffRateLimited() {
        return """
                --- a/src/main/java/com/example/payment/PaymentRetryClient.java
                +++ b/src/main/java/com/example/payment/PaymentRetryClient.java
                @@ -1,5 +1,8 @@
                 package com.example.payment;
                 
                +import java.util.concurrent.ThreadLocalRandom;
                +import java.util.concurrent.TimeUnit;
                +
                 /**
                @@ -14,20 +17,38 @@
                 public class PaymentRetryClient {
                 
                +    private static final int MAX_ATTEMPTS   = 5;
                +    private static final long BASE_DELAY_MS = 200L;
                +    private static final long MAX_DELAY_MS  = 30_000L;
                +
                     private final SettlementApi api;
                 
                     public PaymentRetryClient(SettlementApi api) {
                         this.api = api;
                     }
                 
                     public SettlementResult settle(String reference, long amountMinor) {
                -        while (true) {
                -            try {
                -                return api.settle(reference, amountMinor);
                -            } catch (RateLimitedException ex) {
                -                // No backoff, no attempt ceiling, Retry-After ignored.
                -                continue; // NS_FRAME_RETRY
                +        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                +            try {
                +                return api.settle(reference, amountMinor);
                +            } catch (RateLimitedException ex) { // NS_FRAME_RETRY
                +                if (attempt == MAX_ATTEMPTS) throw ex;
                +                long delay = Math.min(
                +                        BASE_DELAY_MS * (1L << (attempt - 1)),
                +                        MAX_DELAY_MS);
                +                // Add jitter: uniform(0, delay)
                +                long jittered = ThreadLocalRandom.current().nextLong(delay + 1);
                +                // Honour Retry-After when it is more conservative than our own schedule
                +                long retryAfterMs = TimeUnit.SECONDS.toMillis(ex.retryAfterSeconds());
                +                long sleepMs = Math.max(jittered, retryAfterMs);
                +                try {
                +                    Thread.sleep(sleepMs);
                +                } catch (InterruptedException ie) {
                +                    Thread.currentThread().interrupt();
                +                    throw ex;
                +                }
                             }
                         }
                +        // Unreachable — loop always returns or throws on the last attempt.
                +        throw new IllegalStateException("settle loop exited without result");
                     }
                """;
    }

    /**
     * Defect 5 — route failed Kafka payloads to a dead-letter topic.
     * File: src/main/java/com/example/sync/ShipmentEventConsumer.java
     */
    private String diffDeser() {
        return """
                --- a/src/main/java/com/example/sync/ShipmentEventConsumer.java
                +++ b/src/main/java/com/example/sync/ShipmentEventConsumer.java
                @@ -11,19 +11,28 @@
                 public class ShipmentEventConsumer {
                 
                     private final EventDeserializer deserializer;
                     private final ShipmentHandler handler;
                +    private final DeadLetterPublisher deadLetter;
                +    private final java.util.concurrent.atomic.AtomicLong droppedCount =
                +            new java.util.concurrent.atomic.AtomicLong();
                 
                -    public ShipmentEventConsumer(EventDeserializer deserializer, ShipmentHandler handler) {
                +    public ShipmentEventConsumer(EventDeserializer deserializer,
                +                                 ShipmentHandler handler,
                +                                 DeadLetterPublisher deadLetter) {
                         this.deserializer = deserializer;
                         this.handler = handler;
                +        this.deadLetter = deadLetter;
                     }
                 
                     public void onMessage(String topic, byte[] payload) {
                         ShipmentEvent event;
                         try {
                             event = deserializer.read(payload); // NS_FRAME_DESER
                         } catch (DeserializationException ex) {
                -            // Swallowed. No DLQ, no metric, no rethrow: the event is lost.
                -            return;
                +            droppedCount.incrementAndGet();
                +            deadLetter.publish(topic + ".dlq", payload, ex);
                +            return;
                         }
                         handler.handle(event);
                     }
                +
                +    public long droppedCount() { return droppedCount.get(); }
                +
                +    public interface DeadLetterPublisher {
                +        void publish(String dlqTopic, byte[] payload, Exception cause);
                +    }
                """;
    }
}
