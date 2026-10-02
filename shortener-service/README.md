# URL Shortener Service

Generated and maintained by the agentic SDLC orchestrator.

## Endpoints

- `POST /api/v1/urls` - Create a short URL
- `DELETE /api/v1/urls/{code}` - Soft-delete (requires X-API-Key)
- `GET /api/v1/urls/{code}` - Get link metadata
- `GET /api/v1/urls/{code}/stats` - Click statistics
- `GET /{code}` - Redirect

## Requirements covered

- **FR-1** Create short URLs: POST /api/v1/urls returns 201 with a unique code; same long URL returns the existing code (200)
- **FR-2** Redirect: GET /{code} returns 302 with Location; 404 if unknown; 410 if deleted or expired
- **FR-3** Custom aliases: Alias 3-32 chars [A-Za-z0-9_-], not reserved, unique; 409 when taken
- **FR-4** Click analytics: GET /api/v1/urls/{code}/stats returns total clicks and last access time
- **FR-5** Soft delete: DELETE /api/v1/urls/{code} requires admin key, marks link deleted (redirect then 410)
- **FR-6** Rate limiting: Per-client token bucket on write APIs; 429 with Retry-After when exhausted
- **FR-7** URL safety validation: Only http/https; private, loopback and link-local hosts rejected (SSRF)

## Run

```bash
mvn spring-boot:run
# create
curl -s -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' -d '{"longUrl":"https://example.com"}'
```

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `shortener.base-url` | http://localhost:8080 | Prefix used in returned short URLs |
| `shortener.admin-key` | (unset) | Required `X-API-Key` for DELETE; delete disabled when unset |
| `shortener.ratelimit.capacity` | 20 | Token bucket size per client address |
| `shortener.ratelimit.refill-per-second` | 1 | Refill rate |

See `docs/design/` for architecture, OpenAPI, risk register and (brownfield) impact analysis.
