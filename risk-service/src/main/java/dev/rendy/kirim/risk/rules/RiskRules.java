package dev.rendy.kirim.risk.rules;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure rule evaluation: no database, no Kafka, no clock, so every rule is
 * covered by fast unit tests.
 *
 * Daily rules fire once, on the transfer that crosses the threshold, not on
 * every transfer after it. Otherwise one busy account would flood the
 * analysts' queue with copies of the same alert.
 */
public final class RiskRules {

    private RiskRules() {
    }

    public record Finding(AlertRule rule, String details) {
    }

    /**
     * @param amount the current transfer
     * @param after  the account's daily activity including the current transfer
     */
    public static List<Finding> evaluate(long amount, DailyActivity after, RiskProperties thresholds) {
        List<Finding> findings = new ArrayList<>();

        if (amount >= thresholds.largeTransferAmount()) {
            findings.add(new Finding(AlertRule.LARGE_TRANSFER,
                    "Single transfer of " + amount + " (threshold " + thresholds.largeTransferAmount() + ")"));
        }

        long totalBefore = after.totalAmount() - amount;
        if (crosses(totalBefore, after.totalAmount(), thresholds.dailyTotalAmount())) {
            findings.add(new Finding(AlertRule.DAILY_TOTAL_EXCEEDED,
                    "Sent " + after.totalAmount() + " today (threshold " + thresholds.dailyTotalAmount() + ")"));
        }

        int countBefore = after.transferCount() - 1;
        if (crosses(countBefore, after.transferCount(), thresholds.dailyTransferCount())) {
            findings.add(new Finding(AlertRule.HIGH_DAILY_COUNT,
                    after.transferCount() + " transfers today (threshold " + thresholds.dailyTransferCount() + ")"));
        }

        return findings;
    }

    /** The business day a transfer belongs to, e.g. 18:00 UTC is already the next day in Jakarta. */
    public static LocalDate businessDay(Instant occurredAt, ZoneId zone) {
        return LocalDate.ofInstant(occurredAt, zone);
    }

    private static boolean crosses(long before, long after, long threshold) {
        return before < threshold && after >= threshold;
    }
}
