package dev.rendy.kirim.config

import com.fasterxml.jackson.core.JsonProcessingException
import dev.rendy.kirim.outbox.OutboxProperties
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff

@Configuration
class KafkaConfig(private val props: OutboxProperties) {

    @Bean
    fun transferEventsTopic(): NewTopic = TopicBuilder.name(props.topic).partitions(3).replicas(1).build()

    /** Dead-letter topic: same partition count, because the recoverer keeps the original partition. */
    @Bean
    fun transferEventsDltTopic(): NewTopic = TopicBuilder.name("${props.topic}-dlt").partitions(3).replicas(1).build()

    /**
     * Consumer-side retries: back off 0.5s, 1s, 2s, 4s (≈ 7.5s in total), then park
     * the message on <topic>-dlt so one poison message can't block the partition.
     * Malformed JSON will never succeed, so it skips the retries.
     * Spring Boot plugs this bean into the default listener container factory.
     */
    @Bean
    fun kafkaErrorHandler(template: KafkaTemplate<*, *>): DefaultErrorHandler {
        val backOff = ExponentialBackOff(500L, 2.0).apply { maxElapsedTime = 8_000L }
        return DefaultErrorHandler(DeadLetterPublishingRecoverer(template), backOff).apply {
            addNotRetryableExceptions(JsonProcessingException::class.java)
        }
    }
}
