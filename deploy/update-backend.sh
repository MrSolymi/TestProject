#!/usr/bin/env bash
# Fetches the latest code, builds the backend jar and (re)starts the service.
# Run it on the VPS as your normal user (not root-only, it uses sudo where needed):
#
#   bash ~/TestProject/deploy/update-backend.sh
#
# Use `bash script.sh` instead of `./script.sh`: files committed from Windows lose the "executable" flag.
# Works for the very first install too. DEPLOY.md creates the `app` user and /opt/testproject first.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
install_dir=/opt/testproject

if [ ! -d "$install_dir" ] || ! id app >/dev/null 2>&1; then
  echo "Missing user 'app' or $install_dir. Do step 7 in DEPLOY.md first." >&2
  exit 1
fi

echo "==> Pulling the latest code"
git -C "$repo" pull --ff-only

echo "==> Building the jar (the first run downloads Gradle and libraries, this can take a few minutes)"
cd "$repo/backend/demo"
# `sh gradlew` because the executable flag may be missing. --no-daemon so Gradle does not stay in memory afterwards.
sh gradlew bootJar --no-daemon

jar=""
for f in build/libs/demo-*.jar; do
  case "$f" in *-plain.jar) continue ;; esac
  jar="$f"
done
if [ -z "$jar" ] || [ ! -f "$jar" ]; then
  echo "No jar found in $repo/backend/demo/build/libs" >&2
  exit 1
fi

echo "==> Installing $jar to $install_dir/app.jar"
sudo install -o app -g app "$jar" "$install_dir/app.jar"

if systemctl is-enabled --quiet app 2>/dev/null; then
  echo "==> Restarting the service"
  sudo systemctl restart app
  sleep 5
  sudo systemctl status app --no-pager --lines=0 || true
  sudo journalctl -u app -n 15 --no-pager
else
  echo "==> Jar installed. First time? Start the service with:  sudo systemctl enable --now app"
fi
