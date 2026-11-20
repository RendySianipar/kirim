package dev.rendy.kirim.risk.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskRulesTest {

    private final RiskProperties thresholds =
            new RiskProperties("transfer-events", "Asia/Jakarta", 25_000_000, 100_000_000, 20);

    private List<AlertRule> rules(long amount, long totalAfter, int countAfter) {
        return RiskRules.evaluate(amount, new DailyActivity(totalAfter, countAfter), thresholds)
                .stream().map(RiskRules.Finding::rule).toList();
    }

    @Test
    void anOrdinaryTransferRaisesNothing() {
        assertThat(rules(150_000, 150_000, 1)).isEmpty();
    }

    @Test
    void aTransferExactlyAtTheLargeThresholdIsFlagged() {
        assertThat(rules(25_000_000, 25_000_000, 1)).containsExactly(AlertRule.LARGE_TRANSFER);
        assertThat(rules(24_999_999, 24_999_999, 1)).isEmpty();
    }

    @Test
    void theDailyTotalAlertFiresOnTheTransferThatCrossesIt() {
        // 90M before, +10M -> exactly 100M: crosses.
        assertThat(rules(10_000_000, 100_000_000, 5)).containsExactly(AlertRule.DAILY_TOTAL_EXCEEDED);
    }

    @Test
    void theDailyTotalAlertDoesNotRepeatOnceTheAccountIsAlreadyOver() {
        // 100M before, +1M -> 101M: was already over, no new alert.
        assertThat(rules(1_000_000, 101_000_000, 6)).isEmpty();
    }

    @Test
    void theDailyCountAlertFiresOnceOnTheTwentiethTransfer() {
        assertThat(rules(10_000, 190_000, 19)).isEmpty();
        assertThat(rules(10_000, 200_000, 20)).containsExactly(AlertRule.HIGH_DAILY_COUNT);
        assertThat(rules(10_000, 210_000, 21)).isEmpty();
    }

    @Test
    void oneTransferCanBreakSeveralRules() {
        // First transfer of the day is 100M: large, and it alone reaches the daily total.
        assertThat(rules(100_000_000, 100_000_000, 1))
                .containsExactly(AlertRule.LARGE_TRANSFER, AlertRule.DAILY_TOTAL_EXCEEDED);
    }

    @Test
    void daysAreCountedInJakartaTimeNotUtc() {
        ZoneId jakarta = ZoneId.of("Asia/Jakarta"); // UTC+7
        assertThat(RiskRules.businessDay(Instant.parse("2026-01-01T16:59:59Z"), jakarta))
                .isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(RiskRules.businessDay(Instant.parse("2026-01-01T17:00:00Z"), jakarta))
                .isEqualTo(LocalDate.parse("2026-01-02"));
    }
}
