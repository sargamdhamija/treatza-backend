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

## 7. Product catalog (admin-editable, with photos)

Products used to be hardcoded in the app itself. They now live in the database
(new `products` table, auto-seeded with your original 57-item price list the
first time the server starts — no manual data entry needed).

- `GET /api/products` — public, returns the full catalog (id, category, name,
  description, price, `soldOut`, `hasPhoto`) for the app to display
- `GET /api/products/{id}/photo` — public, returns the raw photo for a product
- `POST /api/admin/products` — admin only, create a new product
- `PATCH /api/admin/products/{id}` — admin only, update any of `name`, `cat`,
  `desc`, `price`, `soldOut`
- `POST /api/admin/products/{id}/photo` — admin only, body `{ imageBase64, mime }`
  → uploads/replaces the photo
- `DELETE /api/admin/products/{id}/photo` — admin only, removes the photo
- `DELETE /api/admin/products/{id}` — admin only, deletes the product

Photos are stored as base64 text directly in Postgres (not on the server's local
disk) so they survive redeploys — most hosts like Render wipe local files on every
deploy. The admin dashboard (`admin.html`) has a new **Products** tab to manage all
of this — mark items sold out, edit prices, and upload photos — without touching code.

## 8. Roles, staff logins, forgot password, business hours, analytics

### Role-based accounts
Every account (customer or staff) now has a `role`: `customer`, `admin`, or
`super_admin`. Your master `ADMIN_KEY` from `.env` still works exactly as before —
think of it as the one key that always has full access, no matter what. On top of
that, you can now create proper staff logins (phone + password) that also unlock
the admin dashboard, without sharing your master key:

- `POST /api/admin/staff` — **master key only** (a staff account can't create more
  staff accounts) — body `{ name, phone, password, role }` (`role` is `"admin"` or
  `"super_admin"`)
- `GET /api/admin/staff` — list staff/admin accounts
- `DELETE /api/admin/staff/{id}` — master key only, removes a staff account

A staff member logs in through the same `/api/auth/login` customers use — their
token then works on every `/api/admin/*` route automatically.

### Forgot / reset password
There's no SMS or email service connected (that needs a paid third-party account
with its own API key — see the note at the very end of this README). So this works
in a way that needs no such service:

1. Customer taps "Forgot password" in the app and enters their phone number →
   `POST /api/auth/forgot-password { phone }`. This generates a 6-digit code valid
   for 15 minutes, but doesn't send it anywhere yet.
2. The bakery owner opens the admin dashboard's **Settings** tab, finds the
   customer's pending code under "Password reset requests", and relays it to them
   directly — a phone call, WhatsApp message, or in person.
3. Customer enters the code + a new password in the app →
   `POST /api/auth/reset-password { phone, code, newPassword }`.

If you later connect an SMS provider, step 2 can be automated — see the note at
the end of this file.

### Business hours
- `GET /api/store-status` — public, returns `{ isOpen, message }`
- `PATCH /api/admin/store-status` — admin only, body `{ isOpen, message }`

The app shows a banner and disables checkout when the store is marked closed.
Toggle it from the admin dashboard's **Settings** tab.

### Sales analytics
- `GET /api/admin/analytics` — admin only, returns total orders, total revenue,
  the top 10 best-selling items, and a day-by-day breakdown for the last 7 days.
  Shown in the admin dashboard's **Analytics** tab.

### Still needs your own account + API key (not something I can wire up blind)
- **Order status notifications** (SMS or push) — needs an SMS provider (e.g.
  Twilio, MSG91) or Firebase for push notifications. Once you have credentials,
  this plugs in fairly easily on top of the `orderStatus` field that already
  exists on every order.
- **Distance-based delivery fee** — needs a maps/geocoding provider (e.g. Google
  Maps Distance Matrix API), which requires its own billed API key.

## 9. Firebase login (email/password + Google Sign-In)

Customer login moved from phone+password to **Firebase Authentication** —
email/password or "Sign in with Google" — because it comes with automatic
password-reset emails for free (no SMS/email service needed on our side).
Phone number is still mandatory, but now collected as a required step right
after first login instead of at signup (Google/email sign-in doesn't ask for it).

- `POST /api/auth/firebase-login` — body `{ idToken }` (the ID token the app
  gets from Firebase after the customer signs in) → verifies it and returns
  `{ token, user }`, same shape as the old signup/login endpoints. The returned
  `user.needsPhone` is `true` until the phone step below is completed.
- `PATCH /api/auth/profile` — header `Authorization: Bearer <token>`, body
  `{ phone }` → saves the customer's phone number.

**Setup required** (`.env`):
```
FIREBASE_PROJECT_ID=treatzabakery
```

Token verification (`FirebaseAuth.java`) is hand-written using only the JDK —
no extra library/build tool needed. It fetches Google's public signing certs,
verifies the token's RS256 signature and standard claims (issuer, audience,
expiry) itself, the same thing the official Firebase Admin SDK does under the
hood.

The old phone+password login (`/api/auth/signup`, `/api/auth/login`) still
works exactly as before — it's what **staff/admin accounts** (see section 8)
continue to use to log into the dashboard. Only the customer-facing app switched
to Firebase.

**A file you'll need going forward, but don't commit anywhere public:**
`firebase-service-account.json` (from Firebase Console → Project Settings →
Service Accounts → Generate new private key) — needed to actually send push
notifications (see section 10 below). It's already in `.gitignore`.

## 10. Push notifications (order status)

When you change an order's status from the admin dashboard, the customer who
placed it (if they were logged in) now gets a push notification — no SMS/email
cost, this rides on the same free Firebase project as login.

- `POST /api/notifications/register-token` — header `Authorization: Bearer
  <token>`, body `{ token }` — the app calls this after login to tell the
  backend which device to notify. A customer can have several registered
  devices (e.g. after reinstalling the app); all of them get notified.
- Existing `PATCH /api/orders/{id}` (admin) now also triggers a notification
  automatically whenever `orderStatus` changes — no separate call needed.

**Setup required:**
1. Place `firebase-service-account.json` in this folder (same one as the
   `.java` files) — the default `FIREBASE_SERVICE_ACCOUNT_PATH` in `.env`
   already points here.
2. That's it — if the file is present and valid, push notifications turn on
   automatically. If it's missing, the server logs a note and just skips
   sending (nothing else breaks).

**Important — this changes how you test the whole app going forward, not just
push notifications.** The native Firebase SDK
(`@react-native-firebase/messaging`) doesn't exist inside the plain Expo Go
app — once it's part of the project, Expo Go can no longer open the app at
all (this is exactly the tradeoff you agreed to earlier when choosing raw
Firebase over Expo's own push service). From now on, use an Expo
**development build** instead (`eas build --profile development`) for all
testing, not just push notifications.

Guest orders (no account) don't get notifications, since there's no device to
notify — nothing changes for them otherwise.

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
