# ClinicOps — Daily operations app for small clinics

[![Tests](https://github.com/rameshrobustroot/clinic-ops/actions/workflows/tests.yml/badge.svg)](https://github.com/rameshrobustroot/clinic-ops/actions/workflows/tests.yml)

Mobile-first PWA for single-doctor / small-clinic operations:

- Patient registry + visit history + prescription (image/PDF/text)
- Pharmacy inventory with batch tracking
- Expiry + low-stock alerts
- Daily dashboard (patients, sales, top medicines)
- WhatsApp deep-link send (no Business API setup needed)

Spring Boot 3.4 · Java 17 · PostgreSQL · Liquibase · Thymeleaf + HTMX + Alpine.js + Tailwind (CDN) · installable PWA.

---

## Run with Docker (one command)

Easiest. Needs Docker Desktop / Docker Engine. No Java or Postgres install required.

```bash
docker compose up -d
```

First run takes ~3–5 min to build the image. After that it's ~10s.

Open **http://localhost:8090** → default login:
- clinic code `demo1`
- username `admin`
- password `admin123`

Useful commands:

```bash
docker compose logs -f app    # tail app logs
docker compose down           # stop (data + uploads persist)
docker compose down -v        # stop + wipe everything (DESTRUCTIVE)
docker compose restart app    # restart just the app
```

Data lives in named volumes `clinic_pg_data` (Postgres) and `clinic_uploads` (prescription images / clinic logos). Both survive across `docker compose down`.

## Run locally (without Docker)

Needs Java 17+ and a local Postgres.

### 1. Create the database

```bash
createdb clinic_ops
# user/pass defaults: postgres / postgres — change in src/main/resources/application.yml
```

### 2. Start the app

```bash
mvn spring-boot:run
```

App runs on **http://localhost:8090**.

### 3. Test on phone (same Wi-Fi)

```bash
# Find your machine's LAN IP
ipconfig getifaddr en0
```

Open `http://<your-ip>:8090` on your phone → tap browser menu → "Add to Home Screen" to install the PWA.

---

## Tests

```bash
mvn test                                  # full suite (~2.5 min), H2 in-memory, no Postgres needed
mvn test -Dtest='SaleServiceTest*'        # one class (include * to pick up @Nested groups)
```

Coverage report: `target/site/jacoco/index.html` (generated on every `mvn test`).

CI (`.github/workflows/tests.yml`) runs on every push to `main` and every pull request, as two
parallel jobs: the suite on H2, and the same suite on PostgreSQL 17 with the real Liquibase
migrations and `ddl-auto: validate`. To reproduce the Postgres job locally, point the test profile
at a scratch database with environment variables:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clinic_ops_test \
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=postgres \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
SPRING_LIQUIBASE_ENABLED=true SPRING_JPA_HIBERNATE_DDL_AUTO=validate \
mvn test
```

Use a throwaway database: tests roll back their own rows, but Liquibase will migrate the schema.

| Layer | Where | Style |
| --- | --- | --- |
| Pure logic | `util/`, `*ParsingTest`, interceptors, `tenant/` | plain JUnit, no Spring |
| Services | `service/*Test` | `@SpringBootTest` on H2; most extend `integration/BaseIntegrationTest` (fresh clinic, doctor, patient, user per test) |
| Controllers | `web/*ControllerTest` | `@WebMvcTest` + real Thymeleaf rendering; `@WithMockAppUser(role = ...)` for auth |
| End-to-end | `e2e/` | full HTTP → DB: login, tenant isolation, the clinic-day workflow |

The H2 schema is generated from the entities, so it does **not** have the Liquibase foreign keys,
seed data or Postgres-specific behaviour. Use real `app_user` / `stock_batch` rows in fixtures (not
made-up ids), and scope assertions to rows the test created — the master-list and medicine
catalogs are pre-seeded in real databases.

## Structure

```
src/main/java/com/clinicops/app/
├── Application.java
├── config/
├── domain/          # JPA entities
├── repository/      # Spring Data repos
├── service/         # business logic
├── security/        # Spring Security + bootstrap admin
└── web/             # MVC controllers
    └── dto/

src/main/resources/
├── application.yml
├── db/changelog/    # Liquibase
├── templates/       # Thymeleaf
│   ├── fragments/layout.html
│   ├── auth/ patient/ visit/ inventory/ alerts/ dashboard/ settings/
└── static/
    ├── css/ js/ icons/
    ├── manifest.webmanifest
    └── sw.js        # service worker (PWA shell cache)
```

## 6 MVP screens

| Path                          | Screen                                      |
| ----------------------------- | ------------------------------------------- |
| `/patients`                   | Search + recent patients                    |
| `/patients/new`               | Quick patient entry (4 required fields)     |
| `/patients/{id}` + `/visits/new` | Visit notes + prescription (text + photo)|
| `/inventory`                  | Medicine list + stock totals + next expiry  |
| `/alerts`                     | Expired / <30d / <90d / Low-stock tabs      |
| `/`                           | Dashboard (today's counts, top medicines)   |

## What's deferred (deliberately)

- True offline-first sync (PWA shell caches assets only; data ops require connectivity)
- WhatsApp Business API automation (`wa.me` deep links cover the 95% case)
- Tablet-specific layout (mobile-first works well on tablets already)
- Billing / GST invoicing
- Multi-clinic / multi-tenant
- Detailed audit reporting

## Default DB config

`application.yml` connects to `jdbc:postgresql://localhost:5432/clinic_ops` as `postgres / postgres`.
Override with env vars or a `application-local.yml`.
