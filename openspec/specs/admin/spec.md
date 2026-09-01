# KSH-4: Admin Dashboard Epic

## Overview

Admins can view platform-wide statistics, manage users, and configure
system settings (including SMTP email configuration).

**Sprint:** 2 (Dashboard + Email Settings done), Sprint 5 (User Management)
**Labels:** admin, core
**Priority:** High

---

## KSH-4.1 — Story: Admin Dashboard

> As an ADMIN, I want to see an overview of the platform (user count, class count,
> recent classes, user-by-role breakdown) so that I can monitor the system health.

### KSH-4.1-BE: [BE] Dashboard Service

**Status:** Done

- Controller: `com.ksh.features.auth.controller.admin.AdminController`
  - `GET /admin/dashboard` — main dashboard
  - `GET /admin/users` — user list (partial)
  - `GET /admin/departments` — department list (placeholder)
- Service: `com.ksh.features.auth.service.admin.AdminDashboardService`
- DTOs: `com.ksh.features.auth.dto.admin.AdminDashboardDtos`
  - `DashboardStats` — 4 stat card numbers
  - `UserRoleCount` — donut chart data
  - `RecentClass` — 5 most recent classes table

**Acceptance criteria:**
- `GET /admin/**` is restricted to ROLE_ADMIN via `@PreAuthorize`
- Dashboard loads within one query per aggregate (no N+1)
- Stat cards show: total users, total classes, total departments, total courses

---

### KSH-4.1-FE: [FE] Dashboard View

**Status:** Done

- Template: `admin/dashboard.html`
- 4 stat cards + user role donut chart + recent classes table
- Vietnamese role labels via `displayRole()` (e.g. "Sinh viên", "Giảng viên")
- Vietnamese status labels via `displayStatus()` (e.g. "Đang hoạt động")

---

## KSH-4.2 — Story: Email Settings (SMTP Configuration)

> As an ADMIN, I want to configure SMTP settings (host, port, encryption, credentials)
> and send a test email to verify the configuration works.

### KSH-4.2-DB: [DB] System Settings Schema

**Status:** Done

- Entity: `com.ksh.entities.SystemSetting`
- Table: `system_settings`
- Key columns: `setting_group`, `setting_key`, `setting_value`, `updated_by`
- SMTP keys: `smtp.host`, `smtp.port`, `smtp.encryption`, `smtp.username`,
  `smtp.password`, `smtp.from_name`, `smtp.from_email`, `smtp.reply_to`
- Migration: `V7__system_settings.sql`, `V9__seed_smtp_settings.sql`

---

### KSH-4.2-BE: [BE] Email Settings Service

**Status:** Done

- Controller: `com.ksh.features.auth.controller.settings.admin.EmailSettingsController`
  - `GET /admin/settings/email` — view form
  - `POST /admin/settings/email` — save settings
  - `POST /admin/settings/email/test` — send test email (JSON response)
- Service: `com.ksh.features.auth.service.settings.admin.EmailSettingsService`
- Mail transport: `com.ksh.shared.mail.DbConfiguredMailSender` (reads SMTP config from DB at send time)
- DTO: `com.ksh.features.auth.dto.settings.admin.EmailSettingsDtos`

**Password masking:**
- `smtp.password` is returned as `"********"` (constant `MASKED`) to the view
- On save: if form submits empty or `MASKED`, the password row is NOT overwritten
- Only new non-masked values update the stored password

**Validation:**
- `host`: required
- `port`: required, 1–65535
- `encryption`: required, must be `none|tls|ssl`
- `username`: required
- `fromName`: required
- `fromEmail`: required, valid email format
- `replyTo`: optional, valid email if provided

**Acceptance criteria:**
- Test email endpoint returns `{"ok":true}` on success or `{"ok":false,"error":"..."}` on failure
- OAuth2-only admin session (principal == null) gets a flash error instead of NPE
- All upserts run in a single `@Transactional` — all-or-nothing

---

## KSH-4.3 — Story: User Management [Sprint 5 — Planned]

> As an ADMIN, I want to view, search, activate/deactivate users, and change their roles
> so that I can manage platform access.

**Status:** Planned

### KSH-4.3-DB: [DB] No schema changes required

Existing `users` table columns `is_active` and `role` are sufficient.

### KSH-4.3-BE: [BE] User Management

**Status:** Not started

- Controller: extend `AdminController` or new `AdminUserController` [NEW]
- Endpoints:
  - `GET /admin/users?page=&q=&role=` — paginated user list with search
  - `POST /admin/users/{id}/activate` — set `is_active = true`
  - `POST /admin/users/{id}/deactivate` — set `is_active = false`
  - `POST /admin/users/{id}/role` — change role (admin only, cannot demote self)

### KSH-4.3-FE: [FE] User Management View

**Status:** Not started

- Template: `admin/users.html` [NEW] — paginated table with search bar and role filter

---

## KSH-4.4 — Story: Department Management [Sprint 5 — Planned]

> As an ADMIN or LEADER, I want to create and manage academic departments
> so that lecturers and classes can be grouped by department.

**Status:** Planned

- Table: `departments` — `id`, `name`, `code`, `leader_user_id`, `created_at`
- Table: `user_departments` — `user_id`, `department_id`
- Migration: `V11__departments.sql` (planned)
