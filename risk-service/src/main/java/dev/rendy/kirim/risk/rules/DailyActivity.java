package dev.rendy.kirim.risk.rules;

/** An account's outgoing activity for one day, after the current transfer was added. */
public record DailyActivity(long totalAmount, int transferCount) {
}
