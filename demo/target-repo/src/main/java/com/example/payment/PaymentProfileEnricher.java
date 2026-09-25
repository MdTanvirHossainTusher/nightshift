package com.example.payment;

import com.example.common.NameFormatter;

/**
 * Seeded defect #8: a second caller of the same broken helper, in a different
 * service. The top stack frame is identical to defect #2, so a correct
 * fingerprint folds both into one incident and opens ONE pull request. A
 * fingerprint that hashed the whole stack trace, or the logger name, would open
 * two — which is the bug this case exists to catch.
 */
public class PaymentProfileEnricher {

    public String payeeInitials(Payee payee) {
        return NameFormatter.initials(payee.firstName(), payee.middleName(), payee.lastName()); // NS_FRAME_NPE2
    }

    public record Payee(String firstName, String middleName, String lastName) {
    }
}
