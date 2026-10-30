#!/usr/bin/env bash
# Installed on the VPS as /usr/local/sbin/deploy-app and started by GitHub Actions over SSH, through a
# restricted key that can run nothing else (see DEPLOY.md, step 14.2). One script serves every app on the
# server: the app name is the first argument, and its settings live in /etc/deploy/<app>.conf
# (see deploy/app.conf.example).
#
# It pulls the newest image of the app's service, restarts it and waits until it answers.
# If it does not answer, this script exits with an error and the GitHub job turns red.
#
# It does NOT run `git pull`: changes to compose.yaml or the nginx files stay a manual step
# (bash ~/TestProject/deploy/update.sh), because a bad config change should not go live unattended.
set -euo pipefail

APP="${1:-}"

# The name becomes part of a file path, so allow nothing but lowercase letters, digits and dashes.
if ! [[ "$APP" =~ ^[a-z0-9-]+$ ]]; then
  echo "Usage: deploy-app <app-name>   (lowercase letters, digits and dashes only)" >&2
  exit 1
fi

CONF="/etc/deploy/$APP.conf"
if [ ! -f "$CONF" ]; then
  echo "No config for '$APP': $CONF does not exist. See DEPLOY.md, step 14.2." >&2
  exit 1
fi

# Settings every config must define (the file is plain shell, written by root).
COMPOSE_DIR="" SERVICE="" HEALTH_URL="" HEALTH_CODE=""
# shellcheck source=/dev/null
. "$CONF"

for var in COMPOSE_DIR SERVICE HEALTH_URL HEALTH_CODE; do
  if [ -z "${!var}" ]; then
    echo "$CONF does not set $var." >&2
    exit 1
  fi
done

if [ ! -f "$COMPOSE_DIR/compose.yaml" ]; then
  echo "COMPOSE_DIR ($COMPOSE_DIR) does not contain compose.yaml. See DEPLOY.md, step 14.2." >&2
  exit 1
fi
cd "$COMPOSE_DIR"

echo "==> [$APP] Pulling the newest image"
docker compose pull "$SERVICE"

echo "==> [$APP] Restarting $SERVICE"
docker compose up -d "$SERVICE"

echo "==> [$APP] Waiting for it to answer (up to 90 seconds)"
deadline=$((SECONDS + 90))
while [ "$SECONDS" -lt "$deadline" ]; do
  code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$HEALTH_URL" || true)
  if [ "$code" = "$HEALTH_CODE" ]; then
    echo "$APP is up."
    docker image prune -f > /dev/null
    docker compose ps
    echo "Deploy OK"
    exit 0
  fi
  sleep 2
done

echo "Deploy FAILED: $APP did not answer within 90 seconds." >&2
echo "--- Most telling log lines:" >&2
docker compose logs --tail 300 "$SERVICE" 2>&1 | grep -E "Caused by|ERROR|FAILED|must be" | tail -6 >&2 || true
echo "--- Last log lines:" >&2
docker compose logs --tail 25 "$SERVICE" >&2
exit 1
