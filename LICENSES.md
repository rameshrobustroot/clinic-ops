# Third-Party Licenses

ClinicOps is built entirely on open-source software that is safe for commercial
use. This document lists every direct dependency, its license, and where to
find the upstream project. Apache 2.0 obligations are satisfied by retaining
this attribution in distributions.

## Runtime — Java (backend)

| Dependency | Version | License | Upstream |
|---|---|---|---|
| Spring Boot (+ all starters) | 3.4.13 | Apache 2.0 | https://github.com/spring-projects/spring-boot |
| Spring Framework | 6.2.x | Apache 2.0 | https://github.com/spring-projects/spring-framework |
| Spring Security | 6.4.x | Apache 2.0 | https://github.com/spring-projects/spring-security |
| Spring Data JPA | 3.4.x | Apache 2.0 | https://github.com/spring-projects/spring-data-jpa |
| Hibernate ORM | 6.6.x | Apache 2.0 | https://github.com/hibernate/hibernate-orm |
| HikariCP | 5.x | Apache 2.0 | https://github.com/brettwooldridge/HikariCP |
| Apache Tomcat (embed) | 10.1.x | Apache 2.0 | https://github.com/apache/tomcat |
| Jackson (core, databind, jsr310) | 2.18.x | Apache 2.0 | https://github.com/FasterXML/jackson |
| Thymeleaf | 3.1.x | Apache 2.0 | https://github.com/thymeleaf/thymeleaf |
| Liquibase Core | 4.29.x | Apache 2.0 | https://github.com/liquibase/liquibase |
| PostgreSQL JDBC | 42.7.x | BSD 2-clause | https://github.com/pgjdbc/pgjdbc |
| Lombok | 1.18.x | MIT | https://github.com/projectlombok/lombok |
| Hibernate Validator | 8.x | Apache 2.0 | https://github.com/hibernate/hibernate-validator |
| AspectJ Weaver | 1.9.x | EPL 2.0 | https://github.com/eclipse-aspectj/aspectj |
| **OpenPDF** | 2.0.3 | **MPL 2.0 OR LGPL 2.1** (we use under MPL 2.0) | https://github.com/LibrePDF/OpenPDF |
| SLF4J + Logback | 2.0.x / 1.5.x | MIT / EPL 1.0 + LGPL 2.1 | https://www.slf4j.org / https://logback.qos.ch |

### Note on OpenPDF
OpenPDF is the open-source fork of iText 4.2.0. We use it under the
**Mozilla Public License 2.0** (one of its dual-license options). We do **not**
use iText 5+/7+, which is AGPL and would require a commercial license.

## Runtime — Frontend (self-hosted under `/vendor/`)

| Library | Version | License | Upstream |
|---|---|---|---|
| Tailwind CSS (Play CDN script) | 3.4.16 | MIT | https://github.com/tailwindlabs/tailwindcss |
| HTMX | 1.9.12 | 0BSD (Zero-clause BSD) | https://github.com/bigskysoftware/htmx |
| Alpine.js | 3.14.1 | MIT | https://github.com/alpinejs/alpine |

All three scripts are bundled into the application JAR — the app does **not**
make any third-party CDN calls at runtime.

## Build-only / Test-only

| Dependency | License |
|---|---|
| Maven plugins (compiler, resources, spring-boot, etc.) | Apache 2.0 |
| JUnit 5 + Mockito + Spring Test | EPL 2.0 / MIT / Apache 2.0 |
| H2 Database (test scope only) | MPL 2.0 / EPL 1.0 |

## External services used by the app at runtime

- **WhatsApp deep links** (`https://wa.me/<phone>?text=...`) — link out to
  WhatsApp's installed app. No data sent through us; recipient phone numbers
  are passed via URL. Subject to WhatsApp's Terms of Service.

## Fonts on the prescription PDF

OpenPDF renders the prescription using the **standard 14 PDF fonts**
(Helvetica family). These are part of the PDF specification — no font
licensing required. No fonts are embedded or distributed.

## Commercial-use summary

Every dependency above is compatible with closed-source commercial
distribution. The only license obligations are:
1. Retain copyright notices (this file satisfies that).
2. If you modify an LGPL/MPL/EPL library, make that modified source available.
   We do not modify any of these libraries.

If you fork ClinicOps and ship it commercially, you may bundle this file as-is.
