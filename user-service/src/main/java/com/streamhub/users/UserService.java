package com.streamhub.users;

import com.streamhub.auth.Jwt;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    /** Result of a login: the token and how long it's valid. */
    public record Login(String token, long expiresInSeconds) { }

    private final UserRepository users;
    private final BCryptPasswordEncoder passwords;
    private final Jwt jwt;
    // Checked when the email is unknown, so that case takes as long as a
    // wrong password: response time mustn't reveal which emails have accounts.
    private final String dummyHash;

    public UserService(UserRepository users, BCryptPasswordEncoder passwords, Jwt jwt) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    public Optional<User> register(String email, String password, String displayName) {
        return users.insert(email.trim(), passwords.encode(password), displayName.trim());
    }

    /** Empty for an unknown email and for a wrong password alike. */
    public Optional<Login> login(String email, String password) {
        Optional<UserRepository.Stored> stored = users.findByEmail(email.trim());
        boolean ok = passwords.matches(password, stored.map(UserRepository.Stored::passwordHash).orElse(dummyHash));
        if (!ok || stored.isEmpty()) {
            return Optional.empty();
        }
        UserRepository.Stored u = stored.get();
        return Optional.of(new Login(jwt.issue(u.id(), u.email()), jwt.lifetime().toSeconds()));
    }

    public Optional<User> byId(long id) {
        return users.findById(id);
    }
}
