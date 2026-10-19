#!/usr/bin/env bash
# Pulls the newest backend image and restarts the stack. Run it on the VPS as your normal user
# (it uses sudo for Docker):
#
#   bash ~/TestProject/deploy/update.sh
#
# Use `bash script.sh` instead of `./script.sh`: files committed from Windows lose the "executable" flag.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [ ! -e .env ]; then
  echo "Missing deploy/.env (it should link to /etc/testproject/api.env). Do step 7 in DEPLOY.md first." >&2
  exit 1
fi

echo "==> Fetching the newest config files"
git pull --ff-only

echo "==> Pulling the newest images"
sudo docker compose pull

echo "==> Restarting whatever changed"
sudo docker compose up -d

echo "==> Removing old, unused images"
sudo docker image prune -f

echo "==> Status"
sudo docker compose ps
sudo docker compose logs --tail 15 api
