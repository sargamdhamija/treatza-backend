# Treatza Backend — Java + Postgres (permanent storage)

Same backend, but orders now save to a real **Postgres database** (via Neon, free
forever) instead of a local file — so your orders survive server restarts and
redeploys, which matters once this goes live for real customers.

I compiled this and tested every error path locally (missing config, missing driver —
both fail with a clear message instead of crashing). I could not test an actual live
database connection from my side (no internet access in my environment), so that part
you'll be confirming yourself in step 3 below — but the database code follows completely
standard, well-established JDBC patterns.

## What's new vs the previous version
- `OrderStore.java` now talks to Postgres instead of writing `orders.json`
- New `.env` field: `DATABASE_URL`
- `compile.bat`/`start.bat` now include the Postgres driver on the classpath
- `Dockerfile` auto-downloads the driver during deployment — no manual step needed there

## 1. Create your free Neon database
1. Go to **https://neon.tech** → sign up (free, no credit card)
2. Create a new project (any name, e.g. "treatza")
3. On the project dashboard, find **"Connection string"** — copy it. It looks like:
   ```
   postgresql://user:AbC123xyz@ep-cool-flower-12345.us-east-2.aws.neon.tech/neondb?sslmode=require
   ```

## 2. Download the Postgres JDBC driver (one-time, for local testing)
1. Go to **https://jdbc.postgresql.org/download/**
2. Download the latest `postgresql-42.x.x.jar`
3. Rename it to exactly `postgresql.jar` and put it in this same folder (next to
   `TreatzaServer.java`)

## 3. Set up and run
```
cd treatza-backend-java
copy .env.example .env
```
Open `.env` in Notepad:
- Set `ADMIN_KEY` to your own password
- Paste your Neon connection string as `DATABASE_URL`

Compile (double-click `compile.bat`), then run (double-click `start.bat`).

You should see `Treatza backend running on http://localhost:4000` — same as before.
The very first run automatically creates the `orders` table in your Neon database, no
manual SQL needed.

**Confirm it's really using the database:** place a test order from the app, then stop
the server (Ctrl+C) and start it again (`java -cp .;postgresql.jar TreatzaServer`) — the
order should still show up on `admin.html`. With the old file-based version this always
worked locally too; the real difference shows up after deploying (step 5), where a
redeploy used to wipe `orders.json` and now won't.

## 4. Razorpay setup
Unchanged from before — same steps as the file-based version (Test Mode keys from
Razorpay's dashboard, no KYC needed to start).

## 5. Deploy to Render (now safe to redeploy without losing data)
Same steps as before:
1. Push this folder to a GitHub repo (this time the `Dockerfile` handles the driver
   download automatically — you don't need to upload `postgresql.jar` to GitHub)
2. Render → New → Web Service → connect the repo → it detects the `Dockerfile`
3. Add environment variables: `ADMIN_KEY`, `DATABASE_URL` (your Neon string),
   `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`
4. Deploy

Now Render can restart, redeploy, or sleep/wake this service as much as it wants —
your orders live safely in Neon, completely separate from Render's filesystem.

## Notes
- Neon's free tier: 0.5GB storage, resumes in under a second when idle — more than
  enough for a small bakery's order volume for a very long time.
- If you ever outgrow Neon's free tier, nothing about this code changes — you'd just
  point `DATABASE_URL` at a bigger Postgres instance.

## 6. Login system

There are now two separate logins, for two different people:

### Admin login (you / staff)
Unchanged in spirit — open `admin.html`, enter the `ADMIN_KEY` from your `.env`.
Under the hood it's now sent as a request header (`X-Admin-Key`) instead of a URL
parameter, so it doesn't end up in browser history or server logs. The old
`?key=...` link in the startup message still works too, so nothing breaks.

### Customer login / signup (new)
New database-backed accounts for your customers, with properly hashed passwords
(PBKDF2, industry-standard, no plain-text password ever stored). New file:
`UserStore.java`. It automatically creates two new tables (`users`, `sessions`)
in the same Neon database the first time the server starts — no manual SQL needed.

API endpoints (all under `/api/auth`):
- `POST /api/auth/signup` — body `{ "name", "phone", "password" }` → creates an
  account and logs them in, returns `{ token, user }`
- `POST /api/auth/login` — body `{ "phone", "password" }` → returns `{ token, user }`
- `GET /api/auth/me` — header `Authorization: Bearer <token>` → returns the logged-in user
- `POST /api/auth/logout` — header `Authorization: Bearer <token>` → invalidates the token

### Orders now follow the logged-in account
`orders` table has a new `user_id` column (added automatically — no manual migration
needed, works on existing databases too). When a customer places an order while
logged in, it's tagged with their account. Guest checkouts still work exactly as
before — `user_id` is just left empty.

- `GET /api/orders/mine` — header `Authorization: Bearer <token>` → returns that
  customer's own order history, so it shows up correctly even on a different phone
  after logging in.

Orders placed before this update, or placed as a guest, won't retroactively show up
under an account — only new orders placed while logged in get linked.

Sessions last 30 days. Your frontend (app/website) should store the `token` (e.g. in
`localStorage`) and send it as `Authorization: Bearer <token>` on any request that
needs to know who the customer is.

**Try it now:** open `http://localhost:4000/login-test.html` — a small test page
included in `public/` that lets you sign up and log in through the browser, so you
can confirm it all works before wiring it into your real app/website.

Note: this backend zip doesn't include a customer-facing storefront app, so
`login-test.html` is just there for testing the API. If you tell me about your actual
frontend (web app, or a specific app framework), I can wire the real login screens
up to these same endpoints.
