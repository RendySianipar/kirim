package dev.rendy.kirim.risk.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.rendy.kirim.risk.event.InvalidEventException;
import dev.rendy.kirim.risk.rules.RiskProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    private final RiskProperties props;

    public KafkaConfig(RiskProperties props) {
        this.props = props;
    }

    /** Declared here too so the service can start before the transfer service has created it. Same settings. */
    @Bean
    public NewTopic transferEventsTopic() {
        return TopicBuilder.name(props.topic()).partitions(3).replicas(1).build();
    }

    /**
     * This service's own dead-letter topic. Each consumer group gets its own, so a
     * message that fails here is not mixed up with failures in the notification
     * consumer. Same partition count as the source, because the recoverer keeps
     * the original partition.
     */
    @Bean
    public NewTopic riskDeadLetterTopic() {
        return TopicBuilder.name(props.deadLetterTopic()).partitions(3).replicas(1).build();
    }

    /**
     * Retry 0.5s, 1s, 2s, 4s, then park the message on the dead-letter topic so one
     * bad message cannot block its partition. Messages that can never succeed
     * (malformed JSON, missing fields) skip the retries.
     * Spring Boot plugs this bean into the default listener container factory.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(props.deadLetterTopic(), record.partition()));
        var backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxElapsedTime(8_000L);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JsonProcessingException.class, InvalidEventException.class);
        return handler;
    }
}
