package com.streamhub.users;

/** Public view of an account: never includes the password hash. */
public record User(long id, String email, String displayName) {
}
