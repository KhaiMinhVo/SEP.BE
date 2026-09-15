# InfluencerMatch Backend

Spring Boot 3.3 / Java 21 REST API foundation for InfluencerMatch.

## Technology

- Java 21, Maven, Spring Boot 3.3
- Spring Web MVC, Validation, Security and Data JPA
- PostgreSQL 16 and Flyway
- JWT access tokens plus rotating opaque refresh tokens
- RFC 9457 error responses and correlation IDs

## Auth PoC quick start

Copy `.env.example` to `.env`, then configure a development ADMIN if required:

```properties
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_EMAIL=poc-admin@example.local
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-dev-password
BOOTSTRAP_ADMIN_FULL_NAME=PoC Administrator
```

The bootstrap is disabled by default and only exists in the `dev` profile. Never enable it in production.

```bash
docker compose up -d
mvn spring-boot:run
```

- Swagger UI: http://localhost:8080/api/v1/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/api/v1/api-docs
- Health: http://localhost:8080/api/v1/actuator/health
- Executable PoC requests: `docs/AuthPoc.http`

## Roles

- `BRAND`: assigned to every public registration. Client-supplied role values are ignored.
- `ADMIN`: created only by the development bootstrap or an out-of-band production process.

There is no public endpoint for creating or promoting an ADMIN. `/admin/**` requires `ROLE_ADMIN`.

## Auth endpoints

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`

Access tokens expire after 15 minutes. Refresh tokens expire after 7 days, are stored only as SHA-256 hashes, and rotate on use.

## Verification

```bash
mvn test
```

Flyway applies the existing V1 schema followed by `V2__auth_security_foundation.sql`; Hibernate runs with `ddl-auto=validate` outside the dedicated test configuration.
