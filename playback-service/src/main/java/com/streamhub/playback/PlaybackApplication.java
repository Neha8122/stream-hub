package com.streamhub.playback;

import com.streamhub.events.PlaybackEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@SpringBootApplication
public class PlaybackApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlaybackApplication.class, args);
    }

    /** 6 partitions: up to 6 consumers can share the work; users are spread across them. */
    @Bean
    NewTopic playbackEvents() {
        return TopicBuilder.name(PlaybackEvent.TOPIC).partitions(6).replicas(1).build();
    }
}
