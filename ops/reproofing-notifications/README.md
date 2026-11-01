# Recovery notification worker

Core owns this optional worker; Native owns the durable queue, encrypted prior destination, leases, send operation and eight-attempt budget. Delivery remains at least once. Processing a row is not proof of delivery. Restarting Core reobserves the original queue and does not reset attempts, terminal failures, recovery cases or grants.

## Activation

The global `firefly.reproofing.enabled=true` and `firefly.reproofing.notifications.enabled=true` must both be explicit. The worker is off by default: no scheduler, notification HTTP client or worker metrics are created. Its private path is denied while disabled. Disabling the global flag also closes this path and suppresses the worker at startup; configuration changes require the normal application restart.

Create a separate confidential, grant-disabled Native workload client and set realm attribute `firefly.reproofing.notification-workload-client-id` to that identity. This client may call only `dispatch` and `notification-status`. The poller uses the existing exact `firefly.reproofing.native.base-url`, ending in `/firefly-recovery/lost-credentials/rpc`, with trusted HTTPS and no redirects. Every dispatch body is `{"limit":1}`; status body is `{}`. Ceremony callers retain their original five-second HTTP timeout.

Configure these properties (durations are ISO 8601):

| Property under `firefly.reproofing.notifications` | Default / requirement |
| --- | --- |
| `enabled` | `false` |
| `client-id` / `secret-file` | Dedicated native notification identity and mounted secret |
| `monitor.client-id` / `monitor.secret-file` | Independent private GET monitor identity and mounted secret |
| `poll-interval` | `PT30S`, range 1..300 seconds |
| `request-timeout` | `PT30S`, range 1..60 seconds; bounds the whole dispatch/status cycle and each HTTP transport |
| `stale-after` | `PT90S`, greater than interval plus timeout, at most 3600 seconds |
| `overdue-after` | `PT2M`, range 1..3600 seconds |

Both new secret files must be regular nonsymlink POSIX files with owner-only read/write permissions, containing 32..2048 characters. Mount them privately (for example mode 0600); never put values in arguments or logs. New identities and secret values must differ from one another and from receiver/native ceremony credentials (and evidence credentials when configured). Missing, reused or unsafe files fail startup with generic diagnostics. Certificate and hostname validation remain enabled. Each response is limited to 32 KiB and exact JSON fields; invalid counts, duplicate/unknown/missing fields and untrusted timestamps cannot mark a cycle successful.

The managed lifecycle schedules immediately, then waits the configured delay after each cycle finishes. A single local gate prevents overlapping cycles; Native leases fence multiple Core instances and ambiguous responses. Shutdown cancels the scheduled future and active subscription. No global scheduling or readiness policy changes.

## Private supervision

Authenticated HTTPS GET `/internal/identity-recovery/notifications` accepts only the dedicated monitor Basic credential. All other methods and subpaths are denied. The case receiver, notification caller and ordinary users cannot read this endpoint; the monitor credential cannot call case operations. Do not expose it to browser ingress. It is hidden from the original producer OpenAPI and adds no SDK operation.

The immutable response has `state`, `activeCycle`, `lastAttemptAt`, `lastSuccessAt`, `consecutiveFailures` and `queue`. The timestamps and queue are null before the first attempt/success as applicable. Queue fields are exactly `observedAt`, `pending`, `sending`, `retrying`, `delivered`, `failed`, `invalid`, `due`, `oldestDueDelaySeconds`. No subject, destination, case/execution identifier, ciphertext, provider content, credential or exception is returned. Queue observations use Native database time and realm scope. Core rejects observations more than 30 seconds ahead or 90 seconds behind its clock.

`STARTING` means no successful observation, `UNAVAILABLE` means the last cycle failed, `STALE` means the last successful observation aged past the configured limit, and `DEGRADED` means failed/invalid/retrying rows or excessive due delay. Otherwise the state is `HEALTHY`. Staleness is computed when read, so a stopped scheduler cannot keep a cached successful result healthy. Cached queue data remains explicitly associated with its original `observedAt` and `lastSuccessAt`.

## Metrics and alerts

The existing Micrometer registry records `firefly.reproofing.notifications` metrics: `cycles{outcome=success|failure}`, `failures{category=timeout|invalid_response|dependency}`, `processed`, `active`, `consecutive_failures`, `last_success_epoch`, `stale`, `observed`, and `queue{kind=pending|sending|retrying|delivered|failed|invalid|due|oldest_due_delay_seconds}`. Labels are fixed constants. Missing initial queue values are NaN, never a fabricated empty queue. Counter names gain `_total` and dots become underscores in Prometheus. Counts are observations, not delivery commitments.

Install [alerts.yml](alerts.yml) in the independently owned monitoring system, selecting the expected Core job. Adjust 90/120-second alert thresholds if changing the corresponding configuration. Preserve an external absence alert when the worker is expected: a disabled/stopped runtime cannot emit its own missing-worker alarm. Scrape only through the existing approved private monitoring network/authentication; this change does not expose global Prometheus or change application readiness.

For unavailable/stale polling, inspect workload-client confinement, mounted-file permissions, issuer URL, trusted certificate chain, host connectivity, timeout configuration and clock synchronization. Do not enable SQL bind-value or HTTP body logging. Recover connectivity and allow the next bounded cycle to reobserve durable state. For retry/overdue work, check approved SMTP reachability and provider monitoring, retaining bounded connect/read/write timeouts (Keycloak 26.8.1 defaults are each 10,000 ms). For failed/invalid rows, preserve the original row, attempts, lease/failure evidence and escalate to the named recovery operations owner. There is no automatic terminal-row replay/delete or destination replacement.

Before normal disabling, quiesce new browser recovery ingress, drain or review pending notifications, then disable/restart the runtime and review the external absence expectation. Emergency disabling preserves Native queue state and requires follow-up. No queue reset, new recovery or financial grant is part of worker operations. Real SMTP/provider approval and production operating acceptance remain separate gates.
