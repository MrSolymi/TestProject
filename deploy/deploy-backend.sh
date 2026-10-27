#!/usr/bin/env bash
# Installed on the VPS as /usr/local/sbin/deploy-backend and started by GitHub Actions over SSH, through a
# restricted key that can run nothing else (see DEPLOY.md, step 14.2).
#
# It pulls the newest backend image, restarts the backend and waits until the API answers.
# If it does not answer, this script exits with an error and the GitHub job turns red.
#
# It does NOT run `git pull`: changes to compose.yaml or the nginx files stay a manual step
# (bash ~/TestProject/deploy/update.sh), because a bad config change should not go live unattended.
set -euo pipefail

# DEPLOY.md replaces this with the real path when it installs the script.
COMPOSE_DIR=/CHANGE/ME

if [ ! -f "$COMPOSE_DIR/compose.yaml" ]; then
  echo "COMPOSE_DIR ($COMPOSE_DIR) does not contain compose.yaml. See DEPLOY.md, step 14.2." >&2
  exit 1
fi
cd "$COMPOSE_DIR"

echo "==> Pulling the newest backend image"
docker compose pull api

echo "==> Restarting the backend"
docker compose up -d api

echo "==> Waiting for the backend to answer (up to 90 seconds)"
deadline=$((SECONDS + 90))
while [ "$SECONDS" -lt "$deadline" ]; do
  code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' http://127.0.0.1:8080/api/auth/me || true)
  if [ "$code" = "401" ]; then # 401 = the API is running, it just says "you are not logged in"
    echo "The backend is up."
    docker image prune -f > /dev/null
    docker compose ps
    echo "Deploy OK"
    exit 0
  fi
  sleep 2
done

echo "Deploy FAILED: the backend did not answer within 90 seconds." >&2
echo "--- Most telling log lines:" >&2
docker compose logs --tail 300 api 2>&1 | grep -E "Caused by|ERROR|FAILED|must be" | tail -6 >&2 || true
echo "--- Last log lines:" >&2
docker compose logs --tail 25 api >&2
exit 1
