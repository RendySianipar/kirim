package dev.rendy.kirim.risk.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The event published by the transfer service (see its outbox). Fields are kept
 * as plain types (type and status as strings) so this service does not break
 * when the producer adds a new enum value; unknown JSON fields are ignored.
 */
public record TransferEvent(
        UUID eventId,
        String type,
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        long amount,
        String currency,
        String status,
        String failureReason,
        Instant occurredAt) {

    public static final String TRANSFER_COMPLETED = "TRANSFER_COMPLETED";

    public boolean isCompleted() {
        return TRANSFER_COMPLETED.equals(type);
    }

    /** Throws {@link InvalidEventException} if a required field is missing. Such a message can never succeed. */
    public TransferEvent requireValid() {
        if (eventId == null || type == null || transferId == null || fromAccountId == null || occurredAt == null) {
            throw new InvalidEventException("Transfer event is missing required fields: " + this);
        }
        if (amount <= 0) {
            throw new InvalidEventException("Transfer event " + eventId + " has a non-positive amount");
        }
        return this;
    }
}
