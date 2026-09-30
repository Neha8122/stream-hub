package com.streamhub.gateway;

import com.streamhub.auth.Jwt;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Runs before routing on every request. Checks the bearer token and tells
 * the services behind it who the user is, in {@code X-User-Id}.
 *
 * <p>Any {@code X-User-Id} the client sent is removed first, always:
 * otherwise anyone could claim to be user 1 by setting a header.
 */
@Component
public class JwtFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "streamhub.userId";
    public static final String USER_HEADER = "X-User-Id";

    /** Reachable without a token. */
    private static final Set<String> PUBLIC_POSTS = Set.of("/auth/login", "/users/register");

    private final Jwt jwt;

    public JwtFilter(Jwt jwt) {
        this.jwt = jwt;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.remove(USER_HEADER))
                .build();
        String path = request.getPath().value();

        if (isPublic(request.getMethod(), path)) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        Optional<Jwt.Claims> claims = bearer(request).flatMap(jwt::verify);
        if (claims.isEmpty()) {
            return unauthorized(exchange);
        }
        long userId = claims.get().userId();
        exchange.getAttributes().put(USER_ID, userId);
        ServerHttpRequest withUser = request.mutate()
                .headers(h -> h.set(USER_HEADER, Long.toString(userId)))
                .build();
        return chain.filter(exchange.mutate().request(withUser).build());
    }

    private static boolean isPublic(HttpMethod method, String path) {
        return path.startsWith("/actuator/") || (HttpMethod.POST.equals(method) && PUBLIC_POSTS.contains(path));
    }

    private static Optional<String> bearer(ServerHttpRequest request) {
        String h = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return h != null && h.startsWith("Bearer ") ? Optional.of(h.substring(7).trim()) : Optional.empty();
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        DataBuffer body = response.bufferFactory()
                .wrap("{\"error\":\"missing or invalid token\"}".getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(body));
    }

    /** Before routing and before the rate limiter, which keys on the user. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
