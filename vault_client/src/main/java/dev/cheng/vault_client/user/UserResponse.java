package dev.cheng.vault_client.user;

import java.time.Instant;

public record UserResponse(Long id, String name, String email, Instant createdAt) {

    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }
}
