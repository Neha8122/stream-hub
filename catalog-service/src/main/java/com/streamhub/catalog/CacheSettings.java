package com.streamhub.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param freshFor   served as-is until then (plus or minus jitter)
 * @param keepFor    Redis TTL: a stale copy stays usable this long
 * @param missingFor how long "no such title" is remembered
 */
@ConfigurationProperties("catalog.cache")
public record CacheSettings(Duration freshFor, Duration keepFor, Duration missingFor) {
}
