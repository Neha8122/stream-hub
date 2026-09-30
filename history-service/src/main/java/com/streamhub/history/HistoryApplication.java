package com.streamhub.history;

import com.streamhub.events.PlaybackEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

@SpringBootApplication
public class HistoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(HistoryApplication.class, args);
    }

    /** Declared here too, so either service can start first; same settings as playback's. */
    @Bean
    NewTopic playbackEvents() {
        return TopicBuilder.name(PlaybackEvent.TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic deadLetters() {
        return TopicBuilder.name(PlaybackEvent.DEAD_LETTERS).partitions(6).replicas(1).build();
    }

    /**
     * A failing event is retried 3 times, 200 ms apart, then parked on the
     * dead-letter topic so the rest of its partition keeps flowing. Messages
     * that aren't even valid JSON go straight there: retrying can't fix them.
     */
    @Bean
    DefaultErrorHandler errorHandler(ProducerFactory<Object, Object> producers) {
        // Built from Spring's producer factory so they use the same Kafka as
        // everything else (in tests: the container's address, not the default).
        KafkaTemplate<Object, Object> bytes = template(producers, ByteArraySerializer.class);
        KafkaTemplate<Object, Object> json = template(producers, JsonSerializer.class);
        Map<Class<?>, KafkaOperations<?, ?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, bytes);        // raw bytes of a message we couldn't parse
        byType.put(Object.class, json);         // a parsed event that kept failing
        // Name the topic explicitly: the default is "<topic>-dlt", and publishing
        // to a topic that doesn't exist fails, which leaves the poison message
        // blocking its whole partition. Same partition number keeps order.
        DeadLetterPublishingRecoverer toDlt = new DeadLetterPublishingRecoverer(byType,
                (record, ex) -> new TopicPartition(PlaybackEvent.DEAD_LETTERS, record.partition()));
        return new DefaultErrorHandler(toDlt, new FixedBackOff(200, 3));
    }

    private static KafkaTemplate<Object, Object> template(ProducerFactory<Object, Object> producers,
                                                          Class<?> valueSerializer) {
        return new KafkaTemplate<>(producers, Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer,
                "spring.json.add.type.headers", false));
    }
}
