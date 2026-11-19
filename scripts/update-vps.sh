#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.production.yaml"
ENV_FILE="$PROJECT_DIR/.env"

cd "$PROJECT_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "Missing $ENV_FILE" >&2
    exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
    echo "Missing $COMPOSE_FILE" >&2
    exit 1
fi

echo "Pulling the latest Git changes..."
git pull --ff-only

echo "Validating the production Compose configuration..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet

echo "Building the bot with freshly pulled Java base images..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" build --pull bot

echo "Updating the containers..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-build --remove-orphans --wait --wait-timeout 300

echo "Checking the container Java version..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T bot java -version

echo "Container status:"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps

echo "Recent bot logs:"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" logs --tail=50 bot
