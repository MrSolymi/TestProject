# Deploying, step by step (beginner guide)

This guide takes the app from your PC to the internet:

- the **frontend** (the website) runs on Cloudflare, for free
- the **backend** (the API and its database) runs on a small rented server (a VPS) that you control, inside Docker containers

Both are deployed **straight from your GitHub repository**: GitHub builds the backend into a Docker image and the server just downloads it, and Cloudflare builds the website by itself whenever you push. Optionally (step 14.2), GitHub can also tell the server to update itself after every successful build, so a `git push` is the only thing you ever do.

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
          your VPS:  nginx (port 443)  →  backend container (127.0.0.1:8080)  →  Postgres container (no outside access)

 GitHub (your code) ──► GitHub Actions tests it and builds the backend image ──► the VPS downloads the image
                    └─► Cloudflare builds the website
```

What each piece is, in one line:

| Piece | What it does |
| --- | --- |
| **GitHub** | Stores your code. It also builds the backend image for you (GitHub Actions) and stores it (the container registry). |
| **VPS** | A computer you rent that is always on. You control it over SSH (a remote terminal). |
| **Domain** | Your own web address, like `yourdomain.com`. You need one (about $10 per year). |
| **Cloudflare** | Sits in front of everything. Hosts your website, and hides and protects your server. Free plan is enough. |
| **Docker** | Runs programs in sealed boxes called *containers*. A container carries everything its program needs, so the server needs no Java installed. |
| **Image** | The ready-made package a container is started from. Your backend is one image, Postgres is another. |
| **Docker Compose** | Starts and connects several containers from one file (`deploy/compose.yaml`). |
| **nginx** | The "front door" program on the VPS. It receives web traffic and hands it to the backend. Called a *reverse proxy*. |
| **Backend container** | Your Spring app. Its port is only published on the server itself (`127.0.0.1`), so nobody can reach it directly. |
| **Postgres container** | The database. It is not published at all: only the backend container can talk to it. |
| **ufw** | The firewall. We will tell it to only accept web traffic from Cloudflare. |

Security idea, from your friend's advice, in plain words: **only two programs may be reachable from the internet: SSH (so you can log in) and nginx (and nginx only talks to Cloudflare).** Everything else (the backend, Postgres) stays hidden. That is why we do it in this order: set everything up first, add the firewall last.

### Why two addresses (`app.` and `api.`)?

The login is stored in a cookie. Browsers only send that cookie if the website and the API are on the **same domain**. `app.yourdomain.com` and `api.yourdomain.com` count as the same site. A free `something.workers.dev` address for the website would **not** work for login, so you need your own domain.

---

## 2. Before you start

You need:

- [ ] **A domain name.** Buy one anywhere (Cloudflare itself sells them at cost). Then add it to Cloudflare: create a free account at cloudflare.com, click *Add a domain*, and follow the steps. Cloudflare tells you two *nameservers* to set at the place where you bought the domain. Wait until the Cloudflare dashboard says the domain is **Active**.
- [ ] **A VPS with Ubuntu 24.04**, with its **public IP address** (an address like `203.0.113.10`). You should be able to log in with SSH (your provider tells you how: usually `root` plus a password or key).
- [ ] **The project on GitHub, with the backend image built.** See "Push to GitHub and build the image" right below.

### Push to GitHub and build the image

The server and Cloudflare both fetch your work from GitHub, so it has to be there first. **On your PC**, in the project folder:

```powershell
git status
```

Read the list. It must **not** show any `.env` file (like `.env` or `.env.production`), nor folders named `node_modules`, `build` or `.gradle`, nor `.jar` files. The `.gitignore` files already hide them, so normally you only see your own source files. If the list looks right:

```powershell
git add .
git commit -m "Add Docker deployment"
git push
```

Your repository is **public**, so everyone on the internet can read it. That is fine as long as no real password is in it, and there is none: the real secrets only ever live in a file on your server (`/etc/testproject/api.env`, step 7). One thing to know: `application-dev.properties` contains throwaway values for local development (a fake secret and the `admin` / `admin12345` account). They are harmless in git, but the production setup never switches the `dev` profile on, and you must not either.

Now watch GitHub build the image. Open your repository on github.com and click the **Actions** tab. A run called **Backend** has started. It does two things in order:

1. **test**: runs the backend tests (2 to 4 minutes).
2. **image**: builds the Docker image and publishes it to GitHub's container registry as `ghcr.io/mrsolymi/testproject-backend`, with two tags: `latest` and `sha-` followed by the first 7 characters of your commit.

**Check:** the **test** and **image** jobs show a green check mark. (A third job called **deploy** also appears. It only does something after the optional setup in step 14.2, until then it just says it is doing nothing, and that is fine.) If one is red, click it and read the log (the troubleshooting table in step 15 has the usual causes). Every push to `main` that changes the backend repeats this, so the image always matches your code.

### Replace these placeholders everywhere

| Placeholder | Replace with | Example |
| --- | --- | --- |
| `yourdomain.com` | your real domain | `mycoolsite.dev` |
| `<VPS_IP>` | your VPS public IP | `203.0.113.10` |

The website will be `https://app.yourdomain.com` and the API will be `https://api.yourdomain.com`.

You can pick other names than `app` and `api` (for example `testapp` and `testapi`). That is fine, as long as both are under the **same domain** and you use the **same two names everywhere**: the DNS record (8.1), the nginx `server_name` (step 9), `CORS_ALLOWED_ORIGIN` (step 7), `wrangler.jsonc` and `VITE_API_URL` (step 12).

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
sudo apt install -y docker.io docker-compose-v2 nginx git ufw curl openssl
sudo rm -f /etc/nginx/sites-enabled/default
```

The second line removes nginx's example website so it does not listen on port 80. Notice what is **not** in the list: no Java and no Postgres. Those live inside the containers.

**Check:**

```sh
docker --version
docker compose version
nginx -v                                  # nginx/1.24.x
systemctl is-active docker nginx          # active, active
uname -m                                  # x86_64 is what this guide builds for
```

If `uname -m` says `aarch64` (an ARM server), the image must be built for ARM too. Open `.github/workflows/backend.yml`, change `platforms: linux/amd64` to `platforms: linux/amd64,linux/arm64`, push, and wait for the green check again.

If `apt` cannot find `docker-compose-v2`, install Docker from Docker's own instructions (docs.docker.com/engine/install/ubuntu) instead.

---

## 5. Make the two secrets

You need two random passwords. **Write them down somewhere safe** (a password manager or a notes file on your PC). **On the VPS:**

```sh
openssl rand -hex 24     # this is your DB password: copy it
openssl rand -hex 32     # this is your JWT secret: copy it
```

Each prints a long random string. You will paste them into a settings file in step 7.

You do **not** create the database yourself: the Postgres container creates it the first time it starts, and the app creates its tables.

---

## 6. Get the config files and the backend image

### 6.1 Download the repository (only for the config files)

**On the VPS**, as your normal login user:

```sh
cd ~
git clone https://github.com/MrSolymi/TestProject.git
cd TestProject
ls
```

**Check:** `ls` shows `backend`, `deploy`, `frontend` and a few other files. The code lives in `~/TestProject` (if you are `root`, that is `/root/TestProject`).

Nothing gets built here. The server only needs the files in `deploy/` (the Docker Compose file, the nginx files and two scripts). We **copy** or link them to where they are needed and never edit the files inside the clone, so that `git pull` stays smooth later. (If you ever make the repository private, the server also needs a read-only *deploy key* to clone it, and `docker login ghcr.io` to pull the image. See GitHub's docs.)

### 6.2 Let the server download the image (one-time click)

GitHub makes a new image **private** by default, and the server pulls it without logging in, so you must make it public once. Your image only contains your compiled app, and the code is already public in the repo, and there are no secrets in it.

1. On github.com click your profile picture, then **Your profile**, then the **Packages** tab.
2. Open **testproject-backend**. (If the Packages tab is empty, the **image** job in step 2 did not finish. Fix that first.)
3. On the right open **Package settings**, scroll to the bottom to **Danger Zone**, click **Change visibility**, choose **Public** and confirm.

**Check, on the VPS:**

```sh
sudo docker pull ghcr.io/mrsolymi/testproject-backend:latest
```

It should download and end with `Status: Downloaded newer image` (or `Image is up to date`). No login question. If it says `denied` or `unauthorized`, the package is still private.

---

## 7. Configure and start the backend

**On the VPS.** First the settings file. The backend reads its settings (database password and so on) from here, not from the code, so secrets never end up in git.

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

Now point Docker Compose at that file and start everything. Docker Compose automatically reads a file called `.env` that sits next to `compose.yaml`. We make that name a *link* to your settings file:

```sh
cd ~/TestProject/deploy
ln -s /etc/testproject/api.env .env
sudo docker compose up -d
```

`up -d` downloads the Postgres image and your backend image (the first time takes a minute or two), then starts both containers in the background. They restart by themselves after a crash or a server reboot.

> **Important:** all `docker compose` commands in this guide must be run from inside `~/TestProject/deploy`, because that is where `compose.yaml` is. They need `sudo` (talking to Docker needs admin rights).

**Check:**

```sh
sudo docker compose ps
sudo docker compose logs --tail 40 api
curl -i http://127.0.0.1:8080/api/auth/me
```

- `ps` should show two lines: `db` with `(healthy)` and `api` as `Up`. The `api` line shows `127.0.0.1:8080->8080/tcp`, and `db` shows no host port. That is on purpose.
- The log should contain `Started DemoApplication` and `Created bootstrap user 'admin'`.
- The `curl` should answer **`401`**. That is the success sign here: the backend is running, it just says "you are not logged in".

Try the first user too (replace `YOUR_BOOTSTRAP_PASSWORD`):

```sh
curl -i -H "X-Requested-By: spa" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"YOUR_BOOTSTRAP_PASSWORD"}' \
  http://127.0.0.1:8080/api/auth/login
```

You should see `HTTP/1.1 200` and a `Set-Cookie: session=...` line.

When it works, remove the two `APP_BOOTSTRAP_*` lines from the settings file so the password does not sit there, and apply the change:

```sh
sudo nano /etc/testproject/api.env      # delete the two APP_BOOTSTRAP_ lines
sudo docker compose up -d               # notices the change and recreates the api container
```

> **Gotcha:** the first user is only created if the database has **no users at all**. If you started the app once with a typo in the bootstrap password, that user already exists with the wrong password and changing the file later does nothing. To start over (this deletes all users and invites!), make sure the two `APP_BOOTSTRAP_` lines are in the settings file with the password you want, then:
>
> ```sh
> sudo docker compose exec db psql -U app -d app -c "delete from invites; delete from users;"
> sudo docker compose up -d --force-recreate api
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

While you are in the SSL/TLS settings, also turn on **Always Use HTTPS** (**SSL/TLS → Edge Certificates → Always Use HTTPS → On**). Without it, anyone who opens the plain `http://` version of your website gets it over an unencrypted connection, where it cannot talk to the API and shows an error page. This happens more often than you would think: chat apps like Messenger turn a bare `app.yourdomain.com` into an `http://` link, and browsers can autocomplete to an old `http://` address. With the switch on, every `http://` address jumps to `https://`. It applies to your whole domain.

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

nginx runs directly on the server (not in Docker). It receives the traffic from Cloudflare and forwards it to the backend container's port on `127.0.0.1:8080`.

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

What `api.conf` does, in short: it accepts HTTPS on port 443, demands Cloudflare's ID card, slows down anyone hammering the login endpoints (5 requests per second per visitor), and passes everything under `/api/` to the backend on `127.0.0.1:8080`. Anything else returns 404.

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
| **502 Bad Gateway** | nginx works but the backend container is down. In `~/TestProject/deploy`: `sudo docker compose ps` and `sudo docker compose logs --tail 50 api`. |
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

> **Docker and the firewall:** Docker edits the server's network rules itself, and a port that Docker *publishes* can bypass ufw completely. That is why `compose.yaml` publishes the backend only on `127.0.0.1` (the server itself) and does not publish Postgres at all. Never change those lines to something like `"8080:8080"`.

**Check:**

1. Open a second SSH window: `ssh root@<VPS_IP>`. It must work.
2. `curl.exe -i https://api.yourdomain.com/api/auth/me` on your PC must still answer `401`.
3. On your PC: `curl.exe -m 10 -k https://<VPS_IP>/` should now **time out** (the firewall drops it).
4. On the VPS, see what is open to the world: `sudo ss -tlnp`. In the `Local Address` column, only SSH (port 22) and nginx (port 443) should show `0.0.0.0` or `[::]`. The backend (8080) must show `127.0.0.1`, and Postgres (5432) must not appear at all.

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

**Why does this file exist?** The deploy command (`npx wrangler deploy`) reads `frontend/wrangler.jsonc` to learn what to publish. Without it, Cloudflare would not know where your built website is. The important lines:

- `name`: the project name. It must be the same as the *Project name* you type in 12.2.
- `assets.directory`: the folder with the finished website (`./build`, which `pnpm build` creates).
- `assets.not_found_handling`: `single-page-application` makes addresses like `/login` open your app, even when someone refreshes the page.
- `routes`: puts the site on your own address. `custom_domain: true` means Cloudflare creates the DNS record for you, so do not create one by hand for this name. (If the deploy complains that a DNS record already exists, delete that record in the DNS tab and deploy again.)
- `workers_dev: false`: switches off the free `*.workers.dev` address, because login cookies would not work there.

### 12.2 Connect the repository in Cloudflare

In the Cloudflare dashboard open **Workers & Pages**, then **Create** and choose to **import a repository** (connect to Git). Log in to GitHub when asked and allow Cloudflare access to the `TestProject` repository. You then see a form called **Set up your application**. Fill it in like this (Cloudflare changes the wording now and then, so match by meaning):

| Field | What to put |
| --- | --- |
| **Project name** | `testproject-frontend` (it **must** match `name` in `frontend/wrangler.jsonc`) |
| **Build command** | **`pnpm build`**. The box starts **empty**. If you leave it empty, nothing gets built and the deploy fails, because the `build` folder does not exist. |
| **Deploy command** | `npx wrangler deploy` (already filled in, leave it) |
| **Preview command** and **Enable Preview builds** | Leave the command alone and switch **Enable Preview builds** **off** for now, so that only `main` deploys, like the rest of this guide. |
| **Protect with Cloudflare Access** | Keep it **off**, otherwise every visitor would need a Cloudflare login. |
| **Advanced settings, Path** | **`frontend`**. This is the folder inside your repository that Cloudflare works in (its docs call it the *root directory*). It starts as `/`, which is the whole repository, where it would not find the website. Hover the **i** next to it to see this. |
| **API token** | Leave it on **Create new token**. Cloudflare makes a token that lets its build robot publish your site. |
| **Variable name** and **Variable value** | `VITE_API_URL` and your API address, for example `https://testapi.yourdomain.com` (no slash at the end). Use the real name you gave the API in steps 8.1 and 9. Do **not** click Encrypt, it is not a secret. |

About that last one: `VITE_API_URL` tells the website where the API is. It is baked into the website when it is built, so it must be a **build** variable (not a runtime one). If you change it later, you must start a new build.

Cloudflare's build machine already has a recent Node (24) and pnpm, which fits this project. If you ever want to pin it, add the build variable `NODE_VERSION` = `24`.

Save and deploy.

**Check:** open the build log in Cloudflare. You should see it install the packages, run the build, and end with a successful deploy. Then open `https://app.yourdomain.com`: you should see the login page. Also check that the plain `http` address jumps to `https`:

```powershell
curl.exe -I http://app.yourdomain.com
```

It should answer `301` or `308` with a `Location: https://...` line. If it says `200 OK`, go back to the **Always Use HTTPS** switch in step 8.2.

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

### 14.1 Update the backend by hand (always works)

1. Push your changes to GitHub.
2. Open the **Actions** tab and wait for the **Backend** run to turn green. (The new image only exists after the **image** job finished.)
3. On the VPS:

   ```sh
   bash ~/TestProject/deploy/update.sh
   ```

That pulls the newest config files and images, restarts what changed and prints the status (about a minute). Database changes (new tables and so on) apply automatically when the backend starts. The same command also picks up small security updates of Postgres 17. Your data stays safe in its Docker volume.

If you set up the automatic deploy in 14.2, you do not need this for normal backend changes. You still need it when you change files inside `deploy/` (for example `compose.yaml`), because the automatic deploy deliberately does not download those.

### 14.2 Update the backend automatically after every push (optional, one-time setup)

Idea: when the **image** job has published a new image, a third job called **deploy** logs in to your server over SSH and tells it to switch to the new image. You see success or failure in the **Actions** tab. Setting it up takes about 15 minutes. Do it only after everything in steps 1 to 13 works.

How it stays safe:

- GitHub gets its **own SSH key**. On the server that key is locked down: it can only start one script (`/usr/local/sbin/deploy-backend`). It cannot open a shell, run other commands or forward ports.
- That script is owned by `root`, so the `deploy` user (the one the key logs in as) cannot change it, and `deploy` has no other rights.
- The script only pulls the newest backend image, restarts the backend and checks that it answers. If it does not answer within 90 seconds, the job turns **red**.
- If the key ever leaks, the worst anyone can do is re-run a deploy of your own published image. You cut it off by deleting one file (see "Switch it off" below).
- Your server's IP address is stored as a GitHub *secret*, not in the workflow file, because your repository is public and Cloudflare is busy hiding that address.

What it does **not** do: it does not run `git pull` (so changes to files in `deploy/`, nginx files, or new settings in `api.env` stay manual, see 14.1), and it does not roll back by itself. If a new version does not start, the job turns red and you roll back by hand (14.3).

#### A. On the VPS: create the user, the script and the key

```sh
# 1) a user that exists only to receive the deploy command
sudo adduser --disabled-password --gecos "" deploy

# 2) the script that does the update (owned by root; the second line tells it where your compose folder is)
sudo install ~/TestProject/deploy/deploy-backend.sh /usr/local/sbin/deploy-backend
sudo sed -i "s#^COMPOSE_DIR=.*#COMPOSE_DIR=$HOME/TestProject/deploy#" /usr/local/sbin/deploy-backend

# 3) allow the deploy user to run exactly that one script as root, and nothing else
echo 'deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-backend' > /tmp/deploy-sudoers
sudo visudo -cf /tmp/deploy-sudoers && sudo install -m 440 /tmp/deploy-sudoers /etc/sudoers.d/deploy-backend
rm /tmp/deploy-sudoers
```

**Check:** `visudo` printed `parsed OK`, and `sudo grep '^COMPOSE_DIR=' /usr/local/sbin/deploy-backend` shows a path ending in `/TestProject/deploy` (if you are `root`, it starts with `/root/`).

Now make a key pair and lock the key to that one command:

```sh
# 4) a new key pair just for GitHub (the empty "" means no passphrase, because GitHub cannot type one)
ssh-keygen -t ed25519 -N "" -C "github-actions-deploy" -f ~/gh_deploy

# 5) allow that key for the deploy user, restricted to the one command
sudo install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
echo "restrict,command=\"sudo /usr/local/sbin/deploy-backend\" $(cat ~/gh_deploy.pub)" | sudo tee /home/deploy/.ssh/authorized_keys > /dev/null
sudo chown deploy:deploy /home/deploy/.ssh/authorized_keys
sudo chmod 600 /home/deploy/.ssh/authorized_keys
```

**Check:** try the key on the server itself, exactly the way GitHub will use it:

```sh
ssh -T -i ~/gh_deploy -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new deploy@127.0.0.1
```

You should see the deploy steps (`Pulling the newest backend image`, `Restarting the backend`, ...) ending with `Deploy OK`. Whatever command you add after `deploy@127.0.0.1`, only the script runs.

#### B. In GitHub: store three secrets

First show the two values you need. **On the VPS:**

```sh
cat ~/gh_deploy                                       # the PRIVATE key: everything from -----BEGIN to -----END
cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub     # the server's identity: one line starting with ssh-ed25519
```

On github.com open your repository, then **Settings**, **Secrets and variables**, **Actions**, **New repository secret**. Create these three (names exactly as written):

| Name | Value |
| --- | --- |
| `DEPLOY_HOST` | your VPS IP address, like `203.0.113.10` |
| `DEPLOY_SSH_KEY` | the private key from `cat ~/gh_deploy`: copy **all** its lines, including the `-----BEGIN` and `-----END` lines |
| `DEPLOY_HOST_KEY` | the single line from the `cut` command, starting with `ssh-ed25519` |

`DEPLOY_HOST_KEY` lets GitHub check that it is talking to your real server and not an impostor.

Now delete the private key from the server. GitHub keeps its own copy:

```sh
rm ~/gh_deploy ~/gh_deploy.pub
```

Do not paste these values anywhere else (chat, issues, screenshots).

#### C. Test it

On github.com open the **Actions** tab, click the latest **Backend** run and choose **Re-run all jobs** (or just push a backend change).

**Check:** the **deploy** job turns green. Open it: the log shows the same steps as your test, ending with `Deploy OK`. From now on every push to `main` that changes the backend goes: tests, image, server updated.

Until the secrets exist, the **deploy** job just says "The DEPLOY_* secrets are not set" and passes, so pushing before the server is ready is safe.

#### Switch it off, or cut the key off

- **Pause it:** delete the `DEPLOY_HOST` secret in GitHub. The job then skips itself.
- **Cut the key off completely** (for example if you think it leaked): on the VPS run `sudo rm /home/deploy/.ssh/authorized_keys`. To start again, repeat A4, A5 and B with a new key.
- **Good to know:** GitHub connects from addresses that change all the time, and the firewall in step 11 allows SSH from anywhere. If you later limit SSH to your own address, the deploy job can no longer connect.

### 14.3 Something broke after an update? Roll back

Every build has a tag like `sha-1a2b3c4`. Find the tag of the last good version on GitHub (your profile, **Packages**, **testproject-backend**, the list of versions). Then on the VPS:

```sh
sudo nano /etc/testproject/api.env      # add the line: IMAGE_TAG=sha-1a2b3c4
cd ~/TestProject/deploy && sudo docker compose up -d
```

To go back to always running the newest version, delete that line and run `bash ~/TestProject/deploy/update.sh`. While the line is there, the automatic deploy from 14.2 also pulls that pinned version, so nothing changes until you delete it.

### 14.4 Everything else

**New frontend version:** just `git push`. Cloudflare rebuilds by itself (watch the build log in the dashboard).

**Changed an nginx file in the repo:** copy it again (step 9 commands), then `sudo nginx -t && sudo systemctl reload nginx`. Remember to re-run the `sed` line for your domain.

**Changed `/etc/testproject/api.env`:** apply it with `cd ~/TestProject/deploy && sudo docker compose up -d --force-recreate api`. (`restart` alone does **not** re-read the file.)

**Changed the `VITE_API_URL` build variable in Cloudflare:** start a new build (retry the last build, or push a commit), because the value is baked in at build time.

---

## 15. When something goes wrong

Where to look first (the `docker compose` commands run inside `~/TestProject/deploy`):

- Backend log: `sudo docker compose logs --tail 100 api` (live: `sudo docker compose logs -f api`)
- Database log: `sudo docker compose logs --tail 50 db`
- Is everything running? `sudo docker compose ps`
- nginx log: `sudo tail -n 50 /var/log/nginx/error.log`
- GitHub build: the **Actions** tab. Cloudflare build: dashboard, your Worker, **Deployments / Builds**.
- In the browser: press `F12`, look at the **Console** and **Network** tabs while you try to log in.

| Problem | Likely reason and fix |
| --- | --- |
| `docker compose` says `no configuration file provided` | You are not in `~/TestProject/deploy`. `cd` there first. |
| `docker compose` says `required variable DB_PASSWORD is missing` | The link to your settings file is missing or broken. Check `ls -l ~/TestProject/deploy/.env`: it should point to `/etc/testproject/api.env` (step 7). |
| `permission denied while trying to connect to the Docker daemon` | You forgot `sudo` in front of `docker`. |
| `pull access denied`, `unauthorized` or `manifest unknown` when pulling | The image is still private (step 6.2), or the **image** job in Actions never succeeded, or the name is misspelled. |
| The `api` container keeps restarting (`sudo docker compose ps` shows `Restarting`) | Read the reason in `sudo docker compose logs --tail 50 api`. The usual ones are the next two rows. |
| The log mentions `app.jwt.secret must be at least 32 bytes` | `JWT_SECRET` in `api.env` is still the placeholder or too short. Use the 64-character string from `openssl rand -hex 32`, then `sudo docker compose up -d --force-recreate api`. |
| The log says `password authentication failed` | Postgres only reads `DB_PASSWORD` the **first** time it creates the database. If you changed it afterwards, the database still has the old one. Either put the old one back, or set the new one inside the database: `sudo docker compose exec db psql -U app -d app -c "ALTER USER app WITH PASSWORD 'THE_NEW_PASSWORD';"` then `sudo docker compose up -d --force-recreate api`. |
| `update.sh`: `Permission denied` | Run it with `bash` in front: `bash ~/TestProject/deploy/update.sh`. (Files from Windows lose the "executable" flag, so `./update.sh` does not work.) |
| `git pull`: "Your local changes would be overwritten" | A file inside `~/TestProject` was edited by hand. See which with `git status`, undo with `git restore <file>`. Edit the *copies* in `/etc/...`, not the clone. |
| The server's disk fills up | Old images pile up. `bash ~/TestProject/deploy/update.sh` cleans them each time, or run `sudo docker image prune -f`. Check space with `df -h`. |
| GitHub Actions: the **test** job is red | A backend test failed. Open the run, read which test. The same tests run on your PC with `cd backend\demo` then `.\gradlew.bat test`. |
| GitHub Actions: the **image** job fails with a permission or 403 error | On github.com: your repository, **Settings**, **Actions**, **General**, **Workflow permissions**, choose **Read and write permissions**, save, then re-run the job. |
| GitHub Actions: the **deploy** job says `Permission denied (publickey)` | The server does not accept the key. The `DEPLOY_SSH_KEY` secret must contain **all** lines of the private key (with the `-----BEGIN` and `-----END` lines) and match the public key in `/home/deploy/.ssh/authorized_keys`. That folder must be `700` and the file `600`, both owned by `deploy` (14.2 A, step 5). |
| GitHub Actions: the **deploy** job says `Host key verification failed` | `DEPLOY_HOST_KEY` is wrong, or the server was reinstalled and has a new identity. Recreate the secret with `cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub` on the VPS. |
| GitHub Actions: the **deploy** job says `Connection timed out` | `DEPLOY_HOST` is wrong, or SSH is blocked: for example your firewall or provider only allows your own address, but GitHub connects from changing ones. |
| GitHub Actions: the **deploy** job says `sudo: a password is required` or `not allowed` | The sudoers rule from 14.2 A, step 3 is missing or has a typo. On the VPS, `sudo -l -U deploy` should list `/usr/local/sbin/deploy-backend`. |
| GitHub Actions: the **deploy** job is red and says `Deploy FAILED` | The new version did not start. The job log shows the most telling error lines. A typical cause is a new setting the backend needs that your `api.env` does not have yet. Fix the cause (14.1 for settings), or roll back (14.3). |
| GitHub Actions: the **deploy** job passes but says the secrets are not set | That is on purpose until you finish 14.2 B. |
| Login page says "Could not reach the server" | The website cannot reach the API. Check `VITE_API_URL` (https, no slash at the end, and a new build after changing it), and that `CORS_ALLOWED_ORIGIN` in `api.env` matches the website address exactly. Look at the Network tab. |
| The website calls `localhost` or the wrong address | `VITE_API_URL` was missing or wrong **at build time**. Set it as a *build* variable and start a new build. |
| The website works with `https://` but shows an error page when opened without it (for example from a link in Messenger) | The page loaded over plain `http`, and the backend only accepts the `https` address of the website, so every request to the API is refused. Turn on **Always Use HTTPS** in Cloudflare (step 8.2). |
| Login seems to work but you are logged out right away | The cookie is not being kept. The website and API must both be on `https` and on the same domain (`app.` and `api.` of the same domain). |
| You cannot log in as the first user | It is only created when the database is empty. See the "Gotcha" box in step 7. |
| Cloudflare build: the name does not match | The Worker name in the dashboard must be exactly `testproject-frontend`, the same as `name` in `frontend/wrangler.jsonc`. |
| Cloudflare build: the deploy step fails at the custom domain | Remove the `routes` block from `wrangler.jsonc`, push, then add the domain by hand: Worker, **Settings**, **Domains & Routes**, **Add**, **Custom domain**. |
| Cloudflare 52x errors on the API | See the table in step 10. |
| SSH stopped working after the firewall | Use your provider's web console, run `sudo ufw allow OpenSSH` (or your SSH port), and check `sudo ufw status`. |

---

## 16. Things this guide does not cover (worth doing later)

- **Backups.** The database lives only on this server, in a Docker volume. A simple backup, run in `~/TestProject/deploy`:

  ```sh
  sudo docker compose exec -T db pg_dump -U app app > ~/backup.sql
  ```

  Then copy `backup.sql` to your PC (`scp`). Do it regularly. And a warning: `sudo docker compose down` stops the containers and is safe, but **`down -v` deletes the database volume**, so never add `-v` on the server.
- **SSH keys instead of passwords**, and turning password login off. Your provider's docs explain this; it greatly reduces break-in attempts.
- **Automatic security updates for the server itself:** `sudo apt install unattended-upgrades`.
- **Logout does not cancel a login token.** A stolen token would work until it expires (12 hours). This is a known limit of the simple design.

---

## Glossary

- **VPS**: a rented always-on computer you control remotely.
- **SSH**: the secure remote terminal used to control the VPS.
- **git clone**: download a copy of a repository (all the code) onto a computer.
- **Docker**: software that runs programs in sealed boxes (containers).
- **Image**: the ready-made package a container starts from. Like an installer that already contains everything.
- **Container**: one running copy of an image.
- **Container registry (GHCR)**: a website that stores images. GitHub's is `ghcr.io`.
- **Docker Compose**: starts and connects several containers from one file (`compose.yaml`).
- **Volume**: a folder Docker keeps outside the container, so the database survives when the container is replaced.
- **GitHub Actions**: GitHub's built-in robot that runs tests and builds the image whenever you push.
- **SSH key**: a pair of files used instead of a password. The private half stays secret, the public half goes on the server.
- **GitHub secret**: a value stored in your repository settings that workflows can use, but nobody can read back.
- **Restricted key**: an SSH key set up on the server so that it can run one fixed command and nothing else.
- **sudoers**: Linux's list of who may run which commands as the admin.
- **Workers Builds**: Cloudflare's feature that builds and publishes your website from GitHub on every push.
- **DNS record**: an entry that says "this name points to this IP address".
- **Proxy (orange cloud)**: traffic goes through Cloudflare first, which hides your server's address.
- **Reverse proxy**: a program (nginx) that receives requests and passes them to another program (your backend).
- **TLS / certificate**: what makes `https` work. A certificate proves a server's identity.
- **Origin**: your own server, as opposed to Cloudflare in front of it.
- **mTLS / Authenticated Origin Pull**: the server also checks the visitor's certificate. Here the "visitor" is Cloudflare.
- **Environment file**: a file of `NAME=value` settings (like passwords) given to a program, kept out of the code.
- **Firewall (ufw)**: rules about who may connect to which port.
- **Loopback / `127.0.0.1`**: "this computer only". A program listening there cannot be reached from the internet.
