package dev.rendy.kirim.risk.rules;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("risk")
public record RiskProperties(
        @DefaultValue("transfer-events") String topic,
        @DefaultValue("Asia/Jakarta") String zone,
        @DefaultValue("25000000") long largeTransferAmount,
        @DefaultValue("100000000") long dailyTotalAmount,
        @DefaultValue("20") int dailyTransferCount) {

    public String deadLetterTopic() {
        return topic + "-risk-dlt";
    }
}
