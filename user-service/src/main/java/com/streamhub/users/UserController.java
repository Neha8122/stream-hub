package com.streamhub.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * /users/me trusts the X-User-Id header: only the gateway sets it, after
 * checking the token, and it strips any copy a client sends. In a real
 * deployment this service is reachable only from the gateway (network
 * policy or mTLS), never directly from the internet.
 */
@RestController
public class UserController {

    public record RegisterRequest(@NotBlank @Email String email,
                                  @NotBlank @Size(min = 8, max = 128) String password,
                                  @NotBlank @Size(max = 80) String displayName) { }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) { }

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @PostMapping("/users/register")
    public ResponseEntity<User> register(@Valid @RequestBody RegisterRequest r) {
        return users.register(r.email(), r.password(), r.displayName())
                .map(u -> ResponseEntity.status(HttpStatus.CREATED).body(u))
                .orElse(ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @PostMapping("/auth/login")
    public ResponseEntity<UserService.Login> login(@Valid @RequestBody LoginRequest r) {
        // Same 401 whether the email is unknown or the password is wrong.
        return users.login(r.email(), r.password())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @GetMapping("/users/me")
    public ResponseEntity<User> me(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.of(users.byId(userId));
    }
}
