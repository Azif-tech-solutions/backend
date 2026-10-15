# ZifIQ backend docs

One design doc per module, numbered in build order (`implemented/03-cross-module-conventions.md` §1 explains the order and why). Each doc moves folder as its module progresses:

- **`implemented/`** — built, tested and deployed. The doc describes what runs; differences from the original design are recorded in the doc itself.
- **`in-progress/`** — being built in slices; the doc's status line says which slices are done.
- **`planned/`** — designed, not started.

| # | Module | Status | Doc |
|---|---|---|---|
| 00 | Platform foundation (Exposed, tenancy, conventions) | Implemented | [implemented/00-platform-foundation.md](implemented/00-platform-foundation.md) |
| 01 | Users, tenants, access control, locations | Implemented | [implemented/01-user-tenant-access-control.md](implemented/01-user-tenant-access-control.md) |
| 02 | Factory & production | Implemented | [implemented/02-factory.md](implemented/02-factory.md) |
| 03 | Cross-module conventions (search, filters, events, files, …) | Implemented | [implemented/03-cross-module-conventions.md](implemented/03-cross-module-conventions.md) |
| 04 | Customers & distributors | Implemented | [implemented/04-customers-distributors.md](implemented/04-customers-distributors.md) |
| 05 | Showroom | Implemented | [implemented/05-showroom.md](implemented/05-showroom.md) |
| 06 | Settings | Implemented | [implemented/06-settings.md](implemented/06-settings.md) |
| 07 | Finance & accounting | Implemented | [implemented/07-finance-accounting.md](implemented/07-finance-accounting.md) |
| 08 | Electronic invoicing | In progress (slice 1 of 2) | [in-progress/08-electronic-invoicing.md](in-progress/08-electronic-invoicing.md) |
| 09 | Sales & POS | Planned | [planned/09-sales-pos.md](planned/09-sales-pos.md) |
| 10 | Audit & compliance | Planned | [planned/10-audit-compliance.md](planned/10-audit-compliance.md) |
| 11 | Barcode & QR | Planned | [planned/11-barcode-qr.md](planned/11-barcode-qr.md) |
| 12 | Communication | Planned | [planned/12-communication.md](planned/12-communication.md) |
| 13 | Reports & analytics | Planned | [planned/13-reports-analytics.md](planned/13-reports-analytics.md) |
| 14 | Dashboard | Planned | [planned/14-dashboard.md](planned/14-dashboard.md) |
| 15 | Workflow & automation | Planned | [planned/15-workflow-automation.md](planned/15-workflow-automation.md) |
| 16 | AI & automation | Planned | [planned/16-ai-automation.md](planned/16-ai-automation.md) |

Inventory has no doc of its own: it was built with Factory (02) and extended by Showroom (05).

[frontend-integration.md](frontend-integration.md) is the guide for the frontend team (base URL, auth, conventions, recent API changes).

Code comments refer to docs by number and section (`docs/04 §4.8`), which stays valid when a doc changes folder. Flyway migrations keep the paths they were written with: an applied migration is never edited.
