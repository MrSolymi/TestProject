# Deploying, step by step (beginner guide)

This guide takes the app from your PC to the internet:

- the **frontend** (the website) runs on Cloudflare, for free
- the **backend** (the API) runs on a small rented server (a VPS) that you control

Both are deployed **straight from your GitHub repository**: the server downloads the code and builds it, and Cloudflare builds the website by itself whenever you push.

Plan for about 1 to 2 hours the first time. Nothing here is hard, but there are many small steps, so go slowly and do the **Check** after each step before moving on.

> Written for a VPS running **Ubuntu 24.04** with about **2 GB of RAM**. If your VPS has a different system, the package names may differ.

---

## 1. The big picture

```
                    ┌───────────────────────── Cloudflare ─────────────────────────┐
 your browser ────► │  app.yourdomain.com  →  your static website (the SvelteKit app)  │
                    │  api.yourdomain.com  →  forwards to your VPS                    │
                    └───────────────────────────────┬───────────────────────────────┘
                                                    ▼
                          your VPS:  nginx (port 443)  →  Spring app (127.0.0.1:8080)  →  Postgres (127.0.0.1:5432)

 GitHub (your code) ──► the VPS downloads and builds the backend
                    └─► Cloudflare downloads and builds the website
```

What each piece is, in one line:

| Piece | What it does |
| --- | --- |
| **GitHub** | Stores your code. The VPS and Cloudflare both fetch it from there. |
| **VPS** | A computer you rent that is always on. You control it over SSH (a remote terminal). |
| **Domain** | Your own web address, like `yourdomain.com`. You need one (about $10 per year). |
| **Cloudflare** | Sits in front of everything. Hosts your website, and hides and protects your server. Free plan is enough. |
| **nginx** | The "front door" program on the VPS. It receives web traffic and hands it to the Spring app. Called a *reverse proxy*. |
| **Spring app** | Your backend (the `.jar` file). It only listens on the server itself (`127.0.0.1`), so nobody can reach it directly. |
| **Postgres** | The database. Also only listens on the server itself. |
| **systemd** | The part of Linux that starts your app on boot and restarts it if it crashes. |
| **ufw** | The firewall. We will tell it to only accept web traffic from Cloudflare. |

Security idea, from your friend's advice, in plain words: **only two programs may be reachable from the internet: SSH (so you can log in) and nginx (and nginx only talks to Cloudflare).** Everything else (Spring, Postgres) stays hidden on `127.0.0.1`. That is why we do it in this order: set everything up first, add the firewall last.

### Why two addresses (`app.` and `api.`)?

The login is stored in a cookie. Browsers only send that cookie if the website and the API are on the **same domain**. `app.yourdomain.com` and `api.yourdomain.com` count as the same site. A free `something.workers.dev` address for the website would **not** work for login, so you need your own domain.

---

## 2. Before you start

You need:

- [ ] **A domain name.** Buy one anywhere (Cloudflare itself sells them at cost). Then add it to Cloudflare: create a free account at cloudflare.com, click *Add a domain*, and follow the steps. Cloudflare tells you two *nameservers* to set at the place where you bought the domain. Wait until the Cloudflare dashboard says the domain is **Active**.
- [ ] **A VPS with Ubuntu 24.04**, with its **public IP address** (an address like `203.0.113.10`). You should be able to log in with SSH (your provider tells you how: usually `root` plus a password or key).
- [ ] **The project on GitHub.** See "First push" right below.

### First push to GitHub

The server and Cloudflare both fetch your code from GitHub, so it has to be there first. **On your PC**, in the project folder:

```powershell
git status
```

Read the list. It must **not** show any `.env` file (like `.env` or `.env.production`), nor folders named `node_modules`, `build` or `.gradle`, nor `.jar` files. The `.gitignore` files already hide them, so normally you only see your own source files. If the list looks right:

```powershell
git add .
git commit -m "First version"
git push -u origin main
```

Your repository is **public**, so everyone on the internet can read it. That is fine as long as no real password is in it, and there is none: the real secrets only ever live in a file on your server (`/etc/testproject/api.env`, step 7). One thing to know: `application-dev.properties` contains throwaway values for local development (a fake secret and the `admin` / `admin12345` account). They are harmless in git, but **never switch the `dev` profile on on the server**.

### Replace these placeholders everywhere

| Placeholder | Replace with | Example |
| --- | --- | --- |
| `yourdomain.com` | your real domain | `mycoolsite.dev` |
| `<VPS_IP>` | your VPS public IP | `203.0.113.10` |

The website will be `https://app.yourdomain.com` and the API will be `https://api.yourdomain.com`.

### Two kinds of terminals

Every command block below says where to run it:

- **On your PC** means PowerShell on Windows, in the project folder.
- **On the VPS** means in an SSH session (see next step).

Do not mix them up. Running a VPS command on your PC (or the other way) is the most common beginner mistake.

---

## 3. Log in to the VPS

**On your PC:**

```powershell
ssh root@<VPS_IP>
```

The first time it asks `Are you sure you want to continue connecting?`. Type `yes`. Then enter the password (nothing shows while you type, that is normal).

If your provider gave you a normal user instead of `root`, use that name. The commands below use `sudo` so they work for both.

**Check:** the prompt changes to something like `root@my-server:~#`. You are now typing on the VPS.

Handy things to know in the SSH window:

- **Paste:** right-click (Windows Terminal) or `Ctrl+Shift+V`.
- **Edit a file** with `nano`: `sudo nano /path/to/file`. Type or paste, then `Ctrl+O` then `Enter` to save, and `Ctrl+X` to exit.
- **Leave the SSH session:** `exit`.

Update the server first. **On the VPS:**

```sh
sudo apt update && sudo apt upgrade -y
```

---

## 4. Install the software on the VPS

**On the VPS:**

```sh
sudo apt install -y nginx postgresql openjdk-25-jdk-headless git ufw curl openssl
sudo rm -f /etc/nginx/sites-enabled/default
```

The second line removes nginx's example website so it does not listen on port 80. Note it is the **JDK** (the full Java kit, needed to *build* the app), not just the JRE.

**Check:**

```sh
java -version        # should mention 25
javac -version       # should also say 25 (this is the JDK part)
nginx -v             # nginx/1.24.x
systemctl is-active postgresql nginx    # active, active
free -h              # the "Mem" line: "total" should be about 2 GB
```

If `apt` says it cannot find `openjdk-25-jdk-headless`, install a Java 25 **JDK** (not JRE) from https://adoptium.net/installation/linux/ instead, then make sure `java -version` shows 25.

---

## 5. Create the database

First make two passwords and **write them down somewhere safe** (a password manager or a notes file on your PC). **On the VPS:**

```sh
openssl rand -hex 24     # this is your DB password: copy it
openssl rand -hex 32     # this is your JWT secret: copy it (used in step 7)
```

Each prints a long random string. Run the command twice if you want to practise, only keep the ones you copy.

Now create the database user and database. Replace `PASTE_DB_PASSWORD` with the first string (keep the single quotes). **On the VPS:**

```sh
sudo -u postgres psql -c "CREATE USER app WITH PASSWORD 'PASTE_DB_PASSWORD';"
sudo -u postgres psql -c "CREATE DATABASE app OWNER app;"
```

**Check:** log in to the database with the new user. It should print a table with `1`.

```sh
PGPASSWORD='PASTE_DB_PASSWORD' psql -h 127.0.0.1 -U app -d app -c "select 1;"
```

You do not need to create any tables. The app creates them itself the first time it starts.

Postgres on Ubuntu only listens on the server itself by default. You can see that with `sudo ss -tlnp | grep 5432`: the address should be `127.0.0.1`, not `0.0.0.0`.

---

## 6. Get the code onto the VPS

**On the VPS**, as your normal login user (do **not** do this as the `app` user we create later: the program that runs your app should not be able to change its own source code):

```sh
cd ~
git clone https://github.com/MrSolymi/TestProject.git
cd TestProject
ls
```

**Check:** `ls` shows `backend`, `deploy`, `frontend` and a few other files. The code lives in `~/TestProject` from now on (if you are `root`, that is `/root/TestProject`).

Good to know:

- The commands below read files from `~/TestProject/deploy/`. We **copy** them to where the system wants them. We never edit the files inside the clone, so that `git pull` stays smooth later.
- If you ever make the repository private, the server needs a read-only *deploy key* to clone it (GitHub docs: "Managing deploy keys").

> **Fallback if the server is too small or the build fails (step 7):** build the jar on your PC and upload it instead.
>
> ```powershell
> # on your PC, in the project folder
> cd backend\demo ; .\gradlew.bat bootJar ; cd ..\..
> scp backend\demo\build\libs\demo-0.0.1-SNAPSHOT.jar root@<VPS_IP>:/tmp/app.jar
> ```
>
> Then on the VPS, instead of running `update-backend.sh` in step 7, run `sudo install -o app -g app /tmp/app.jar /opt/testproject/app.jar`.

---

## 7. Configure, build and start the backend

**On the VPS.** Create a dedicated user for the app (it cannot log in; it only runs the app) and its folder:

```sh
sudo useradd --system --home /opt/testproject --shell /usr/sbin/nologin app
sudo install -d -o app -g app /opt/testproject
```

Now the settings file. The app reads its settings (database password and so on) from here, not from the code, so secrets never end up in git.

```sh
sudo install -d /etc/testproject
sudo install -m 600 ~/TestProject/deploy/api.env.example /etc/testproject/api.env
sudo nano /etc/testproject/api.env
```

Edit these lines, leave the rest as they are:

| Line | What to put |
| --- | --- |
| `DB_PASSWORD=` | the DB password from step 5 |
| `JWT_SECRET=` | the JWT secret from step 5 (the app refuses to start if it is shorter than 32 characters) |
| `CORS_ALLOWED_ORIGIN=` | `https://app.yourdomain.com` (exactly this form: `https`, no slash at the end) |
| `COOKIE_SECURE=` | keep `true` |
| `APP_BOOTSTRAP_USERNAME=` | the name of your first user, for example `admin` (letters and digits) |
| `APP_BOOTSTRAP_PASSWORD=` | a password for that first user, at least 12 letters and digits (avoid spaces and symbols for now) |

`APP_BOOTSTRAP_*` exists because registering needs an invite from an existing user, so someone has to be the very first user. These two lines create that user on the very first start, and only if the database has no users yet.

Install the service definition (it tells Linux how to run the app):

```sh
sudo install ~/TestProject/deploy/systemd/app.service /etc/systemd/system/app.service
sudo systemctl daemon-reload
```

Now build the app and put the jar in place. The script `update-backend.sh` does all of it: downloads the newest code, builds the jar, copies it to `/opt/testproject/app.jar`, and restarts the service if it is already running.

```sh
bash ~/TestProject/deploy/update-backend.sh
```

The first run downloads the build tool (Gradle) and the libraries, so expect a minute or two. It ends with `BUILD SUCCESSFUL` and a hint that the service is not started yet. Start it, and make it start on every boot:

```sh
sudo systemctl enable --now app
```

**Check:**

```sh
sudo systemctl status app --no-pager        # look for: active (running)
sudo journalctl -u app -n 40 --no-pager     # look for: Started DemoApplication ... and Created bootstrap user 'admin'
curl -i http://127.0.0.1:8080/api/auth/me
```

The last command should answer **`401`**. That is the success sign here: the app is running, it just says "you are not logged in".

Try the first user too (replace `YOUR_BOOTSTRAP_PASSWORD`):

```sh
curl -i -H "X-Requested-By: spa" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"YOUR_BOOTSTRAP_PASSWORD"}' \
  http://127.0.0.1:8080/api/auth/login
```

You should see `HTTP/1.1 200` and a `Set-Cookie: session=...` line.

When it works, remove the two `APP_BOOTSTRAP_*` lines from the file so the password does not sit there:

```sh
sudo nano /etc/testproject/api.env      # delete the two APP_BOOTSTRAP_ lines
sudo systemctl restart app
```

> **Gotcha:** the first user is only created if the database has **no users at all**. If you started the app once with a typo in the bootstrap password, that user already exists with the wrong password and changing the file later does nothing. To start over (this deletes all users and invites!):
>
> ```sh
> sudo -u postgres psql -d app -c "delete from invites; delete from users;"
> sudo systemctl restart app
> ```

---

## 8. Cloudflare: DNS, encryption, certificate

Open the Cloudflare dashboard, click your domain. Cloudflare sometimes renames buttons, so look for the names in **bold**.

### 8.1 Point `api.yourdomain.com` at your VPS

**DNS → Records → Add record:**

- Type: `A`
- Name: `api`
- IPv4 address: `<VPS_IP>`
- Proxy status: **Proxied** (the orange cloud must be ON)

Save. The orange cloud is important: it makes traffic go through Cloudflare, which hides your real server address.

### 8.2 Encryption mode

**SSL/TLS → Overview.** Set the encryption mode to **Full (strict)**. (If you only see a "Configure" button, open it and pick Full (strict).) This makes Cloudflare insist on a valid certificate on your server.

### 8.3 A certificate for your server

**SSL/TLS → Origin Server → Create Certificate.** Keep the defaults (Cloudflare generates the key, RSA, 15 years). The hostnames list should include `api.yourdomain.com` (the default `*.yourdomain.com` also covers it). Click **Create**.

You now see two boxes: **Origin Certificate** and **Private Key**. The private key is shown **only once**, so do not close the page until both are saved on the VPS.

**On the VPS:**

```sh
sudo install -d -m 755 /etc/ssl/cloudflare
sudo nano /etc/ssl/cloudflare/origin.pem
```

Paste the **Origin Certificate** (including the `-----BEGIN CERTIFICATE-----` and `-----END CERTIFICATE-----` lines), save, exit. Then:

```sh
sudo nano /etc/ssl/cloudflare/origin.key
```

Paste the **Private Key** (with its BEGIN/END lines), save, exit. Then lock the key down:

```sh
sudo chmod 600 /etc/ssl/cloudflare/origin.key
```

Now you may click OK in Cloudflare.

### 8.4 Make your server only talk to Cloudflare (Authenticated Origin Pulls)

This is the "mTLS client certificate" your friend mentioned. Idea: Cloudflare shows a special ID card to your server on every request, and nginx throws out anyone who cannot show it. Even if someone finds your server's IP, they cannot use it.

Download Cloudflare's ID-card checker. **On the VPS:**

```sh
sudo curl -fsSL -o /etc/ssl/cloudflare/origin-pull-ca.pem \
  https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem
```

Then in the Cloudflare dashboard: **SSL/TLS → Origin Server → Authenticated Origin Pulls**, and switch it **On**.

> Honest limit: this default ID card is the same for **every** Cloudflare customer. It stops random people, but not another Cloudflare user who knows your server IP. The firewall in step 11 closes that gap, because it only lets Cloudflare's addresses in at all. For even stronger protection you can use your own client certificate (Cloudflare docs: "Authenticated Origin Pulls, per-hostname").

---

## 9. Set up nginx

**On the VPS.** Copy the nginx files into place, and the script that keeps Cloudflare's address list up to date:

```sh
sudo install -d /etc/nginx/snippets
sudo install ~/TestProject/deploy/nginx/api-proxy.conf /etc/nginx/snippets/api-proxy.conf
sudo install ~/TestProject/deploy/nginx/api.conf /etc/nginx/conf.d/api.conf
sudo install ~/TestProject/deploy/cloudflare-ips.sh /usr/local/sbin/cloudflare-ips.sh
```

Now tell nginx your domain, by editing the **installed copy** (replace `yourdomain.com` with your real domain first):

```sh
sudo sed -i 's/api\.example\.com/api.yourdomain.com/' /etc/nginx/conf.d/api.conf
```

Then fetch Cloudflare's current IP ranges, test the config and reload nginx:

```sh
sudo cloudflare-ips.sh nginx
```

**Check:**

```sh
sudo nginx -t            # should say: syntax is ok / test is successful
```

If it complains about a missing file, a path in step 8.3 or 8.4 was wrong: check that `/etc/ssl/cloudflare/` contains `origin.pem`, `origin.key` and `origin-pull-ca.pem`.

What `api.conf` does, in short: it accepts HTTPS on port 443, demands Cloudflare's ID card, slows down anyone hammering the login endpoints (5 requests per second per visitor), and passes everything under `/api/` to the app on `127.0.0.1:8080`. Anything else returns 404.

---

## 10. Test the API through Cloudflare

Wait one or two minutes after creating the DNS record. **On your PC:**

```powershell
curl.exe -i https://api.yourdomain.com/api/auth/me
```

(In PowerShell you must type `curl.exe`. Plain `curl` is a different command there.)

**Check:** the answer starts with `HTTP/1.1 401` or `HTTP/2 401`. Same success sign as before: the whole chain works and you are simply not logged in.

Also check that going **around** Cloudflare does not work:

```powershell
curl.exe -ik --resolve api.yourdomain.com:443:<VPS_IP> https://api.yourdomain.com/api/auth/me
```

This should say **`400 No required SSL certificate was sent`**. nginx is refusing because there is no Cloudflare ID card.

If something else shows up:

| What you see | Likely reason |
| --- | --- |
| `Could not resolve host` | DNS record is missing or not spread yet. Wait a few minutes, check step 8.1. |
| Cloudflare error **521** | nginx is not running or nothing answers on port 443. `sudo systemctl status nginx`. |
| **525** or **526** | Certificate trouble: files from 8.3 are wrong, or `api.yourdomain.com` was not in the certificate hostnames. |
| **502 Bad Gateway** | nginx works but the Spring app is down: `sudo systemctl status app` and `sudo journalctl -u app -n 50`. |
| **400 No required SSL certificate** even through Cloudflare | Step 8.4 toggle is not On yet. |

---

## 11. Turn on the firewall (last step on the server)

Right now your server answers anyone who finds its IP address. The firewall will drop everyone except Cloudflare (for the website) and you (for SSH).

> **Danger zone:** a wrong firewall rule can lock you out of your own server. Two safety rules:
> 1. Keep your current SSH window open the whole time.
> 2. After enabling the firewall, open a **second** SSH window to prove you can still get in. Only then close the old one.
>
> If you do get locked out, most VPS providers have a web "console" or "rescue" button in their control panel that gets you back in.

**On the VPS**, in this order:

```sh
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo cloudflare-ips.sh ufw
sudo ufw enable
sudo ufw status numbered
```

What this does: block everything incoming by default, allow SSH, allow port 443 only from each of Cloudflare's address ranges, then switch the firewall on. (If you changed your SSH port from 22, allow that port instead of `OpenSSH`.)

**Check:**

1. Open a second SSH window: `ssh root@<VPS_IP>`. It must work.
2. `curl.exe -i https://api.yourdomain.com/api/auth/me` on your PC must still answer `401`.
3. On your PC: `curl.exe -m 10 -k https://<VPS_IP>/` should now **time out** (the firewall drops it).
4. On the VPS, see what is open to the world: `sudo ss -tlnp`. In the `Local Address` column, only SSH (port 22) and nginx (port 443) should show `0.0.0.0` or `[::]`. Spring (8080) and Postgres (5432) must show `127.0.0.1`.

Cloudflare changes its address list now and then. Every month or so, run `sudo cloudflare-ips.sh nginx` and `sudo cloudflare-ips.sh ufw` again. The script only adds rules, so old ones stay; you can list them with `sudo ufw status numbered` and remove stale ones with `sudo ufw delete <number>`.

---

## 12. Deploy the frontend: Cloudflare builds it from GitHub

Cloudflare can fetch your code from GitHub, build the website and publish it, and it repeats that **every time you push to `main`**. This feature is called *Workers Builds*.

### 12.1 Put your domain in the config and push it

**On your PC.** Open `frontend/wrangler.jsonc` and change `app.example.com` (in the `routes` section) to `app.yourdomain.com`. Then:

```powershell
git add frontend/wrangler.jsonc
git commit -m "Set my domain"
git push
```

### 12.2 Connect the repository in Cloudflare

In the Cloudflare dashboard open **Workers & Pages**, then **Create** and choose to **import a repository** (connect to Git). Log in to GitHub when asked and allow Cloudflare access to the `TestProject` repository. Then fill in these settings (the page may word them slightly differently):

| Setting | Value |
| --- | --- |
| Project / Worker name | `testproject-frontend` (it **must** match `name` in `frontend/wrangler.jsonc`) |
| Production branch | `main` |
| **Root directory** | `frontend` (your repo has the backend and frontend side by side, this tells Cloudflare which part to build) |
| **Build command** | `pnpm build` |
| **Deploy command** | `npx wrangler deploy` (usually already the default) |
| **Build variables and secrets** | add `VITE_API_URL` = `https://api.yourdomain.com` (no slash at the end) |

About that last one: `VITE_API_URL` tells the website where the API is. It is baked into the website when it is built, so it must be set as a **build** variable (not a runtime one). If you change it later, you must start a new build.

Cloudflare's build machine already has a recent Node (24) and pnpm, which fits this project. If you ever want to pin it, add the build variable `NODE_VERSION` = `24`.

Save and deploy.

**Check:** open the build log in Cloudflare. You should see it install the packages, run the build, and end with a successful deploy. Then open `https://app.yourdomain.com`: you should see the login page.

From now on, **`git push` is your frontend deploy**.

> No GitHub? You can also deploy by hand from your PC: copy `frontend/.env.example` to `frontend/.env.production`, set `VITE_API_URL=https://api.yourdomain.com` in it, then in the `frontend` folder run `pnpm wrangler login` (opens your browser) and `pnpm cf:deploy`.

---

## 13. Final test

1. On `https://app.yourdomain.com`, log in with your first user (the bootstrap user from step 7). You should see "Hello, ...!".
2. Click **Create invite link**, copy the link.
3. Open the link in a **private/incognito window**, register a second user. You land on the hello page.
4. Open the same link again in another private window and try to register: it must say the invite is invalid. (Invites work only once.)
5. Click **Log out**.

If all five work, you are live.

Housekeeping you should have done by now: removed the `APP_BOOTSTRAP_*` lines (step 7), and saved the DB password and JWT secret somewhere safe.

---

## 14. Updating later

**New backend version:** push your changes to GitHub, then on the VPS:

```sh
bash ~/TestProject/deploy/update-backend.sh
```

That pulls the new code, builds, installs the jar and restarts the app (about a minute). Database changes (new tables and so on) apply automatically when the app starts.

**New frontend version:** just `git push`. Cloudflare rebuilds by itself (watch the build log in the dashboard).

**Changed an nginx file in the repo:** copy it again (step 9 commands), then `sudo nginx -t && sudo systemctl reload nginx`. Remember to re-run the `sed` line for your domain.

**Changed `/etc/testproject/api.env`:** `sudo systemctl restart app`.

**Changed the `VITE_API_URL` build variable in Cloudflare:** start a new build (retry the last build, or push a commit), because the value is baked in at build time.

---

## 15. When something goes wrong

Where to look first:

- App log: `sudo journalctl -u app -n 100 --no-pager` (live: `sudo journalctl -u app -f`)
- nginx log: `sudo tail -n 50 /var/log/nginx/error.log`
- Cloudflare build log: dashboard → your Worker → **Deployments / Builds**
- In the browser: press `F12`, look at the **Console** and **Network** tabs while you try to log in.

| Problem | Likely reason and fix |
| --- | --- |
| The app does not start; the log mentions `app.jwt.secret must be at least 32 bytes` | `JWT_SECRET` in `api.env` is still the placeholder or too short. Use the 64-character string from `openssl rand -hex 32`. |
| The app does not start; the log says `Connection refused` or password failed | Wrong `DB_PASSWORD` or `DB_URL`, or Postgres is not running (`systemctl status postgresql`). |
| `update-backend.sh`: `Permission denied` | Run it with `bash` in front: `bash ~/TestProject/deploy/update-backend.sh`. (Files from Windows lose the "executable" flag, so `./script.sh` does not work.) |
| The build prints `Killed` or `OutOfMemoryError` | The server ran out of memory. Add a swap file (emergency memory on disk) and run the script again, see the box below. |
| `git pull`: "Your local changes would be overwritten" | A file inside `~/TestProject` was edited by hand. See which with `git status`, undo with `git restore <file>`. Edit the *copies* in `/etc/...`, not the clone. |
| Login page says "Could not reach the server" | The website cannot reach the API. Check `VITE_API_URL` (https, no slash at the end, and a new build after changing it), and that `CORS_ALLOWED_ORIGIN` in `api.env` matches the website address exactly. Look at the Network tab. |
| The website calls `localhost` or the wrong address | `VITE_API_URL` was missing or wrong **at build time**. Set it as a *build* variable and start a new build. |
| Login seems to work but you are logged out right away | The cookie is not being kept. The website and API must both be on `https` and on the same domain (`app.` and `api.` of the same domain). |
| You cannot log in as the first user | It is only created when the database is empty. See the "Gotcha" box in step 7. |
| Cloudflare build: the name does not match | The Worker name in the dashboard must be exactly `testproject-frontend`, the same as `name` in `frontend/wrangler.jsonc`. |
| Cloudflare build: the deploy step fails at the custom domain | Remove the `routes` block from `wrangler.jsonc`, push, then add the domain by hand: Worker → **Settings → Domains & Routes → Add → Custom domain**. |
| Cloudflare 52x errors on the API | See the table in step 10. |
| SSH stopped working after the firewall | Use your provider's web console, run `sudo ufw allow OpenSSH` (or your SSH port), and check `sudo ufw status`. |
| Strange `\r` or `bad interpreter` errors on files you uploaded from Windows | Windows line endings. Fix a file with `sed -i 's/\r$//' <file>`. (Files that came through `git clone` on the server are fine.) |

**Swap file** (only if the build runs out of memory). Swap lets Linux use some disk space as emergency memory. **On the VPS:**

```sh
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h        # a "Swap" line with 2.0Gi should appear
```

---

## 16. Things this guide does not cover (worth doing later)

- **Backups.** The database lives only on this server. A simple backup: `sudo -u postgres pg_dump app > backup.sql`, then copy that file to your PC (`scp`). Do it regularly.
- **SSH keys instead of passwords**, and turning password login off. Your provider's docs explain this; it greatly reduces break-in attempts.
- **Automatic security updates:** `sudo apt install unattended-upgrades`.
- **Logout does not cancel a login token.** A stolen token would work until it expires (12 hours). This is a known limit of the simple design.

---

## Glossary

- **VPS**: a rented always-on computer you control remotely.
- **SSH**: the secure remote terminal used to control the VPS.
- **git clone**: download a copy of a repository (all the code) onto a computer.
- **Workers Builds**: Cloudflare's feature that builds and publishes your website from GitHub on every push.
- **DNS record**: an entry that says "this name points to this IP address".
- **Proxy (orange cloud)**: traffic goes through Cloudflare first, which hides your server's address.
- **Reverse proxy**: a program (nginx) that receives requests and passes them to another program (your app).
- **TLS / certificate**: what makes `https` work. A certificate proves a server's identity.
- **Origin**: your own server, as opposed to Cloudflare in front of it.
- **mTLS / Authenticated Origin Pull**: the server also checks the visitor's certificate. Here the "visitor" is Cloudflare.
- **Environment file**: a file of `NAME=value` settings (like passwords) given to a program, kept out of the code.
- **systemd service**: a program managed by Linux: started at boot and restarted if it crashes.
- **Firewall (ufw)**: rules about who may connect to which port.
- **Swap**: disk space Linux uses as emergency memory when RAM runs out.
- **Loopback / `127.0.0.1`**: "this computer only". A program listening there cannot be reached from the internet.
