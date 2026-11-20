package dev.rendy.kirim.risk.store;

import dev.rendy.kirim.risk.rules.AlertRule;
import dev.rendy.kirim.risk.rules.DailyActivity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RiskRepository {

    private final JdbcClient jdbc;

    public RiskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false if this event was already processed (duplicate delivery). */
    public boolean markProcessed(UUID eventId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, :now)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /**
     * Adds one transfer to the account's daily totals and returns the new totals.
     * A single atomic upsert: concurrent consumers queue on the row lock instead
     * of reading, adding and writing back (which would lose updates).
     */
    public DailyActivity addOutgoing(UUID accountId, LocalDate day, long amount) {
        return jdbc.sql("""
                        INSERT INTO daily_outgoing (account_id, day, total_amount, transfer_count)
                        VALUES (:accountId, :day, :amount, 1)
                        ON CONFLICT (account_id, day) DO UPDATE
                           SET total_amount   = daily_outgoing.total_amount + EXCLUDED.total_amount,
                               transfer_count = daily_outgoing.transfer_count + 1
                        RETURNING total_amount, transfer_count
                        """)
                .param("accountId", accountId)
                .param("day", day)
                .param("amount", amount)
                .query((rs, n) -> new DailyActivity(rs.getLong("total_amount"), rs.getInt("transfer_count")))
                .single();
    }

    public Optional<DailyActivity> findDaily(UUID accountId, LocalDate day) {
        return jdbc.sql("SELECT total_amount, transfer_count FROM daily_outgoing WHERE account_id = :accountId AND day = :day")
                .param("accountId", accountId)
                .param("day", day)
                .query((rs, n) -> new DailyActivity(rs.getLong("total_amount"), rs.getInt("transfer_count")))
                .optional();
    }

    public void insertAlert(Alert alert) {
        jdbc.sql("""
                        INSERT INTO alerts (id, event_id, transfer_id, account_id, rule, amount, details, created_at)
                        VALUES (:id, :eventId, :transferId, :accountId, :rule, :amount, :details, :createdAt)
                        """)
                .param("id", alert.id())
                .param("eventId", alert.eventId())
                .param("transferId", alert.transferId())
                .param("accountId", alert.accountId())
                .param("rule", alert.rule().name())
                .param("amount", alert.amount())
                .param("details", alert.details())
                .param("createdAt", Timestamp.from(alert.createdAt()))
                .update();
    }

    public List<Alert> findAlertsByAccount(UUID accountId) {
        return jdbc.sql("SELECT * FROM alerts WHERE account_id = :accountId ORDER BY created_at DESC LIMIT 100")
                .param("accountId", accountId)
                .query(RiskRepository::mapAlert)
                .list();
    }

    public List<Alert> findRecentAlerts() {
        return jdbc.sql("SELECT * FROM alerts ORDER BY created_at DESC LIMIT 100")
                .query(RiskRepository::mapAlert)
                .list();
    }

    private static Alert mapAlert(ResultSet rs, int rowNum) throws SQLException {
        return new Alert(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                AlertRule.valueOf(rs.getString("rule")),
                rs.getLong("amount"),
                rs.getString("details"),
                rs.getTimestamp("created_at").toInstant());
    }
}
