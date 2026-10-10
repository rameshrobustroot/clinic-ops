# Roadmap — deferred items

Things consciously deferred. Listed in rough order of when they actually
become required (not in priority of "want to do them").

## Production / SaaS readiness

| Item | Trigger to do it | Effort | Notes |
|---|---|---|---|
| **Automated PG backups** | Before any external user | ½ day | `pg_dump` cron → external storage (USB / Dropbox / S3). The one item I'd not skip even in pilot. |
| **HTTPS + domain** | Before first pilot clinic | 1 day | Cloudflare Tunnel or Caddy. iOS PWA install requires HTTPS. |
| **Subscription billing** | First paying customer | 3 days | Razorpay subscriptions (test mode is free to develop against). |
| **Email (password reset, reminders)** | Customer asks for it | 1-2 days | Test locally with MailHog. Prod via SendGrid / Brevo. |
| **SMS (appointment reminders)** | Customer asks for it | 1-2 days | MSG91 (India) or Twilio. Free sandbox numbers. |
| **Basic monitoring** | First paying customer | ½ day | Spring Actuator + UptimeRobot for "is the app up". Add Prometheus/Grafana when needed. |
| **S3-compatible file storage** | First multi-instance deploy | 1 day | MinIO locally; R2/B2/S3 in prod. Replaces local `uploads/` dir. |
| **Redis sessions** | First multi-instance deploy | ½ day | Or use sticky sessions at the load balancer instead. |

## Backend hardening (when scale demands)

- DB partitioning by `clinic_id` (when >1,000 active clinics)
- PostgreSQL Row-Level Security as third-layer tenant isolation backstop
- Read replicas (when read load is high)
- Per-tenant data export (GDPR / customer offboarding)
- Audit log for sensitive actions

## Feature ideas (parked)

- Patient-facing mobile app (book appointments, view Rx history) — likely React Native
- WhatsApp Business API for prescription image delivery (currently `wa.me` deep-links only)
- Multi-branch / chain clinics support (one owner, multiple clinic locations)
- Public clinic profile / appointment booking page
- Lab integration (LIMS systems for blood test reports)
- Insurance claim workflow
- Inventory reorder automation (auto-generate procurement when stock hits threshold)
- Daily follow-up reminders via cron + WhatsApp

## REST API layer

Not built. Service layer is clean enough to expose REST endpoints under
`/api/v1/...` whenever needed (~2-3 days for current feature set). Required
when building a separate native mobile app or for third-party integrations.

## Tested locally only — needs real-env verification later

- Phone access from another device (LAN works; over public internet needs HTTPS + tunnel)
- PWA installability on iOS Safari (needs HTTPS)
- Service worker behaviour on flaky networks
