package com.streamhub.gateway;

import com.streamhub.auth.Jwt;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    Jwt jwt(@Value("${auth.jwt-secret}") String secret) {
        // The gateway only verifies; the lifetime is the user service's business.
        return new Jwt(secret, Duration.ofHours(1), Clock.systemUTC());
    }

    /** Everyday limit, per logged-in user: replenish/s, burst, tokens per request. */
    @Bean
    @Primary
    RedisRateLimiter perUser(@Value("${limits.per-user-per-second}") int perSecond) {
        return new RedisRateLimiter(perSecond, perSecond * 2, 1);
    }

    /** Much stricter, per IP address, for login: slows down password guessing. */
    @Bean
    RedisRateLimiter loginLimiter(@Value("${limits.login-per-ip-per-second}") int perSecond) {
        return new RedisRateLimiter(perSecond, perSecond * 2, 1);
    }

    /** The logged-in user if the JWT filter found one, else the caller's IP. */
    @Bean
    @Primary
    KeyResolver userOrIp() {
        return exchange -> Mono.just(Optional.ofNullable((Long) exchange.getAttribute(JwtFilter.USER_ID))
                .map(id -> "user:" + id)
                .orElseGet(() -> "ip:" + ip(exchange)));
    }

    @Bean
    KeyResolver byIp() {
        return exchange -> Mono.just("ip:" + ip(exchange));
    }

    private static String ip(org.springframework.web.server.ServerWebExchange exchange) {
        var address = exchange.getRequest().getRemoteAddress();
        return address == null ? "unknown" : address.getAddress().getHostAddress();
    }

    @Bean
    RouteLocator routes(RouteLocatorBuilder b,
                        @Value("${routes.catalog}") String catalog,
                        @Value("${routes.users}") String users,
                        @Value("${routes.playback}") String playback,
                        @Value("${routes.history}") String history,
                        @Value("${routes.home}") String home,
                        @Value("${routes.recs}") String recs,
                        @Value("${routes.assistant}") String assistant,
                        @Value("${routes.assistant-timeout-ms}") int assistantTimeoutMs,
                        @Value("${routes.timeout-ms}") int timeoutMs,
                        // Explicit qualifiers: @Primary would otherwise win over the
                        // parameter names, and login would silently get the lax limiter.
                        @Qualifier("perUser") RedisRateLimiter perUser,
                        @Qualifier("loginLimiter") RedisRateLimiter loginLimiter,
                        @Qualifier("userOrIp") KeyResolver userOrIp,
                        @Qualifier("byIp") KeyResolver byIp) {
        return b.routes()
                .route("login", r -> r.path("/auth/login", "/users/register")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(loginLimiter).setKeyResolver(byIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeoutMs)
                        .uri(users))
                .route("users", r -> r.path("/users/**")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(perUser).setKeyResolver(userOrIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeoutMs)
                        .uri(users))
                // Service-to-service only (recs uses them): raw vectors and
                // unbounded nearest-neighbour queries aren't for the public.
                // Listed before "catalog" so it matches first.
                .route("internal-only", r -> r.path("/titles/nearest", "/titles/*/embedding")
                        .filters(f -> f.setStatus(404))
                        .uri("no://op"))
                .route("catalog", r -> r.path("/titles/**")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(perUser).setKeyResolver(userOrIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeoutMs)
                        .uri(catalog))
                .route("playback", r -> r.path("/playback/**")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(perUser).setKeyResolver(userOrIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeoutMs)
                        .uri(playback))
                .route("search", r -> r.path("/search")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(perUser).setKeyResolver(userOrIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeoutMs)
                        .uri(catalog))
                .route("recs", r -> r.path("/recs/**")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(perUser).setKeyResolver(userOrIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeoutMs)
                        .uri(recs))
                // An LLM answer takes seconds, not milliseconds: its own, longer timeout.
                .route("assistant", r -> r.path("/assistant/**")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(perUser).setKeyResolver(userOrIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, assistantTimeoutMs)
                        .uri(assistant))
                .route("home", r -> r.path("/home")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(perUser).setKeyResolver(userOrIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeoutMs)
                        .uri(home))
                .route("history", r -> r.path("/history/**")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(perUser).setKeyResolver(userOrIp)))
                        .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeoutMs)
                        .uri(history))
                .build();
    }
}
