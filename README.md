# InfluencerMatch Backend

Spring Boot 3.x REST API for the InfluencerMatch platform — connecting Brands with Influencers.

## Tech Stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.x |
| Database | PostgreSQL 16 |
| Security | Spring Security 6 + JWT |
| API Docs | Swagger UI (SpringDoc OpenAPI 3) |
| Build | Maven 3.x |
| Container | Docker + Docker Compose |

## Prerequisites

- Docker Desktop
- JDK 21+
- Maven 3.8+

## Quick Start

```bash
# 1. Start the database
docker compose up -d

# 2. Run the application
mvn spring-boot:run
```

- **Swagger UI** → http://localhost:8080/swagger-ui.html
- **OpenAPI JSON** → http://localhost:8080/api-docs

## Configuration

All settings are in `src/main/resources/application.yml`.

| Property | Value |
|---|---|
| Port | `8080` |
| Database | `localhost:5432/influencermatch_dev` |
| JWT expiry | `24h` |
| Refresh token expiry | `7d` |



## Roles

| Role | Description |
|---|---|
| `ADMIN` | Full platform administration |
| `BRAND` | Brand / SME campaign management |

## Docker

```bash
docker compose up -d        # Start services
docker compose down         # Stop (keep data)
docker compose down -v      # Stop and remove volumes (deletes data)
```
