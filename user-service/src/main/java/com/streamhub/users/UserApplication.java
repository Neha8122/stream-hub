package com.streamhub.users;

import com.streamhub.auth.Jwt;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }

    @Bean
    Jwt jwt(@Value("${auth.jwt-secret}") String secret, @Value("${auth.token-lifetime}") Duration lifetime) {
        return new Jwt(secret, lifetime, Clock.systemUTC());
    }

    /** Cost 10: ~50-100 ms per hash, slow for an attacker, fine for a login. */
    @Bean
    BCryptPasswordEncoder passwords() {
        return new BCryptPasswordEncoder(10);
    }
}
