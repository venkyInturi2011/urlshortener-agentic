# URL Shortener – API specification

Machine-readable contract: [`openapi.yaml`](openapi.yaml) (OpenAPI 3.0.3). This page is the human-readable
reference. Everything below was checked against a running instance of the final generated service (v1.2.0,
`workspace/demo/v3`, started with `SHORTENER_ADMIN_KEY=demo-admin-key mvn spring-boot:run`).

* **Base URL:** `http://localhost:8080` (`shortener.base-url` controls the host used in returned `shortUrl`s)
* **Format:** JSON (`application/json`) for success, RFC 7807 `application/problem+json` for errors
* **Auth:** none, except `DELETE` which needs `X-API-Key`
* **Time:** ISO-8601 UTC instants

## Endpoints

| Method | Path | Purpose | Success | Errors |
|---|---|---|---|---|
| `POST` | `/api/v1/urls` | Create a short URL | 201 (or 200 if reused) | 400, 409, 429, 503 |
| `GET` | `/api/v1/urls/{code}` | Link metadata | 200 | 404, 410 |
| `GET` | `/api/v1/urls/{code}/stats` | Click statistics | 200 | 404, 410 |
| `DELETE` | `/api/v1/urls/{code}` | Soft-delete (admin) | 204 | 403, 404 |
| `GET` | `/{code}` | Redirect | 302 | 404, 410 |
| `GET` | `/actuator/health` | Health | 200 | – |

### POST /api/v1/urls

Request body:

| Field | Type | Required | Rules |
|---|---|---|---|
| `longUrl` | string | yes | ≤ 2048 chars; absolute **https** URL (v1.2.0 default – `http` is rejected); public host; no credentials |
| `customAlias` | string | no | 3–32 chars `[A-Za-z0-9_-]`; not reserved (`api, actuator, health, healthz, admin, static, favicon, error, docs`, any letter case); must not differ from an existing code only by case |
| `expiresAt` | date-time | no | in the future, ≤ 5 years ahead |

```bash
curl -i -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/spec","customAlias":"spec-demo","expiresAt":"2031-01-01T00:00:00Z"}'
```
```
HTTP/1.1 201
Location: /api/v1/urls/spec-demo
{"code":"spec-demo","shortUrl":"http://localhost:8080/spec-demo","longUrl":"https://example.com/spec",
 "createdAt":"2026-10-02T21:30:20.519955300Z","expiresAt":"2031-01-01T00:00:00Z"}
```

Behaviour:

* No alias → a random 7-character base62 code. Allocation retries up to 5 times on collision, then **503**.
* No alias **and** no `expiresAt` → if a non-expiring generated link for the same `longUrl` exists, it is returned with
  **200** (observed: first call 201, second 200). With `expiresAt`, a new link is always created.
* Destination rejected with **400** when: not `https`; malformed; contains `user:pass@`; host is `localhost`,
  `*.localhost`, `*.local`, `*.internal` or single-label; IP literal in loopback / private / link-local (incl.
  `169.254.169.254`) / CGNAT / multicast / unique-local ranges, or IPv4-mapped IPv6; numeric/hex/short IP forms
  (`2130706433`, `0x7f.0.0.1`, `127.1`); domain on the denylist or a subdomain of one; IDN hosts (send punycode).
* Rate limit: token bucket per client address on `POST /api/*` (default capacity 20, refill 1/s).

### GET /api/v1/urls/{code}

```json
{"code":"spec-demo","shortUrl":"http://localhost:8080/spec-demo","longUrl":"https://example.com/spec",
 "createdAt":"2026-10-02T21:30:20.519955Z","expiresAt":"2031-01-01T00:00:00Z"}
```
`expiresAt` is `null` for non-expiring links. Does not record a click.

### GET /api/v1/urls/{code}/stats

```json
{"code":"spec-demo","totalClicks":2,"lastAccessedAt":"2026-10-02T21:30:21.185623Z","clicksByDay":{"2026-10-02":2}}
```
`lastAccessedAt` is `null` before the first click. `clicksByDay` keys are **server-local** dates.

### DELETE /api/v1/urls/{code}

Header `X-API-Key: <admin key>`. Observed: no key → 403; correct key → 204; afterwards `GET /{code}` → 410.
If the service has no `SHORTENER_ADMIN_KEY`, delete is disabled (always 403). The key is checked *before* the lookup,
so an unauthenticated caller cannot learn whether a code exists. Delete is soft: stats history is kept and the code
stays reserved.

### GET /{code}

```
HTTP/1.1 302
Location: https://example.com/spec
Cache-Control: no-store
```
302 (not 301) so browsers don't cache it; each successful redirect records one click. Unknown → 404; deleted or
expired → 410. `{code}` must match `[A-Za-z0-9_-]{3,32}`; other paths fall through to normal 404 handling.

## Errors

Shape (RFC 7807): `{"type":"about:blank","title":…,"status":…,"detail":…,"instance":<request path>}`.

| Status | When | Example `detail` (observed) |
|---|---|---|
| 400 | Domain rule violated | `only these URL schemes are allowed: [https]` · `expiresAt must be in the future` |
| 400 | Bean validation / malformed JSON | `Invalid request content.` · `Failed to read request` |
| 403 | Missing/wrong/unconfigured admin key | `a valid X-API-Key is required` |
| 404 | Unknown code | `No short URL with code 'nope1234'` |
| 409 | Alias unavailable | `Alias 'SPEC-demo' is not available` (case-insensitive clash with `spec-demo`) |
| 410 | Deleted or expired | `Short URL 'spec-demo' was deleted or has expired` |
| 429 | Rate limit | `Rate limit exceeded`, header `Retry-After: 1` |
| 503 | No unique code after 5 attempts | `could not allocate a unique code, please retry` |

Observed rate limiting: with the default bucket, 25 rapid creates produced 201 for the first 14 and then a mix of
201/429 as tokens refilled (earlier requests in the same minute had already consumed tokens). The 429 body is
`{"title":"Too Many Requests","status":429,"detail":"Rate limit exceeded"}` – it is written by the servlet filter, so
it has no `type`/`instance` fields and is served with `charset=ISO-8859-1` (a minor inconsistency, see below).

## Versioning and compatibility

| Contract version | Change | Compatible with older clients? |
|---|---|---|
| 1.0.0 | Initial API | – |
| 1.1.0 | `expiresAt` (request, `UrlResponse`); `clicksByDay` (`StatsResponse`); alias bug fix (stricter) | Yes – additive fields; the only behavioural tightening is rejecting `Admin`-style reserved aliases and case-only duplicate aliases |
| 1.2.0 | Destination scheme **https only**; optional domain denylist | **Behavioural tightening**: `http://` destinations that used to work now return 400. This came from the ambiguous scenario's stakeholder answer and is a generation-time setting (`ALLOWED_SCHEMES`) |

The path prefix `/api/v1` has not changed. Clients should ignore unknown response fields.

## Known gaps in the contract

* `GET` metadata/stats are unauthenticated; only delete is protected, with one shared key.
* `clicksByDay` is server-local, not UTC.
* The 429 response differs slightly from other errors (no `type`/`instance`, charset header).
* `shortUrl` is built from configuration, not from the request's `Host` header.
* No pagination or listing endpoint; no update endpoint (links are immutable except deletion).
* The OpenAPI document is hand-maintained from the verified behaviour; the generator's `docs/design/openapi.yaml`
  inside each generated project is a simpler, plausibility-checked version and is not guaranteed to match field for field.
