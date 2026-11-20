package dev.rendy.kirim.risk.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rendy.kirim.risk.RiskService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Its own consumer group (kirim-risk), so it receives every transfer event
 * independently of the notification consumer in the transfer service.
 */
@Component
public class TransferEventListener {

    private final ObjectMapper objectMapper;
    private final RiskService riskService;

    public TransferEventListener(ObjectMapper objectMapper, RiskService riskService) {
        this.objectMapper = objectMapper;
        this.riskService = riskService;
    }

    @KafkaListener(topics = "${risk.topic:transfer-events}", groupId = "kirim-risk")
    public void onTransferEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        // Parsed outside the transaction: a malformed message fails before any DB work.
        TransferEvent event = objectMapper.readValue(record.value(), TransferEvent.class).requireValid();
        riskService.handle(event);
    }
}
