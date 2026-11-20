package dev.rendy.kirim.risk.rules;

public enum AlertRule {
    /** One transfer at or above the large-transfer threshold. */
    LARGE_TRANSFER,
    /** The account's outgoing total for the day reached the daily threshold. */
    DAILY_TOTAL_EXCEEDED,
    /** The account's number of transfers for the day reached the daily threshold. */
    HIGH_DAILY_COUNT
}
