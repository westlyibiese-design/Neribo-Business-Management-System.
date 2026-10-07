# NBMS backend (Supabase)

Plain-English notes.

## What is here
- `migrations/` : the database, written as SQL files. They run in number order.
  1. `…01_core_tenancy.sql` : businesses, which roles each business uses, and staff members.
  2. `…02_device_lock.sql` : Device Lock tables (only server functions can touch them).
  3. `…03_messages.sql` : website contact-message inbox.
  4. `…04_storage.sql` : the public photo bucket `nbms-media`.
- `functions/` : server functions (Edge Functions). `_shared/` holds helpers other functions reuse. `health/` is a tiny public check.
- `config.toml` : CLI settings. `health` is public (no login needed).

## How it gets deployed
Pushing to `main` with changes under `supabase/` runs GitHub Actions > "Deploy backend". It needs three repository secrets:
`SUPABASE_ACCESS_TOKEN`, `SUPABASE_PROJECT_ID`, `SUPABASE_DB_PASSWORD`.
If any is empty, the run skips with a message instead of failing.

## Things to know
- Every business record carries a `business_id`. Staff can only read their own business.
- Staff cannot read `pin_hash` or other PIN secrets (column privileges).
- Messages are never hard-deleted by staff. "Delete" sets `is_deleted = true`.
- Public sign-ups must stay OFF. Only server functions create accounts.
- Check it works: open `https://<project-ref>.supabase.co/functions/v1/health` in a browser.
