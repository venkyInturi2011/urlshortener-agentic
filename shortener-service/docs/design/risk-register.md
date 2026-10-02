# Risk register

| ID | Failure scenario | Mitigation | Validation |
|---|---|---|---|
| R-CODE | Code collision or exhaustion under load | Random 62^7 space, bounded retry (5), fail closed with 503 | UrlServiceTest collision/exhaustion cases |
| R-OPENREDIR | Short domain abused for phishing (open redirect by design) | Rate limit creation; optional host denylist; soft delete for takedown | Integration tests for 410 after delete |
| R-CLICK | Synchronous click write adds latency and grows unbounded | Failure to record never blocks redirect; retention/batching listed as follow-up | Integration test: stats after redirect |
| R-AUTHZ | Unauthenticated delete would allow link takedown by anyone | Admin API key, constant-time compare, disabled when key unset | Integration: 403 without key |
| R-RATE | Per-instance limiter is bypassed by scaling out; X-Forwarded-For not trusted | Documented limitation; move to shared store (Redis) for multi-instance | RateLimitIntegrationTest (429) |
| R-SSRF | Short link points at internal service (SSRF) or uses numeric-IP obfuscation | Scheme allow-list; reject credentials, loopback/private/link-local, decimal/hex/short IPs | UrlValidatorTest parameterised cases |
| R-AMB-1 | Unresolved ambiguity 'safer' (status OPEN) | Proceed on assumption: Block known-bad destination domains (denylist) and keep http/https; rate limiting stays on | Human approval of assumptions at design review |
