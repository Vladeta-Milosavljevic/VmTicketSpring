# VmTicketSpring

Minimal ticket-management REST API built with **Java 25** and **Spring Boot 4.1.1**.

## Prerequisites

- JDK 25+
- Maven 3.9+ (or use a local Maven install)
- Optional: PostgreSQL 16+ for `prod` profile

## Profiles

| Profile | Datasource | Notes |
|---------|------------|--------|
| `local` (default) | H2 file DB under `./data` | Opaque Bearer auth; Flyway loads `db/migration` and the V8 seed from `db/seed` |
| `test` | H2 in-memory | Used by Surefire; same V8 seed password as local |
| `prod` | PostgreSQL via env vars | Flyway loads `db/migration` only. Set `BOOTSTRAP_ADMIN_PASSWORD` so the first start can create `admin` |

Schema is owned by **Flyway** (`spring.jpa.hibernate.ddl-auto=validate`).

## Run (local)

```bash
mvn spring-boot:run
```

App listens on `http://localhost:8080`.

- Health: `GET /actuator/health`
- OpenAPI JSON: `GET /v3/api-docs` (open locally; prod requires a bearer token)
- Swagger UI: `http://localhost:8080/swagger-ui.html` (open locally; prod requires a bearer token)
- H2 console: `http://localhost:8080/h2-console` (local profile only)

## Auth

Login verifies the user's stored BCrypt password hash and issues an **opaque** Bearer token (SHA-256 of the raw token stored in `access_tokens`; TTL via `app.auth.token-ttl`). Available in **all** profiles, including `prod`. Usernames are stored and matched in lowercase, so `Admin` and `admin` are the same account. Five failed logins for the same username within 15 minutes return **429** (`Too many login attempts`); a successful login clears that count. The window is in-memory per JVM (`app.auth.login-max-failures` / `app.auth.login-window`). Flyway **V10** lowercases existing `users.username` rows. Flyway **V11** drops `comments.is_internal` and adds `tickets.version`. A local H2 database under `./data` that already applied V10 picks V11 up on the next start.

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"password\"}"
```

Response: `accessToken`, `tokenType` (`Bearer`), `username`, `roles` (from `user_roles`).

Logout (authenticated) revokes the current token:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Authorization: Bearer $TOKEN"
```

### Bootstrap credentials

Local and test load `src/main/resources/db/seed/V8__seed_bootstrap_data.sql` (not part of `db/migration`). That seed creates:

`admin`, `manager.one`, `manager.two`, `agent.one`, `agent.two`, `agent.three`

Shared seed password: `password`. `ADMIN` cannot be created via the API.

Prod does not load `db/seed`. On startup, when `app.bootstrap.enabled` is true, the app inserts username `admin` with role `ADMIN` only if that user is missing. The password comes from `BOOTSTRAP_ADMIN_PASSWORD` and must be 8–72 characters. If an active `ADMIN` named `admin` already exists, startup leaves the password unchanged. A blank password fails startup when the admin user still has to be created. A soft-deleted or non-`ADMIN` user named `admin` fails prod startup; the runner does not create a second user.

If a local H2 database under `./data` already recorded V8 while that script lived under `db/migration`, delete `./data` (and `./data/uploads` if present) before starting again so the moved seed can apply cleanly. A database that already applied V8 from `db/migration` cannot simply drop that file from its history.

### Roles

Roles: `AGENT`, `MANAGER`, `ADMIN`.

| Endpoint | Who |
|----------|-----|
| `GET` tickets | any authenticated user, but only tickets they can see: `ADMIN` sees every active ticket; others see tickets where they are the requester, the assigned agent (and still have `AGENT`), or the assigned manager (and still have `MANAGER`). A ticket that is not visible is **404**, never 403. `q` matches title, description, or category name |
| `POST` tickets | `AGENT`, `MANAGER`, `ADMIN`. An agent sets `manager` and does not set `agent`. `MANAGER` and `ADMIN` may set `agent`. Optional `deadline`; omit or null means no deadline. Agents may set it |
| `PATCH` tickets | `AGENT`, `MANAGER`, `ADMIN`. Body must include `version` from GET; a stale version is **409**. Assignment fields (`agent`, `manager`) are `MANAGER` or `ADMIN` only. The assigned agent may still patch status, `deadline`, and other non-assignment fields when write access allows it. Sending `deadline` sets it; omitting it leaves the value (it cannot be cleared). `COMPLETED` and `CANCELLED` tickets cannot be patched. `REJECTED` can move to `IN_PROGRESS` or `CANCELLED` |
| `DELETE` tickets | `ADMIN` (soft-delete; a later `GET` is 404) |
| `GET` categories | any authenticated user |
| `POST` / `PATCH` categories | `MANAGER`, `ADMIN`. No delete; `isArchived` hides a category from the default list |
| `GET` users, `GET` user by id | `MANAGER` or `ADMIN` |
| `POST` users | `MANAGER` or `ADMIN`. A manager may assign only `AGENT`. An admin may assign only `AGENT` or `MANAGER` |
| `PATCH` user role | `ADMIN` only, and only `AGENT` ↔ `MANAGER` |
| `DELETE` users | `ADMIN` only, soft-delete of an active `AGENT` |
| `PATCH /api/v1/users/me/password` | any authenticated user (current password required). Success revokes that user's tokens |
| `PATCH /api/v1/users/{id}/password` | `ADMIN`, and only for an active `AGENT` or `MANAGER`. An `ADMIN` target is 403. Success revokes that user's tokens |
| View comments, list and download files, list rejections | any authenticated user who can see the ticket (same visibility as GET ticket). Soft-deleted or not-visible tickets are **404** |
| Create a comment | the ticket's assigned agent, the ticket's assigned manager, or `ADMIN` |
| Remove an attachment | the uploader, that ticket's manager (caller must still have role `MANAGER`), or `ADMIN` |

Write access requires the caller to currently hold the role of the slot they occupy (`AGENT` for the agent slot, `MANAGER` for the manager slot), or be `ADMIN`.

Files may be added only when a ticket or a comment is created. Allowed uploads are `png`, `jpg`, `jpeg`, `gif`, `webp`, `pdf`, `txt`, and `csv`, and the bytes must match that type. There is no comment edit or delete. Deleted users stay referenced by existing work and cannot be newly assigned. Tickets and comments do not create users.

## Sample curls

```bash
# Login as the local/test seed admin
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password"}' \
  | jq -r .accessToken)

# Create ticket (AGENT sets manager only; MANAGER or ADMIN may also set agent; deadline is optional)
curl -s -X POST http://localhost:8080/api/v1/tickets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"VPN down","description":"Cannot connect","urgency":"HIGH","manager":"manager.one","agent":"agent.one","deadline":"2026-12-01T12:00:00Z"}'

# Get by id (replace ID)
curl -s http://localhost:8080/api/v1/tickets/ID \
  -H "Authorization: Bearer $TOKEN"

# List / filter
curl -s "http://localhost:8080/api/v1/tickets?status=OPEN&q=VPN&page=0&size=20&sort=createdAt,desc" \
  -H "Authorization: Bearer $TOKEN"

# Patch status as the assigned agent
AGENT_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"agent.one","password":"password"}' \
  | jq -r .accessToken)

curl -s -X PATCH http://localhost:8080/api/v1/tickets/ID \
  -H "Authorization: Bearer $AGENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status":"IN_PROGRESS","version":0}'

# Soft-delete as admin
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE http://localhost:8080/api/v1/tickets/ID \
  -H "Authorization: Bearer $TOKEN"
```

## Status transitions

```
OPEN → IN_PROGRESS | CANCELLED
IN_PROGRESS → PENDING_REVIEW | OPEN | CANCELLED
PENDING_REVIEW → COMPLETED | REJECTED
REJECTED → IN_PROGRESS | CANCELLED
COMPLETED → none
CANCELLED → none
```

Rejecting a ticket records the agent assigned before that patch. Moving to `PENDING_REVIEW` sets `completedByAgentAt`. Moving to `OPEN` or `IN_PROGRESS` clears it. `COMPLETED`, `REJECTED`, and `CANCELLED` leave it unchanged. `COMPLETED` and `CANCELLED` tickets cannot be patched. `REJECTED` can still move to `IN_PROGRESS` or `CANCELLED`.

## Run against PostgreSQL (prod)

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/vmticket
export DATABASE_USERNAME=vmticket
export DATABASE_PASSWORD=secret
export BOOTSTRAP_ADMIN_PASSWORD='choose-a-password'

mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

Flyway applies `src/main/resources/db/migration` only. Prod does not load `db/seed`. The first start creates `admin` from `BOOTSTRAP_ADMIN_PASSWORD` (8–72 characters) when that user is absent. A soft-deleted or non-`ADMIN` user named `admin` fails startup. Create further users with `POST /api/v1/users`. Prod Swagger and `/v3/api-docs` require a bearer token. The H2 console stays local-only.

## Tests

```bash
mvn verify
```

## API surface (v1)

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/logout`
- `POST /api/v1/tickets` (JSON, or multipart with files)
- `GET /api/v1/tickets/{id}`
- `GET /api/v1/tickets` (filter: `status`, `urgency`, `agent`, `manager`, `q` on title/description/category name; paging/sort on `createdAt`, `updatedAt`, `title`, `status`, `urgency`; results are visibility-scoped)
- `PATCH /api/v1/tickets/{id}` (requires `version`; stale is 409)
- `DELETE /api/v1/tickets/{id}` (soft-delete)
- `GET /api/v1/tickets/{ticketId}/attachments`
- `GET /api/v1/tickets/{ticketId}/attachments/{attachmentId}`
- `DELETE /api/v1/tickets/{ticketId}/attachments/{attachmentId}`
- `GET /api/v1/tickets/{ticketId}/comments`
- `POST /api/v1/tickets/{ticketId}/comments` (JSON, or multipart with files)
- `GET /api/v1/tickets/{ticketId}/comments/{commentId}/attachments/{attachmentId}`
- `DELETE /api/v1/tickets/{ticketId}/comments/{commentId}/attachments/{attachmentId}`
- `GET /api/v1/tickets/{ticketId}/rejections`
- `POST /api/v1/categories`
- `GET /api/v1/categories/{id}`
- `GET /api/v1/categories` (`q`, `includeArchived`; sort on `name`, `slug`)
- `PATCH /api/v1/categories/{id}`
- `POST /api/v1/users`
- `GET /api/v1/users/{id}`
- `GET /api/v1/users` (`q`, `includeDeleted`; sort on `username`, `createdAt`)
- `PATCH /api/v1/users/{id}`
- `DELETE /api/v1/users/{id}` (soft-delete of an active `AGENT`)
- `PATCH /api/v1/users/me/password`
- `PATCH /api/v1/users/{id}/password`
