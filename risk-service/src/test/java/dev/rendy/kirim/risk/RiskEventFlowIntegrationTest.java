package dev.rendy.kirim.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rendy.kirim.risk.event.TransferEvent;
import dev.rendy.kirim.risk.rules.AlertRule;
import dev.rendy.kirim.risk.rules.DailyActivity;
import dev.rendy.kirim.risk.rules.RiskProperties;
import dev.rendy.kirim.risk.store.Alert;
import dev.rendy.kirim.risk.store.RiskRepository;
import dev.rendy.kirim.risk.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Through a real Kafka broker: event on transfer-events -> listener -> rules -> PostgreSQL.
 * Test thresholds (application-test.yml): large 2M, daily total 3M, daily count 5.
 */
class RiskEventFlowIntegrationTest extends IntegrationTest {

    private static final Instant NOON_JAKARTA = Instant.parse("2026-03-10T05:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-03-10");

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired ObjectMapper objectMapper;
    @Autowired RiskRepository repository;
    @Autowired RiskProperties props;
    @Autowired MockMvc mockMvc;

    private TransferEvent completed(UUID from, long amount, Instant at) {
        return new TransferEvent(UUID.randomUUID(), "TRANSFER_COMPLETED", UUID.randomUUID(), from, UUID.randomUUID(),
                amount, "IDR", "COMPLETED", null, at);
    }

    /** Keyed by sender, like the real producer, so one account's events stay in order on one partition. */
    private void send(TransferEvent event) throws Exception {
        sendRaw(event.fromAccountId().toString(), objectMapper.writeValueAsString(event));
    }

    private void sendRaw(String key, String value) throws Exception {
        kafka.send(props.topic(), key, value).get(10, TimeUnit.SECONDS);
    }

    private DailyActivity awaitDailyCount(UUID account, LocalDate day, int count) {
        await().atMost(Duration.ofSeconds(30)).until(() ->
                repository.findDaily(account, day).map(DailyActivity::transferCount).orElse(0) == count);
        return repository.findDaily(account, day).orElseThrow();
    }

    @Test
    void aLargeTransferRaisesAnAlertAndIsAddedToTheDailyTotal() throws Exception {
        UUID alice = UUID.randomUUID();
        TransferEvent event = completed(alice, 2_500_000, NOON_JAKARTA);

        send(event);

        assertThat(awaitDailyCount(alice, DAY, 1)).isEqualTo(new DailyActivity(2_500_000, 1));
        List<Alert> alerts = repository.findAlertsByAccount(alice);
        assertThat(alerts).extracting(Alert::rule).containsExactly(AlertRule.LARGE_TRANSFER);
        assertThat(alerts.get(0).transferId()).isEqualTo(event.transferId());
    }

    @Test
    void aRedeliveredEventIsCountedOnce() throws Exception {
        UUID alice = UUID.randomUUID();
        TransferEvent event = completed(alice, 100_000, NOON_JAKARTA);

        send(event);
        send(event); // Kafka is at-least-once: the same event can arrive twice
        send(completed(alice, 50_000, NOON_JAKARTA));

        // Same key, same partition, so once the third event is in, the duplicate has been seen.
        assertThat(awaitDailyCount(alice, DAY, 2)).isEqualTo(new DailyActivity(150_000, 2));
    }

    @Test
    void theDailyTotalAlertIsRaisedOnceWhenTheThresholdIsCrossed() throws Exception {
        UUID alice = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            send(completed(alice, 1_000_000, NOON_JAKARTA)); // 1M, 2M, 3M (crosses 3M), 4M
        }

        awaitDailyCount(alice, DAY, 4);
        assertThat(repository.findAlertsByAccount(alice)).extracting(Alert::rule)
                .containsExactly(AlertRule.DAILY_TOTAL_EXCEEDED);
    }

    @Test
    void rejectedTransfersAreIgnored() throws Exception {
        UUID alice = UUID.randomUUID();
        send(new TransferEvent(UUID.randomUUID(), "TRANSFER_REJECTED", UUID.randomUUID(), alice, UUID.randomUUID(),
                9_000_000, "IDR", "REJECTED", "INSUFFICIENT_FUNDS", NOON_JAKARTA));
        send(completed(alice, 10_000, NOON_JAKARTA));

        assertThat(awaitDailyCount(alice, DAY, 1)).isEqualTo(new DailyActivity(10_000, 1));
        assertThat(repository.findAlertsByAccount(alice)).isEmpty();
    }

    @Test
    void transfersAreBucketedByJakartaBusinessDayAndExposedOverHttp() throws Exception {
        UUID alice = UUID.randomUUID();
        // 18:00 UTC on 1 Jan is 01:00 on 2 Jan in Jakarta.
        send(completed(alice, 75_000, Instant.parse("2026-01-01T18:00:00Z")));

        awaitDailyCount(alice, LocalDate.parse("2026-01-02"), 1);
        mockMvc.perform(get("/api/v1/accounts/{id}/daily-activity", alice).param("day", "2026-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(75_000))
                .andExpect(jsonPath("$.transferCount").value(1));
        mockMvc.perform(get("/api/v1/accounts/{id}/daily-activity", alice).param("day", "2026-01-01"))
                .andExpect(jsonPath("$.transferCount").value(0));
    }

    @Test
    void aMalformedMessageGoesToTheDeadLetterTopicWithoutBlockingThePartition() throws Exception {
        UUID alice = UUID.randomUUID();
        String poison = "{not valid json " + UUID.randomUUID();

        sendRaw(alice.toString(), poison);
        send(completed(alice, 20_000, NOON_JAKARTA)); // same partition, right behind the bad message

        awaitDailyCount(alice, DAY, 1);
        try (Consumer<String, String> dlt = consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null)) {
            dlt.subscribe(List.of(props.deadLetterTopic()));
            List<String> seen = new ArrayList<>();
            // KafkaConsumer is single-threaded: poll it on this thread, not on Awaitility's.
            await().pollInSameThread().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> record : dlt.poll(Duration.ofMillis(500))) {
                    seen.add(record.value());
                }
                assertThat(seen).contains(poison);
            });
        }
    }
}
