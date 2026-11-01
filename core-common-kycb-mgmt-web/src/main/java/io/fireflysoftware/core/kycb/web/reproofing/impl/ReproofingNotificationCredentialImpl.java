package io.fireflysoftware.core.kycb.web.reproofing.impl;

import org.springframework.core.env.Environment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;

final class ReproofingNotificationCredentialImpl {
    final String identity, secret;
    private ReproofingNotificationCredentialImpl(String identity, String secret) {
        if (identity == null || identity.isBlank() || identity.length() > 128 || identity.contains(":")
                || identity.chars().anyMatch(Character::isISOControl))
            throw new IllegalStateException("Notification workload configuration unavailable");
        this.identity = identity;
        this.secret = secret;
    }
    static ReproofingNotificationCredentialImpl read(Environment env, String prefix, boolean privateFile) {
        String identity = env.getRequiredProperty(prefix + "client-id");
        String file = env.getRequiredProperty(prefix + "secret-file");
        return new ReproofingNotificationCredentialImpl(identity,
                privateFile ? readPrivateSecret(file) : ReproofingRemoteClientImpl.readSecret(file));
    }
    static ReproofingNotificationCredentialImpl[] validated(Environment env) {
        var notification = read(env, "firefly.reproofing.notifications.", true);
        var monitor = read(env, "firefly.reproofing.notifications.monitor.", true);
        var credentials = new ArrayList<ReproofingNotificationCredentialImpl>();
        credentials.add(notification); credentials.add(monitor);
        credentials.add(read(env, "firefly.reproofing.receiver.", false));
        credentials.add(read(env, "firefly.reproofing.native.", false));
        if (env.containsProperty("firefly.reproofing.evidence.client-id"))
            credentials.add(read(env, "firefly.reproofing.evidence.", false));
        for (int i = 0; i < credentials.size(); i++)
            for (int j = i + 1; j < credentials.size(); j++) {
                var first = credentials.get(i); var second = credentials.get(j);
                if (first.identity.equals(second.identity) || MessageDigest.isEqual(
                        first.secret.getBytes(StandardCharsets.UTF_8), second.secret.getBytes(StandardCharsets.UTF_8)))
                    throw new IllegalStateException("Notification workload credentials must be independent");
            }
        return new ReproofingNotificationCredentialImpl[] {notification, monitor};
    }
    private static String readPrivateSecret(String file) {
        try {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
                throw new IllegalStateException();
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(p -> p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE))
                throw new IllegalStateException();
            ByteBuffer buffer = ByteBuffer.allocate(4097);
            try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
            }
            if (buffer.position() > 4096) throw new IllegalStateException();
            buffer.flip();
            String value = StandardCharsets.UTF_8.newDecoder().decode(buffer).toString().strip();
            if (value.length() < 32 || value.length() > 2048 || value.chars().anyMatch(Character::isISOControl))
                throw new IllegalStateException();
            return value;
        } catch (Exception error) {
            throw new IllegalStateException("Notification workload credential unavailable");
        }
    }
}
