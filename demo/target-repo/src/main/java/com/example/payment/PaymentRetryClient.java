package com.example.payment;

/**
 * Calls the bank settlement API.
 *
 * <p>Seeded defect #4: the retry loop has no cap, no backoff and no special case
 * for HTTP 429. A rate-limited upstream is retried as fast as the thread can
 * issue requests, which is why the logs show bursts of hundreds of identical
 * warnings within a second. The correct fix is a policy change — bounded attempts
 * plus exponential backoff and jitter, and honouring Retry-After — not a one-line
 * edit, which makes this the case where the fix agent has to reason rather than
 * pattern-match.
 */
public class PaymentRetryClient {

    private final SettlementApi api;

    public PaymentRetryClient(SettlementApi api) {
        this.api = api;
    }

    public SettlementResult settle(String reference, long amountMinor) {
        while (true) {
            try {
                return api.settle(reference, amountMinor);
            } catch (RateLimitedException ex) {
                // No backoff, no attempt ceiling, Retry-After ignored.
                continue; // NS_FRAME_RETRY
            }
        }
    }

    public interface SettlementApi {
        SettlementResult settle(String reference, long amountMinor) throws RateLimitedException;
    }

    public static class RateLimitedException extends RuntimeException {
        private final int retryAfterSeconds;

        public RateLimitedException(int retryAfterSeconds) {
            super("Rate limited, retry after " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public record SettlementResult(String reference, String status) {
    }
}
