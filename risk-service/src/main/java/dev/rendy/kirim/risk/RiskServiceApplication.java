package dev.rendy.kirim.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Kirim risk service: watches transfer events and raises AML-style alerts
 * (large transfers, daily totals, daily counts). It is a separate service
 * written in Java that shares nothing with the transfer API except the
 * Kafka topic and the event contract.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RiskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskServiceApplication.class, args);
    }
}
