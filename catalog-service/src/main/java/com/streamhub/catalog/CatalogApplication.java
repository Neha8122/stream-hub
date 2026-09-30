package com.streamhub.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CatalogApplication {

    /** Loaded once (~1 s); thread-safe. */
    @Bean Embedder embedder() {
        return new Embedder.MiniLm();
    }

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
