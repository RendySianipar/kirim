package dev.rendy.kirim.risk.store;

import dev.rendy.kirim.risk.rules.AlertRule;
import java.time.Instant;
import java.util.UUID;

public record Alert(
        UUID id,
        UUID eventId,
        UUID transferId,
        UUID accountId,
        AlertRule rule,
        long amount,
        String details,
        Instant createdAt) {
}
