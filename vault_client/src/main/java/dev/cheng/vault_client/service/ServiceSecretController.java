package dev.cheng.vault_client.service;

import dev.cheng.vaultstarter.KvSecret;
import dev.cheng.vaultstarter.VaultSecrets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo visibility into the loaded version; never returns the password. */
@RestController
@RequestMapping("/api/demo-service")
public class ServiceSecretController {
    private final KvSecret password;

    public ServiceSecretController(VaultSecrets secrets) {
        this.password = secrets.kv("demo-service");
    }

    @GetMapping("/secret-status")
    public Status status() {
        return new Status(password.get().version());
    }

    public record Status(int version) {}
}
