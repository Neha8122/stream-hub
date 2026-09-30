package com.streamhub.users;

import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    record Stored(long id, String email, String passwordHash, String displayName) {
        User toUser() {
            return new User(id, email, displayName);
        }
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty if the email is already taken (the unique index decides, so there's no race). */
    public Optional<User> insert(String email, String passwordHash, String displayName) {
        try {
            long id = jdbc.sql("INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?) RETURNING id")
                    .params(email, passwordHash, displayName).query(Long.class).single();
            return Optional.of(new User(id, email, displayName));
        } catch (DuplicateKeyException taken) {
            return Optional.empty();
        }
    }

    public Optional<Stored> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, password_hash, display_name FROM users WHERE lower(email) = lower(?)")
                .param(email).query(Stored.class).optional();
    }

    public Optional<User> findById(long id) {
        return jdbc.sql("SELECT id, email, display_name FROM users WHERE id = ?")
                .param(id).query(User.class).optional();
    }
}
