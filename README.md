# TestProject

Basic invite-only auth monorepo.

- `backend/demo` – Spring Boot 4, Postgres, Flyway, JWT in an HttpOnly cookie
- `frontend` – SvelteKit static SPA (pnpm, Tailwind, shadcn-svelte)
- `deploy` – production Docker Compose file, nginx and firewall helpers for the VPS (see [DEPLOY.md](DEPLOY.md))
- `.github/workflows` – CI: runs the backend tests and publishes the backend Docker image to GitHub's registry

Registering needs a single-use invite token created by an existing user (the "Create invite link"
button on the home page). The first user is seeded from `APP_BOOTSTRAP_USERNAME` /
`APP_BOOTSTRAP_PASSWORD` while the users table is empty.

## Run locally

Open **three terminals**, all starting in the project root folder. The backend and the frontend keep
running until you press `Ctrl+C`, so each needs its own terminal.

**Terminal 1: database** (Docker Desktop must be running)

```powershell
docker compose up -d --wait
```

**Terminal 2: backend** (http://localhost:8080, first user `admin` / `admin12345`)

```powershell
cd backend\demo
.\gradlew.bat bootRun --args="--spring.profiles.active=dev"
```

**Terminal 3: frontend** (http://localhost:5173, requests to `/api` are forwarded to the backend)

```powershell
cd frontend
pnpm install
pnpm dev
```

Open http://localhost:5173 and log in. To stop: `Ctrl+C` in terminals 2 and 3, and
`docker compose down` in terminal 1. (macOS/Linux: use `./gradlew` instead of `.\gradlew.bat`.)

New to servers? [DEPLOY.md](DEPLOY.md) is a step-by-step guide for putting this online.

## Tests and checks

```sh
cd backend/demo && ./gradlew test           # needs Docker (Testcontainers)
cd frontend && pnpm check && pnpm lint && pnpm build
```

## API

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | – | `{username, password, inviteToken}`, logs in on success |
| POST | `/api/auth/login` | – | `{username, password}` |
| POST | `/api/auth/logout` | – | clears the cookie |
| GET | `/api/auth/me` | cookie | current user |
| POST | `/api/invites` | cookie | returns `{token, expiresAt}`, shown once |

State-changing requests must send `X-Requested-By: spa` (CSRF guard). The frontend does this in
`src/lib/api.ts`.

## Known limits

- Logout only clears the cookie. The JWT itself stays valid until it expires (12h, `app.jwt.ttl`);
  there is no server-side revocation.
- No password reset and no rate limiting in the app itself. nginx rate-limits `/api/auth/` in production.
