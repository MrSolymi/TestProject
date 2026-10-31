# Deploying apps to your own VPS: the universal guide

A step-by-step guide for putting **any number of backends** (in Docker) and **any number of frontends** (static sites on Cloudflare) online, with automatic deploys from GitHub. It is not tied to one project: you set the server up **once**, and then every new app is a short recipe.

It is written for someone new to servers. Every step says where to run it, what it does, and what you should see afterwards (**Check**).

> Written for a VPS running **Ubuntu 24.04**, a domain managed by **Cloudflare (free plan)** and code on **GitHub**. The names in this guide (`shop`, `blog`, `example.com`) are examples. Replace them.

---

## Contents

1. [How it fits together](#1-how-it-fits-together)
2. [Part A: set up the server and the domain (once)](#2-part-a-set-up-the-server-and-the-domain-once)
3. [Part B: add a backend (repeat for every backend)](#3-part-b-add-a-backend-repeat-for-every-backend)
4. [Part C: add a frontend (repeat for every frontend)](#4-part-c-add-a-frontend-repeat-for-every-frontend)
5. [Part D: daily life](#5-part-d-daily-life)
6. [Part E: when something goes wrong](#6-part-e-when-something-goes-wrong)
7. [Checklists](#7-checklists)
8. [Glossary](#8-glossary)

---

## 1. How it fits together

```
                       ┌──────────────── Cloudflare (DNS, HTTPS, protection) ─────────────────┐
 browser ────────────► │  shop.example.com       →  static website (a Cloudflare Worker)         │
 browser ────────────► │  shop-api.example.com   ──┐                                           │
 browser ────────────► │  blog-api.example.com   ──┤  only Cloudflare is allowed to connect    │
                       └───────────────────────────┼───────────────────────────────────────────┘
                                                   ▼
  your VPS ┌─ firewall: port 443 only from Cloudflare, SSH for you ─────────────────────────────┐
           │ nginx :443 ─┬─ shop-api.example.com → 127.0.0.1:8081 → [shop api] → [shop database] │
           │             └─ blog-api.example.com → 127.0.0.1:8082 → [blog api] → [blog database] │
           └───────────────────────────────────────────────────────────────────────────────────┘

  GitHub:  git push → tests → Docker image (ghcr.io) → GitHub logs in over SSH and runs
           "deploy-app shop" on the VPS → the VPS pulls the new image and restarts that one app
```

| Piece | What it does |
| --- | --- |
| **Domain + Cloudflare** | Your address (`example.com`). Cloudflare answers visitors first: it provides HTTPS, hides your server's real IP, filters bad traffic, and hosts your static websites. |
| **VPS** | The rented always-on computer that runs your backends. |
| **nginx** | The one program that listens on the internet-facing port 443. It looks at the hostname of each request and passes it to the right app. Called a *reverse proxy*. |
| **Docker + Compose** | Runs each backend and its database in sealed boxes (*containers*). Each app has its own folder, its own containers and its own network. |
| **GitHub + Actions** | Stores the code, runs tests, builds the Docker image and stores it (*container registry*), then tells the VPS to update. |
| **Firewall (ufw)** | Only lets Cloudflare reach port 443, and only lets SSH in for management. |

### The security idea (in plain words)

Only two things on the VPS may be reachable from the internet: **SSH** (so you can manage it) and **nginx** (and nginx only accepts Cloudflare). Everything else (your backends, your databases) listens on `127.0.0.1` or inside Docker's private networks. Several layers protect that:

1. **Firewall:** port 443 only from Cloudflare's addresses.
2. **Authenticated Origin Pulls (mTLS):** nginx also demands Cloudflare's client certificate, so even someone who knows your IP cannot talk to nginx directly.
3. **nginx** answers only for hostnames you configured, and rate-limits login-like endpoints.
4. **Docker ports** are published on `127.0.0.1` only, and databases publish nothing.
5. **GitHub's deploy key** can run exactly one script, for exactly one app.

### The conventions that keep many apps tidy

Every backend gets the same set of names. Write them into a small table of your own (a notes file) so that two apps never clash:

| App name | API hostname | Host port | Folder on the VPS | Image |
| --- | --- | --- | --- | --- |
| `shop` | `shop-api.example.com` | `8081` | `/srv/shop` | `ghcr.io/<github-user>/shop` |
| `blog` | `blog-api.example.com` | `8082` | `/srv/blog` | `ghcr.io/<github-user>/blog` |

- **App name:** lowercase letters, digits and dashes. It is also the folder name, which Docker Compose uses as the project name, so every app gets separate containers, volumes and networks.
- **Host port:** a number you pick, unique per app, only ever used on `127.0.0.1`. (This guide counts up from 8081.)
- **Frontend hostnames** are your choice, for example `shop.example.com`.
- If your app uses **cookies for login**, the website and its API must be under the **same domain** (for example `shop.example.com` and `shop-api.example.com`). A free `*.workers.dev` address would not work.

### Placeholders used in this guide

| Placeholder | Meaning |
| --- | --- |
| `example.com` | your real domain |
| `<VPS_IP>` | the public IP address of your VPS |
| `<app>` | the app name, like `shop` |
| `<port>` | the app's host port, like `8081` |
| `<github-user>` | your GitHub user name, **in lowercase** (registry names must be lowercase) |
| `<you>` | the normal (non-root) user name you log in with on the VPS |

### Two kinds of terminals

- **On your PC:** PowerShell on Windows.
- **On the VPS:** an SSH session.

Do not mix them up. The most common beginner mistake is running a command in the wrong place.

---

## 2. Part A: set up the server and the domain (once)

Do this once per VPS and once per domain. After that, adding apps (parts B and C) does not repeat any of it.

### A1. What you need

- [ ] **A domain** added to Cloudflare, with the status **Active** (create a free Cloudflare account, *Add a domain*, set the two nameservers it gives you at your registrar).
- [ ] **A VPS** with Ubuntu 24.04 and its public IP. Count on roughly **0.5 GB of RAM per small backend** (a Spring Boot API plus its Postgres database used about 350 to 400 MB in our tests; lighter languages use less), plus the system. 2 GB is comfortable for a few apps.
- [ ] **A GitHub account.** Your repositories can be public or private.
- [ ] **On your PC:** a browser and an SSH client (Windows 10 and 11 have `ssh` built in).

### A2. Log in and create your own user

**On your PC:**

```powershell
ssh root@<VPS_IP>
```

Type `yes` when it asks about the fingerprint, then enter the password. (If your provider gave you a normal user instead of `root`, use it, and skip the user creation below.)

**On the VPS**, update, then create a normal user so that you do not work as `root` all the time:

```sh
apt update && apt upgrade -y
adduser <you>                  # asks for a password and some optional details
usermod -aG sudo <you>
```

Now let your PC log in with a key instead of a password. **On your PC:**

```powershell
ssh-keygen -t ed25519 -C "my-pc"          # press Enter at every question
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh <you>@<VPS_IP> "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

**Check:** open a **new** terminal and run `ssh <you>@<VPS_IP>`. It should log you in without asking for a password. From now on, log in as `<you>` and use `sudo` in front of admin commands.

Handy in the SSH window: paste with right-click (or `Ctrl+Shift+V`); edit a file with `sudo nano <file>`, save with `Ctrl+O` then `Enter`, quit with `Ctrl+X`.

### A3. Install the software

**On the VPS:**

```sh
sudo apt update && sudo apt upgrade -y
sudo apt install -y docker.io docker-compose-v2 nginx ufw curl openssl
sudo rm -f /etc/nginx/sites-enabled/default
sudo install -d /srv /etc/ssl/cloudflare /etc/nginx/snippets
```

The `rm` removes nginx's example website. Notice what is **not** installed: no Java, no Node, no Postgres. Those live inside the containers.

**Check:**

```sh
docker --version
docker compose version
nginx -v                              # 1.24.x on Ubuntu 24.04
systemctl is-active docker nginx      # active, active
uname -m                              # x86_64 (amd64): the images below are built for this
```

If `uname -m` says `aarch64` (an ARM server), build your images for ARM too: in the workflow in B6, use `platforms: linux/amd64,linux/arm64`.

### A4. Cloudflare settings for the domain (once per domain)

Open the Cloudflare dashboard and click your domain. Cloudflare renames buttons now and then, so match by the **bold** names.

**1. Encryption mode.** **SSL/TLS → Overview**. Set the mode to **Full (strict)**. (If you only see a *Configure* button, open it and choose Full (strict).) This makes Cloudflare insist on a valid certificate on your server.

**2. Always Use HTTPS.** **SSL/TLS → Edge Certificates**, then **scroll down below the list of certificates** to the row of switches and turn **Always Use HTTPS** on. Without it, anyone who opens the plain `http://` version of your site (a chat app turns a bare `shop.example.com` into an `http://` link!) gets it unencrypted, and your frontend cannot talk to your API from there. It applies to the whole domain.

**3. A certificate for your server.** **SSL/TLS → Origin Server → Create Certificate**. Keep the defaults, and make sure the hostnames are `*.example.com` and `example.com`. One wildcard certificate covers every app on this domain. After you click **Create** you see two boxes, **Origin Certificate** and **Private Key**. The key is shown **only once**, so keep the page open until both are saved on the VPS.

**On the VPS:**

```sh
sudo nano /etc/ssl/cloudflare/origin.pem      # paste the Origin Certificate (with the BEGIN/END lines), save
sudo nano /etc/ssl/cloudflare/origin.key      # paste the Private Key (with the BEGIN/END lines), save
sudo chmod 600 /etc/ssl/cloudflare/origin.key
```

**4. Authenticated Origin Pulls.** Download Cloudflare's "ID card checker" on the VPS:

```sh
sudo curl -fsSL -o /etc/ssl/cloudflare/origin-pull-ca.pem \
  https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem
```

Then in the dashboard: **SSL/TLS → Origin Server → Authenticated Origin Pulls** and switch it **On** (one switch for the whole domain).

> **Honest limit:** the default Cloudflare ID card is the same for every Cloudflare customer. Together with the firewall (which only lets Cloudflare's addresses in), it keeps strangers out. Cloudflare also offers per-zone client certificates if you want stricter control.

**Check:** `ls /etc/ssl/cloudflare` shows `origin.pem`, `origin.key` and `origin-pull-ca.pem`.

> If your apps live on **more than one domain**, repeat this whole step for each domain, and save that domain's files under another name (for example `origin-other.pem`). Use a matching copy of the `cloudflare-origin.conf` snippet from A5 in that domain's nginx files.

### A5. nginx basics (once)

Three small shared files and a script. The shared files mean every app's own nginx file stays tiny.

**On the VPS:**

**1. Defaults for the whole server** (`00-defaults.conf`):

```sh
sudo tee /etc/nginx/conf.d/00-defaults.conf > /dev/null <<'EOF'
# A rate limit that apps can use on login-like endpoints (see B4): 5 requests per second per visitor.
limit_req_zone $binary_remote_addr zone=login:10m rate=5r/s;

# Any request for a hostname that no app claims is turned away before a certificate is even shown.
server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    ssl_reject_handshake on;
}
EOF
```

**2. The "only Cloudflare may talk to me" settings** (`cloudflare-origin.conf`), included by every app:

```sh
sudo tee /etc/nginx/snippets/cloudflare-origin.conf > /dev/null <<'EOF'
# Certificate for *.example.com (the Cloudflare Origin Certificate from A4).
ssl_certificate         /etc/ssl/cloudflare/origin.pem;
ssl_certificate_key     /etc/ssl/cloudflare/origin.key;

# Authenticated Origin Pulls: refuse anyone who cannot show Cloudflare's client certificate.
ssl_client_certificate  /etc/ssl/cloudflare/origin-pull-ca.pem;
ssl_verify_client on;

# Use the visitor's real IP address (trusted only when it comes from a Cloudflare address).
include /etc/nginx/cloudflare-realip.conf;
real_ip_header CF-Connecting-IP;
EOF
```

**3. How requests are passed to the backend** (`proxy.conf`), included inside each `location`:

```sh
sudo tee /etc/nginx/snippets/proxy.conf > /dev/null <<'EOF'
proxy_http_version 1.1;
proxy_set_header Host $host;
# Overwrite (never append) so a visitor cannot fake their address.
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto https;
proxy_set_header X-Forwarded-Host $host;
EOF
```

**4. The script that keeps Cloudflare's address list up to date.** It writes the list for nginx and, in the other mode, adds firewall rules. It never enables the firewall and never touches your SSH rule, so it cannot lock you out.

```sh
sudo tee /usr/local/sbin/cloudflare-ips.sh > /dev/null <<'EOF'
#!/usr/bin/env bash
# Keeps the VPS in step with Cloudflare's published IP ranges (https://www.cloudflare.com/ips/).
#   cloudflare-ips.sh nginx   write /etc/nginx/cloudflare-realip.conf (set_real_ip_from lines), test, reload
#   cloudflare-ips.sh ufw     add "allow 443 from <range>" firewall rules for every range
# It only adds ufw rules. If Cloudflare drops a range, remove the stale rule by hand (ufw status numbered).
set -euo pipefail

ranges() {
  curl -fsS https://www.cloudflare.com/ips-v4
  echo
  curl -fsS https://www.cloudflare.com/ips-v6
  echo
}

case "${1:-}" in
  nginx)
    out=/etc/nginx/cloudflare-realip.conf
    { echo "# Generated by cloudflare-ips.sh on $(date -u +%F)"; ranges | grep . | sed 's/.*/set_real_ip_from &;/'; } > "$out"
    nginx -t && systemctl reload nginx
    ;;
  ufw)
    ranges | grep . | while read -r cidr; do
      ufw allow proto tcp from "$cidr" to any port 443 comment 'cloudflare'
    done
    ;;
  *)
    echo "usage: $0 nginx|ufw" >&2
    exit 1
    ;;
esac
EOF
sudo chmod 755 /usr/local/sbin/cloudflare-ips.sh
sudo cloudflare-ips.sh nginx
```

**Check:** `sudo nginx -t` says *syntax is ok / test is successful*, and `/etc/nginx/cloudflare-realip.conf` contains about 22 `set_real_ip_from` lines.

### A6. Firewall

Right now your server answers anyone who finds its IP. The firewall will drop everyone except Cloudflare (for port 443) and SSH.

> **Danger zone:** a wrong firewall rule can lock you out. Keep your current SSH window open the whole time, and after enabling the firewall open a **second** SSH window to prove you can still log in before closing the first. If you do get locked out, most providers have a web "console" or "rescue" button.

**On the VPS**, in this order:

```sh
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo cloudflare-ips.sh ufw
sudo ufw enable
sudo ufw status numbered
```

(If you moved SSH off port 22, allow that port instead of `OpenSSH`.)

> **Docker and the firewall:** Docker edits the server's network rules itself, and a port that Docker *publishes* can bypass ufw completely. That is why every app in this guide publishes its port on `127.0.0.1` only (`"127.0.0.1:8081:8080"`) and databases publish nothing. Never change one to a plain `"8081:8080"`.

**Check:**

1. A second SSH window still logs in.
2. `sudo ss -tlnp`: in the *Local Address* column only SSH (port 22) and nginx (port 443) may show `0.0.0.0` or `[::]`. Everything else shows `127.0.0.1`.

### A7. The deploy user and the deploy script (once)

GitHub will update your apps by logging in over SSH. We give it a **separate, powerless user** and a script that can only do one thing: pull one app's newest image and restart it.

**On the VPS:**

```sh
# 1) a user that exists only to receive deploy commands (no password, key login only)
sudo adduser --disabled-password --gecos "" deploy

# 2) the deploy script (owned by root, so the deploy user cannot change it)
sudo tee /usr/local/sbin/deploy-app > /dev/null <<'EOF'
#!/usr/bin/env bash
# Usage: deploy-app <app>
# Started by GitHub Actions over SSH, through a restricted key whose forced command is
# "sudo /usr/local/sbin/deploy-app <app>". Pulls the newest image of one app, restarts it
# and waits until it answers. Exits with an error (so the GitHub job turns red) if it does not.
set -euo pipefail

APPS_DIR=/srv

app="${1:-}"
if ! [[ "$app" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  echo "Usage: deploy-app <app-name>   (lowercase letters, digits and dashes)" >&2
  exit 1
fi

dir="$APPS_DIR/$app"
if [ ! -f "$dir/compose.yaml" ]; then
  echo "$dir/compose.yaml not found." >&2
  exit 1
fi

# Defaults. The optional file $dir/deploy.conf (owned by root) can override them.
SERVICE=api                  # the compose service that holds your backend
HEALTH_URL=""                # for example http://127.0.0.1:8081/health ; empty = only check that it runs
HEALTH_OK='^(200|401)$'      # HTTP status codes that mean "it is up"
HEALTH_WAIT=90               # seconds to wait
if [ -f "$dir/deploy.conf" ]; then
  # shellcheck disable=SC1091
  . "$dir/deploy.conf"
fi

cd "$dir"

echo "==> [$app] pulling the newest image"
docker compose pull "$SERVICE"

echo "==> [$app] restarting"
docker compose up -d "$SERVICE"

up=0
if [ -n "$HEALTH_URL" ]; then
  echo "==> [$app] waiting up to ${HEALTH_WAIT}s for $HEALTH_URL"
  deadline=$((SECONDS + HEALTH_WAIT))
  while [ "$SECONDS" -lt "$deadline" ]; do
    code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$HEALTH_URL" || true)
    if [[ "$code" =~ $HEALTH_OK ]]; then up=1; break; fi
    sleep 2
  done
else
  sleep 10
  if docker compose ps --status running --services | grep -qx "$SERVICE"; then up=1; fi
fi

if [ "$up" = 1 ]; then
  docker image prune -f > /dev/null
  docker compose ps
  echo "Deploy OK ($app)"
  exit 0
fi

echo "Deploy FAILED ($app): it did not become healthy. Most telling log lines:" >&2
docker compose logs --tail 300 "$SERVICE" 2>&1 | grep -E "Caused by|ERROR|FATAL|Error|panic|Traceback" | tail -6 >&2 || true
echo "--- Last log lines:" >&2
docker compose logs --tail 25 "$SERVICE" >&2
exit 1
EOF
sudo chmod 755 /usr/local/sbin/deploy-app
```

**Check:** `sudo deploy-app nothing-here` answers `/srv/nothing-here/compose.yaml not found.` (that proves the script runs). The SSH keys and the permission to run it are added per app in B6.

### A8. Harden SSH (recommended, do it last)

Once key login works (A2) and everything else runs, turn off password logins and direct `root` logins. **Keep your current SSH window open** while you test.

**On the VPS:**

```sh
sudo tee /etc/ssh/sshd_config.d/10-hardening.conf > /dev/null <<'EOF'
PasswordAuthentication no
PermitRootLogin no
EOF
sudo sshd -t && sudo systemctl reload ssh
sudo sshd -T | grep -iE "passwordauthentication|permitrootlogin"     # both should say "no"
```

The file is named `10-...` on purpose: it is read before Ubuntu's default `50-cloud-init.conf`, and the first value wins. **Check:** open a **new** terminal and `ssh <you>@<VPS_IP>`: it must still log you in. The `deploy` user (key only) is not affected.

---

## 3. Part B: add a backend (repeat for every backend)

Everything in this part is per app. Do the steps in order. A quick checklist version is at the end of the guide.

### B1. Make the backend container-ready

Any language works. The deployment only needs your backend to follow these rules:

| Rule | Why |
| --- | --- |
| **Listens on `0.0.0.0` inside the container** on a fixed port (for example 8080), configurable by an environment variable. | Inside a container, "localhost only" would make it unreachable. The *host* side is restricted to `127.0.0.1` by the compose file. |
| **All settings come from environment variables** (database URL, secrets, allowed origin). No secrets in the repository. | The same image runs anywhere. Secrets live only in a file on the server. |
| **Fails fast on missing or weak settings** (for example a too-short signing secret). | A crash at start with a clear message beats a half-working app. |
| **Logs to stdout/stderr**, not to files. | `docker compose logs` shows them. |
| **Has a cheap URL that answers when it is healthy**, for example `GET /health` returning 200 (or a URL with a known status such as 401). | The deploy script waits for it, so a broken release turns the GitHub job red. |
| **Runs database migrations itself at start** (Flyway, Alembic, Prisma, ...). | A new release can change the schema without manual steps. |
| **Behind a proxy:** trusts `X-Forwarded-For` / `X-Forwarded-Proto` from nginx. | Correct client IP and `https` detection. Only safe because nothing but nginx can reach the port. |
| **CORS** (if a website on another hostname calls it): allow **exactly** the website's address (`https://shop.example.com`: `https`, no trailing slash), allow credentials if you use cookies, and answer the `OPTIONS` pre-check. | A wrong or missing value makes the browser refuse every call. An `http://` page has a *different* origin and is refused too. |
| **Cookies for login:** `HttpOnly`, `Secure`, `SameSite=Lax`. And a CSRF defence (for example require a custom header like `X-Requested-By` on non-GET requests). | Protects the session. Needs website and API under the same domain. |

**The Dockerfile.** A multi-stage build keeps the final image small: the first stage builds, the second only runs. Example for a Spring Boot (Java) app that was tested end to end:

```dockerfile
# syntax=docker/dockerfile:1

# ---- build stage: full JDK + Gradle, thrown away afterwards ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Copy only the build files first, so downloaded libraries are cached as their own layer.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sh gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN sh gradlew --no-daemon bootJar -x test && rm -f build/libs/*-plain.jar

# ---- runtime stage: only a JRE and the jar ----
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
```

And a sketch for a Node.js app (adapt the build and start commands):

```dockerfile
FROM node:24-alpine AS build
WORKDIR /src
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /src/package*.json ./
RUN npm ci --omit=dev
COPY --from=build /src/dist ./dist
USER node
EXPOSE 8080
CMD ["node", "dist/index.js"]
```

Also add a `.dockerignore` next to the Dockerfile (for example `build/`, `node_modules/`, `.git`, `.gradle/`) so those folders are not copied into the build.

> **Windows tip:** files that run on Linux need Unix line endings. Add a `.gitattributes` to the repo containing `Dockerfile text eol=lf` and `*.sh text eol=lf`. If a Gradle project's `gradlew` loses its "executable" flag when committed from Windows, call it as `sh gradlew` (as above) instead of `./gradlew`.

### B2. Pick the names

Choose `<app>`, the API hostname (`<app>-api.example.com`) and a free `<port>`. Add a row to your table (see section 1). From here on, replace `<app>` and `<port>` everywhere.

### B3. DNS record

Cloudflare dashboard, your domain, **DNS → Records → Add record**:

- Type `A`, Name `<app>-api`, IPv4 `<VPS_IP>`, Proxy status **Proxied** (orange cloud ON).

The orange cloud is what sends traffic through Cloudflare and hides your real IP. (All your apps use the same IP, only the name differs.)

### B4. The nginx file for the app

**On the VPS**, create one small file per app:

```sh
sudo tee /etc/nginx/conf.d/<app>.conf > /dev/null <<'EOF'
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name <app>-api.example.com;

    include /etc/nginx/snippets/cloudflare-origin.conf;

    # Optional: a stricter rate limit for login-like endpoints (adapt the path or drop this block).
    location /api/auth/ {
        limit_req zone=login burst=10 nodelay;
        limit_req_status 429;
        proxy_pass http://127.0.0.1:<port>;
        include /etc/nginx/snippets/proxy.conf;
    }

    location / {
        proxy_pass http://127.0.0.1:<port>;
        include /etc/nginx/snippets/proxy.conf;
    }
}
EOF
sudo nginx -t && sudo systemctl reload nginx
```

(Replace `<app>`, `<port>` and `example.com` before running. The quoted `'EOF'` keeps nginx's `$` signs as they are.)

**Check:** `nginx -t` says *test is successful*.

### B5. The app's folder on the VPS

Each app has its own folder in `/srv` with three files: the compose file (what to run), `.env` (secrets and settings), and optionally `deploy.conf` (health check for the deploy script).

**On the VPS:**

```sh
sudo install -d /srv/<app>
sudo nano /srv/<app>/compose.yaml
```

Paste and adapt this template. The **bold ideas**: the database is reachable only by the app; the app's port is published on `127.0.0.1` only; the database healthcheck checks over TCP; both restart by themselves.

```yaml
# /srv/<app>/compose.yaml
services:
  db:
    image: postgres:17-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: app
      POSTGRES_USER: app
      POSTGRES_PASSWORD: ${DB_PASSWORD:?Set DB_PASSWORD in /srv/<app>/.env}
    volumes:
      - db-data:/var/lib/postgresql/data
    healthcheck:
      # Checks over TCP on purpose: while Postgres initialises for the first time it runs a temporary
      # server that only listens on a socket, and a socket check would say "healthy too early".
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U app -d app"]
      interval: 5s
      timeout: 3s
      retries: 20
    logging: &logging
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
    # No `ports:` on purpose: only the api container can reach the database.

  api:
    image: ghcr.io/<github-user>/<app>:${IMAGE_TAG:-latest}
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
    env_file: .env
    environment:
      # ADAPT THESE to the names YOUR app reads. They make it listen on all addresses *inside* the
      # container and point it at the database container (host name "db").
      SERVER_ADDRESS: 0.0.0.0
      DB_URL: jdbc:postgresql://db:5432/app
      DB_USER: app
    ports:
      - "127.0.0.1:<port>:8080"      # host port <port> on loopback only -> the app's port 8080
    read_only: true
    tmpfs:
      - /tmp
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    mem_limit: 1g                     # protects the other apps; check real use with: docker stats
    logging: *logging

volumes:
  db-data:
```

Now the settings file (the names after `DB_PASSWORD` are whatever your app reads):

```sh
sudo nano /srv/<app>/.env
sudo chmod 600 /srv/<app>/.env
```

```sh
# /srv/<app>/.env       (root only; never in git)
DB_PASSWORD=<paste the output of: openssl rand -hex 24>
# ... your app's own settings, for example:
# JWT_SECRET=<openssl rand -hex 32>
# CORS_ALLOWED_ORIGIN=https://<app>.example.com
# Optional: run a specific version instead of the newest (for rollbacks, see D4)
# IMAGE_TAG=sha-1a2b3c4
```

Use letters and digits for passwords: some symbols (like `$`) get mangled in this file.

And the health settings for the deploy script, so a broken release turns the GitHub job red:

```sh
sudo tee /srv/<app>/deploy.conf > /dev/null <<'EOF'
# Read by /usr/local/sbin/deploy-app (it runs as root, so this file must be owned by root).
HEALTH_URL=http://127.0.0.1:<port>/health
HEALTH_OK='^200$'           # for an endpoint that answers 401 when it is up, use '^(200|401)$'
HEALTH_WAIT=90
EOF
```

(Leave `HEALTH_URL` empty if your app has no such URL. The script then only checks that the container keeps running.)

**You cannot start the app yet:** the image does not exist until GitHub has built it (B6). Continue with B6.

### B6. GitHub: build the image, then deploy it

**1. The workflow file.** In your repository create `.github/workflows/backend.yml` (adapt the marked lines):

```yaml
name: Backend

# On every change to the backend: run the tests. On main, also build the Docker image, publish it to
# GitHub's container registry and tell the VPS to start using it.
on:
  push:
    branches: [main]
    paths:
      - "backend/**"                        # <- the folder that holds your backend
      - ".github/workflows/backend.yml"
  pull_request:
    paths:
      - "backend/**"
      - ".github/workflows/backend.yml"

permissions:
  contents: read

concurrency:
  group: backend-${{ github.ref }}
  # Only pull request runs may be cancelled by a newer one. A run on main must never be cancelled
  # halfway, because it might be in the middle of updating the server.
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

env:
  IMAGE: ghcr.io/<github-user>/<app>        # <- lowercase!

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      # <- replace with your language's setup and your test command, for example:
      # - uses: actions/setup-node@v6
      # - run: npm ci && npm test
      - run: echo "run your tests here"

  image:
    needs: test
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write # lets the built-in token publish to ghcr.io
    steps:
      - uses: actions/checkout@v7
      - uses: docker/setup-buildx-action@v4
      - uses: docker/login-action@v4
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - id: meta
        uses: docker/metadata-action@v6
        with:
          images: ${{ env.IMAGE }}
          # Two tags per build: `latest`, and `sha-<first 7 characters of the commit>` for rollbacks.
          tags: |
            type=raw,value=latest
            type=sha
      - uses: docker/build-push-action@v7
        with:
          context: backend                  # <- the folder with your Dockerfile
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          # amd64 is what almost every VPS uses. For an ARM server: linux/amd64,linux/arm64
          platforms: linux/amd64
          cache-from: type=gha
          cache-to: type=gha,mode=max

  # Tells the VPS to start using the image that was just published. It logs in over SSH with a key that
  # can only run "deploy-app <app>". Until the three DEPLOY_* secrets exist this job does nothing, so
  # pushing before the server is ready is fine.
  deploy:
    needs: image
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - id: cfg
        env:
          DEPLOY_HOST: ${{ secrets.DEPLOY_HOST }}
        run: |
          if [ -n "$DEPLOY_HOST" ]; then
            echo "enabled=true" >> "$GITHUB_OUTPUT"
          else
            echo "The DEPLOY_* secrets are not set, so the server is not updated automatically."
          fi
      - if: steps.cfg.outputs.enabled == 'true'
        env:
          DEPLOY_HOST: ${{ secrets.DEPLOY_HOST }}
          DEPLOY_HOST_KEY: ${{ secrets.DEPLOY_HOST_KEY }}
          DEPLOY_SSH_KEY: ${{ secrets.DEPLOY_SSH_KEY }}
        run: |
          install -m 700 -d ~/.ssh
          printf '%s\n' "$DEPLOY_SSH_KEY" > ~/.ssh/deploy_key
          chmod 600 ~/.ssh/deploy_key
          # Pin the server's identity so nobody can pose as it.
          printf '%s %s\n' "$DEPLOY_HOST" "$DEPLOY_HOST_KEY" > ~/.ssh/known_hosts
          # The key on the server is restricted: whatever command we send, it only runs deploy-app <app>.
          ssh -T -i ~/.ssh/deploy_key -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
            -o ConnectTimeout=20 "deploy@$DEPLOY_HOST"
```

The `uses:` versions are the current majors at the time of writing. Check the *Marketplace* page of each action for newer ones now and then.

Commit and push it. On github.com open the **Actions** tab: the **Backend** run starts. **Check:** the **test** and **image** jobs turn green (the **deploy** job says it is doing nothing, which is correct for now).

**2. Let the VPS download the image.** GitHub makes a new image **private**. Choose one:

- **Public image (simplest, fine if the code is public or contains nothing secret):** on github.com click your profile picture, **Your profile**, **Packages** tab, open `<app>`, **Package settings**, scroll to **Danger Zone**, **Change visibility**, **Public**. The VPS then pulls without logging in.
- **Private image:** on the VPS, as root, log in once with a token: create a GitHub *personal access token (classic)* with only the `read:packages` scope, then `sudo docker login ghcr.io -u <github-user>` and paste the token as the password. The deploy script runs as root, so it uses that login.

**Check, on the VPS:** `sudo docker pull ghcr.io/<github-user>/<app>:latest` finishes without an error.

**3. Start the app for the first time.** **On the VPS:**

```sh
cd /srv/<app>
sudo docker compose up -d
sudo docker compose ps                    # db: (healthy), api: Up, api shows 127.0.0.1:<port>->8080
sudo docker compose logs --tail 40 api    # read what your app says
curl -i http://127.0.0.1:<port>/health    # use your health URL
```

> All `docker compose` commands must be run **inside** `/srv/<app>` (that is where `compose.yaml` is) and need `sudo`.

**4. The automatic deploy: one key per app.** Still **on the VPS**:

```sh
# a) a new key pair just for this app (the empty "" means no passphrase; GitHub cannot type one)
ssh-keygen -t ed25519 -N "" -C "github-deploy-<app>" -f ~/gh_deploy_<app>

# b) allow that key for the deploy user, restricted to ONE command: deploy-app <app>
sudo install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
echo "restrict,command=\"sudo /usr/local/sbin/deploy-app <app>\" $(cat ~/gh_deploy_<app>.pub)" | sudo tee -a /home/deploy/.ssh/authorized_keys > /dev/null
sudo chown deploy:deploy /home/deploy/.ssh/authorized_keys
sudo chmod 600 /home/deploy/.ssh/authorized_keys

# c) allow the deploy user to run that one command as root, and nothing else (checked before it is installed)
echo 'deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-app <app>' > /tmp/sudoers-<app>
sudo visudo -cf /tmp/sudoers-<app> && sudo install -m 440 /tmp/sudoers-<app> /etc/sudoers.d/deploy-<app>
rm /tmp/sudoers-<app>
```

The `restrict` option removes everything else (no shell, no port forwarding). So this key can only ever start `deploy-app <app>`, and `sudo` only allows exactly that command. A leaked key could do nothing but redeploy your own published image of this one app. (`tee -a` *adds* a line, so each app gets its own key line in the same file.)

**Check:** try the key on the server itself, the same way GitHub will use it:

```sh
ssh -T -i ~/gh_deploy_<app> -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new deploy@127.0.0.1
```

You should see the deploy steps ending with `Deploy OK (<app>)`. Whatever command you add after `deploy@127.0.0.1`, only that script runs.

**5. Put three secrets into GitHub.** First show the two values you need, **on the VPS**:

```sh
cat ~/gh_deploy_<app>                                 # the PRIVATE key: everything from -----BEGIN to -----END
cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub     # the server's identity: one line starting with ssh-ed25519
```

On github.com open the repository: **Settings → Secrets and variables → Actions → New repository secret**. Create (names exactly as written):

| Name | Value |
| --- | --- |
| `DEPLOY_HOST` | your VPS IP address. It is a *secret* (not in the workflow file) because Cloudflare hides that address and the repository may be public. |
| `DEPLOY_SSH_KEY` | the private key from `cat`: copy **all** lines, including `-----BEGIN` and `-----END` |
| `DEPLOY_HOST_KEY` | the one line from `cut`, starting with `ssh-ed25519` (lets GitHub check it talks to your real server) |

Then delete the private key from the server (GitHub keeps its copy): `rm ~/gh_deploy_<app> ~/gh_deploy_<app>.pub`. Never paste these values anywhere else.

> **Several apps in one repository?** Use secret names with a suffix (`DEPLOY_SSH_KEY_SHOP`, ...) and one deploy job per app. **Several repositories?** Each repository gets its own `DEPLOY_SSH_KEY` (its own app's key); `DEPLOY_HOST` and `DEPLOY_HOST_KEY` have the same values everywhere.

### B7. Test the whole chain

1. On github.com: **Actions → the latest Backend run → Re-run all jobs** (or push a small backend change).
2. **Check:** all three jobs are green. Open **deploy**: the log shows the steps ending with `Deploy OK (<app>)`.
3. **Check, through Cloudflare, from your PC:**

```powershell
curl.exe -i https://<app>-api.example.com/health
```

You should get your app's answer. (Wait a minute or two after creating the DNS record.) In PowerShell you must type `curl.exe`; plain `curl` is a different command there.

4. **Check that going around Cloudflare is refused:**

```powershell
curl.exe -ik --resolve <app>-api.example.com:443:<VPS_IP> https://<app>-api.example.com/health
```

It should say **`400 No required SSL certificate was sent`**: nginx refuses visitors without Cloudflare's ID card.

From now on, **`git push` to `main` is your backend deploy.**

---

## 4. Part C: add a frontend (repeat for every frontend)

A frontend here is a **static website**: files that a browser runs (React, Svelte, Vue, plain HTML, ... any framework that can output static files). Cloudflare builds it from GitHub and serves it from its network, for free. No VPS involved.

### C1. Make the frontend deploy-ready

- **It builds to a folder of static files** (for example `build/` or `dist/`).
- **It is a single-page app?** Then unknown paths (like `/login` after a refresh) must open `index.html`. The config below does that.
- **The API address is a build-time setting**, never hard-coded. In Vite-based projects, any variable starting with `VITE_` is baked into the site while it is built:

```js
const API = (import.meta.env.VITE_API_URL ?? '').replace(/\/$/, '');
fetch(`${API}/api/something`, { credentials: 'include' });   // include = send the login cookie
```

  Empty in local development (a dev-server proxy forwards `/api` to your local backend), the real API address in production.
- **Do not put secrets in the frontend.** Everything inside a website can be downloaded by anyone. An API *address* is fine.
- **Never let the very first API call crash the whole app.** If the "who am I" request fails (API down, network, blocked), show the login page with a message like "Could not reach the server", not a blank error page.

In the frontend project create `wrangler.jsonc` (the file Cloudflare's deploy command reads):

```jsonc
{
  "$schema": "node_modules/wrangler/config-schema.json",
  // Static assets only, there is no Worker script.
  "name": "<frontend-name>",              // must equal the "Project name" you type in Cloudflare (C2)
  "compatibility_date": "2026-10-01",     // any recent date
  // Login cookies only work when the site and the API share a domain, so the free *.workers.dev
  // address is switched off and your own domain is used instead.
  "workers_dev": false,
  "routes": [
    { "pattern": "<app>.example.com", "custom_domain": true }   // Cloudflare creates the DNS record for you
  ],
  "assets": {
    "directory": "./build",               // <- the folder your build produces
    "not_found_handling": "single-page-application"
  }
}
```

Put `wrangler` in the project's dev dependencies if the deploy command needs it (`npm i -D wrangler` or `pnpm add -D wrangler`). If the DNS record `<app>` already exists in the Cloudflare DNS tab, delete it first: `custom_domain` creates its own.

Commit and push this file before C2.

### C2. Connect the repository in Cloudflare

Cloudflare dashboard → **Workers & Pages → Create → import a repository** (connect to Git). Log in to GitHub when asked and allow access to the repository. You get a form **Set up your application**. Fill it in (match by meaning; Cloudflare changes the wording now and then):

| Field | What to put |
| --- | --- |
| **Project name** | `<frontend-name>`, exactly the `name` in `wrangler.jsonc` |
| **Build command** | your build, for example `npm run build` or `pnpm build`. The box starts **empty**. If you leave it empty nothing is built and the deploy fails. |
| **Deploy command** | `npx wrangler deploy` (already filled in) |
| **Preview command / Enable Preview builds** | leave the command, switch **Enable Preview builds** **off** (only `main` deploys) |
| **Protect with Cloudflare Access** | keep **off**, or every visitor needs a Cloudflare login |
| **Advanced settings → Path** | the folder inside the repository where the frontend lives (`frontend`), or `/` if the whole repository is the frontend. It starts as `/`. |
| **API token** | leave on **Create new token** (it lets Cloudflare's build robot publish the site) |
| **Variable name / Variable value** | the API address: name `VITE_API_URL`, value `https://<app>-api.example.com` (no slash at the end). **Not** encrypted: it is not a secret. This is a **build** variable. If you change it later you must start a new build. |

Cloudflare's build machine has a recent Node and pnpm. If you need a specific Node version, add the build variable `NODE_VERSION` (for example `24`).

Save and deploy.

### C3. Test

1. **Check:** the build log in Cloudflare ends with a successful deploy.
2. **Check:** `https://<app>.example.com` opens your site, and `curl.exe -I http://<app>.example.com` answers `301`/`308` with a `Location: https://...` line (that is the *Always Use HTTPS* switch from A4).
3. **Check:** an action that calls the API works (for example log in).

From now on, **`git push` to `main` is your frontend deploy.**

---

## 5. Part D: daily life

### D1. Deploying

Push to `main`. Backends: tests, image, server update (watch the **Actions** tab). Frontends: Cloudflare builds (watch the build log in its dashboard). Nothing else to do.

### D2. Looking at what happens

On the VPS, inside `/srv/<app>`:

```sh
sudo docker compose ps                       # what runs
sudo docker compose logs --tail 100 api      # the app's log (add -f to follow live)
sudo docker compose logs --tail 50 db        # the database's log
sudo docker stats --no-stream                # memory and CPU of every container
sudo tail -n 50 /var/log/nginx/error.log     # nginx errors
```

### D3. Changing an app's settings

Edit `/srv/<app>/.env`, then apply it. **`restart` alone does not re-read the file**; recreate the container:

```sh
cd /srv/<app> && sudo docker compose up -d --force-recreate api
```

Changing `compose.yaml` (new ports or services) is applied with `sudo docker compose up -d`. The automatic deploy deliberately touches **only the image**, never the compose file or `.env`. If a release needs a new setting, add it to `.env` **before** you push.

### D4. Rolling back

Every build has a tag like `sha-1a2b3c4`. Find the last good one on GitHub (your profile → **Packages** → the app → versions). Then:

```sh
sudo nano /srv/<app>/.env                    # add the line: IMAGE_TAG=sha-1a2b3c4
cd /srv/<app> && sudo docker compose up -d
```

While `IMAGE_TAG` is set, automatic deploys also pull that pinned version, so nothing changes until you delete the line (then run `up -d` or push again).

### D5. Backups

The database lives only on the VPS, in a Docker volume. A backup you can run by hand, from `/srv/<app>`:

```sh
sudo docker compose exec -T db pg_dump -U app app | gzip > ~/<app>-$(date +%F).sql.gz
```

Copy it to your PC (`scp <you>@<VPS_IP>:<app>-2026-01-01.sql.gz .`). To automate it, add a line with `sudo crontab -e` that dumps every night at 03:00 and keeps 14 days:

```
0 3 * * * cd /srv/<app> && docker compose exec -T db pg_dump -U app app | gzip > /var/backups/<app>-$(date +\%F).sql.gz && find /var/backups -name '<app>-*.sql.gz' -mtime +14 -delete
```

(In a crontab a `%` must be written `\%`.) Test a restore on a spare machine now and then: a backup you never restored is a hope, not a backup. And a warning: `docker compose down` is safe, but **`down -v` deletes the database volume**. Never add `-v` on the server.

### D6. Keeping it healthy

- **Cloudflare's IP list changes now and then.** Every month or so run `sudo cloudflare-ips.sh nginx` and `sudo cloudflare-ips.sh ufw`. The script only *adds* firewall rules: list them with `sudo ufw status numbered` and remove stale ones with `sudo ufw delete <number>`.
- **Server updates:** `sudo apt update && sudo apt upgrade -y` regularly, or `sudo apt install unattended-upgrades` for automatic security updates.
- **Disk space:** old Docker images pile up. The deploy script removes unused ones each time; check with `df -h` and `sudo docker system df`.
- **Certificates:** the Cloudflare origin certificate (15 years) and the Authenticated Origin Pulls CA do not need renewing for a long time. Cloudflare renews the website certificates by itself.

### D7. Removing an app

```sh
cd /srv/<app> && sudo docker compose down          # stops it (add -v only if you really want to delete the data)
sudo rm /etc/nginx/conf.d/<app>.conf && sudo nginx -t && sudo systemctl reload nginx
sudo rm /etc/sudoers.d/deploy-<app>
sudo nano /home/deploy/.ssh/authorized_keys        # delete the line that ends in github-deploy-<app>
```

Then delete the DNS record in Cloudflare and the `DEPLOY_*` secrets in GitHub. Keep or delete `/srv/<app>` as you like.

---

## 6. Part E: when something goes wrong

**First, find out *which layer* is failing.** Test from the inside out; the first test that fails points at the culprit:

| # | Test | Where | Healthy answer | If it fails |
| --- | --- | --- | --- | --- |
| 1 | `sudo docker compose ps` | VPS, in `/srv/<app>` | `db` healthy, `api` Up | the container problem (see below) |
| 2 | `curl -i http://127.0.0.1:<port>/health` | VPS | your app's answer | the app itself |
| 3 | `curl.exe -i https://<app>-api.example.com/health` | your PC | your app's answer | nginx, firewall, Cloudflare, DNS |
| 4 | `curl.exe -i -H "Origin: https://<app>.example.com" https://<app>-api.example.com/health` | your PC | answer **with** an `Access-Control-Allow-Origin` header | CORS setting of the app |
| 5 | open the website in a **private window** | your PC | works | something stored in your browser (cookies, cache, an extension) |

### Cloudflare error codes (from step 3)

| What you see | Likely reason |
| --- | --- |
| `Could not resolve host` | DNS record missing or not spread yet. Wait a few minutes, check B3. |
| **521** | nginx is not running or nothing answers on 443. `sudo systemctl status nginx`. |
| **525** / **526** | Certificate trouble: wrong files in `/etc/ssl/cloudflare/`, the hostname is not covered by the certificate, **or no nginx file claims this hostname** (the default server turns it away; check `server_name` in B4 and that you reloaded nginx). |
| **502 Bad Gateway** | nginx works but the app is down or on another port. Check test 1 and 2, and the `<port>` in the nginx file against the compose file. |
| **400 No required SSL certificate** even through Cloudflare | The *Authenticated Origin Pulls* switch (A4) is not on. |
| **429** | You hit the nginx rate limit (B4). |

### Docker and the deploy

| Problem | Likely reason and fix |
| --- | --- |
| `no configuration file provided` | You are not inside `/srv/<app>`. |
| `required variable DB_PASSWORD is missing` | `/srv/<app>/.env` is missing or lacks that line. |
| `permission denied while trying to connect to the Docker daemon` | You forgot `sudo`. |
| `pull access denied`, `unauthorized`, `manifest unknown` | The image is still private (B6 step 2), the first image job never succeeded, or the name differs between the workflow, compose file and registry (lowercase!). |
| The `api` container keeps restarting | Read `sudo docker compose logs --tail 50 api`. Usual causes: a missing or too-weak setting in `.env`, or a wrong database address. |
| `password authentication failed` for the database | Postgres reads `DB_PASSWORD` only the **first** time it creates its data. If you changed it later, put the old one back, or set the new one inside: `sudo docker compose exec db psql -U app -d app -c "ALTER USER app WITH PASSWORD 'NEW';"` then `up -d --force-recreate api`. |
| Port already in use / the app is unreachable | Two apps share a `<port>`. Give each app its own (section 1). |
| An app is killed or the server is sluggish | Out of memory. `sudo docker stats`; lower `mem_limit` of the others, reduce the app's memory settings, or add a swap file: `sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile && echo '/swapfile none swap sw 0 0' \| sudo tee -a /etc/fstab`. |
| The server's disk fills up | `sudo docker image prune -f`, check `df -h` and `sudo docker system df`. |

### GitHub Actions

| Problem | Likely reason and fix |
| --- | --- |
| The **test** job is red | A test failed. Open the run and read which. |
| The **image** job fails with a permission or 403 error | Repository **Settings → Actions → General → Workflow permissions → Read and write permissions**, then re-run. |
| The **deploy** job says `Permission denied (publickey)` | The key is not accepted. `DEPLOY_SSH_KEY` must contain **all** lines of the private key and match the public key in `/home/deploy/.ssh/authorized_keys`. That folder must be `700` and the file `600`, owned by `deploy`. |
| `Host key verification failed` | `DEPLOY_HOST_KEY` is wrong, or the server was reinstalled. Recreate the secret with `cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub`. |
| `Connection timed out` | `DEPLOY_HOST` is wrong, or SSH is blocked (for example limited to your own IP; GitHub's machines have changing addresses). |
| `sudo: a password is required` / `not allowed` | The sudoers rule (B6 step 4c) is missing or has a typo. `sudo -l -U deploy` should list `/usr/local/sbin/deploy-app <app>`. |
| The **deploy** job is red with `Deploy FAILED` | The new version did not become healthy. The job log shows the most telling error lines. Typical cause: a new setting that `.env` does not have yet. Fix it (D3) or roll back (D4). |
| The deploy job passes but says the secrets are not set | Intended until you finish B6 step 5. |

### The website (Cloudflare Workers)

| Problem | Likely reason and fix |
| --- | --- |
| Build: the name does not match | The project name in Cloudflare must equal `name` in `wrangler.jsonc`. |
| Build or deploy fails right away | Is the **Build command** empty, or **Path** wrong (C2)? |
| Deploy step fails at the custom domain | Delete a leftover DNS record with the same name, or remove the `routes` block, push, and add the domain by hand under the Worker's **Settings → Domains & Routes**. |
| The website calls `localhost` or the wrong API | `VITE_API_URL` was missing or wrong **at build time**. Fix the build variable and start a new build. |
| "Could not reach the server" / a console CORS error | Test 4 above. The allowed origin in the backend must be exactly the website's `https://...` address. |
| Works with `https://` but breaks without it (for example from a chat link) | **Always Use HTTPS** is off (A4 step 2). |
| Login seems to work, then you are logged out | The cookie is not kept: website and API must both be `https` and under the same domain. |

---

## 7. Checklists

### New backend

- [ ] Backend follows the rules in B1; `Dockerfile` and `.dockerignore` committed
- [ ] Name, hostname, free port written in your table
- [ ] Cloudflare DNS: `A` record `<app>-api`, **proxied**
- [ ] nginx: `/etc/nginx/conf.d/<app>.conf`, `nginx -t`, reload
- [ ] VPS: `/srv/<app>/compose.yaml`, `.env` (chmod 600), `deploy.conf`
- [ ] GitHub: workflow pushed, **test** and **image** jobs green
- [ ] Image made public (or `docker login ghcr.io` on the VPS)
- [ ] `cd /srv/<app> && sudo docker compose up -d`, health URL answers on `127.0.0.1:<port>`
- [ ] Deploy key for this app, `authorized_keys` line with the forced command, sudoers file
- [ ] GitHub secrets `DEPLOY_HOST`, `DEPLOY_SSH_KEY`, `DEPLOY_HOST_KEY`; private key deleted from the VPS
- [ ] Re-run the workflow: **deploy** job green; `curl.exe` through Cloudflare works; the direct-to-IP test says `400 No required SSL certificate`

### New frontend

- [ ] Builds to static files; API address read from a build-time variable; first API call cannot crash the app
- [ ] `wrangler.jsonc` committed (name, `assets.directory`, custom domain route, `workers_dev: false`)
- [ ] Cloudflare: repository connected, **Build command** filled in, **Path** set, `VITE_API_URL` build variable set
- [ ] Site opens on `https://<app>.example.com`; plain `http://` redirects; login/API call works

### New server (once)

- [ ] A2 user + SSH key · A3 packages · A4 Cloudflare (Full strict, Always Use HTTPS, origin certificate, Authenticated Origin Pulls)
- [ ] A5 nginx basics · A6 firewall · A7 deploy user and `deploy-app` · A8 SSH hardening

---

## 8. Glossary

- **VPS**: a rented always-on computer you control remotely.
- **SSH**: the secure remote terminal used to control the VPS. **SSH key**: a pair of files used instead of a password (the private half stays secret, the public half goes on the server).
- **DNS record**: an entry that says "this name points to this IP address". **Proxy (orange cloud)**: traffic goes through Cloudflare first, which hides your server's address.
- **Reverse proxy (nginx)**: a program that receives requests and passes them to another program, chosen by hostname.
- **TLS / certificate**: what makes `https` work. **Origin**: your own server, as opposed to Cloudflare in front of it. **mTLS / Authenticated Origin Pulls**: the server also checks the visitor's certificate; here the "visitor" is Cloudflare.
- **Docker / image / container**: Docker runs programs in sealed boxes (containers). An image is the ready-made package a container starts from. **Registry (ghcr.io)**: a website that stores images. **Docker Compose**: starts and connects several containers from one file. **Volume**: a folder Docker keeps outside the container so the database survives a replacement.
- **Loopback / `127.0.0.1`**: "this computer only". A program listening there cannot be reached from the internet.
- **Environment file (`.env`)**: a file of `NAME=value` settings (like passwords) given to a program, kept out of the code.
- **GitHub Actions**: GitHub's built-in robot that runs tests, builds the image and starts the deploy whenever you push. **GitHub secret**: a value stored in the repository settings that workflows can use but nobody can read back.
- **Restricted (forced-command) key**: an SSH key set up on the server so it can run one fixed command and nothing else. **sudoers**: Linux's list of who may run which command as the admin.
- **CORS**: the browser's rule about which websites may call which APIs. **Preflight**: the browser's automatic `OPTIONS` pre-check before some requests.
- **Workers Builds**: Cloudflare's feature that builds and publishes your website from GitHub on every push.
