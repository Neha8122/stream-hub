package com.streamhub.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Signs and checks access tokens (HS256 with a shared secret). The user
 * service issues them at login; the gateway checks them on every request,
 * so no service behind it has to.
 *
 * <p>A shared secret is the simple choice for one team's system. With
 * services owned by different teams you'd switch to RS256: only the user
 * service holds the private key, everyone else verifies with the public key.
 */
public final class Jwt {

    public static final String ISSUER = "stream-hub";

    /** Who the token belongs to. */
    public record Claims(long userId, String email, Instant expiresAt) { }

    private final byte[] secret;
    private final Duration lifetime;
    private final Clock clock;

    public Jwt(String secret, Duration lifetime, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes for HS256");
        }
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public String issue(long userId, String email) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(Long.toString(userId))
                .claim("email", email)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The claims if the token is well-formed, signed with our secret, issued
     * by us and not expired; empty otherwise. Never throws for bad input.
     */
    public Optional<Claims> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                return Optional.empty();        // refuse "alg: none" and algorithm swaps
            }
            if (!jwt.verify(new MACVerifier(secret))) {
                return Optional.empty();
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Date exp = c.getExpirationTime();
            if (!ISSUER.equals(c.getIssuer()) || exp == null || !exp.toInstant().isAfter(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(new Claims(Long.parseLong(c.getSubject()), c.getStringClaim("email"), exp.toInstant()));
        } catch (ParseException | JOSEException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Duration lifetime() {
        return lifetime;
    }
}
