#!/usr/bin/env bash

set -Eeuo pipefail

: "${HEALTHCHECKS_PING_URL:?HEALTHCHECKS_PING_URL is required}"

LOCAL_HEALTH_URL="http://127.0.0.1:8100/actuator/health/readiness"
PING_URL="${HEALTHCHECKS_PING_URL%/}"
CONTAINER_NAME="blackbox"
STATE_DIR="${BLACKBOX_MONITOR_STATE_DIR:-/var/lib/blackbox-monitor}"
RESTART_COUNT_FILE="$STATE_DIR/restart-count"

signal_failure() {
    local reason="$1"
    echo "BlackBox health check failed: $reason" >&2
    curl --fail --silent --show-error --max-time 10 --retry 2 "$PING_URL/fail" >/dev/null || true
    exit 1
}

if ! curl --fail --silent --show-error --max-time 10 "$LOCAL_HEALTH_URL" >/dev/null; then
    signal_failure "application readiness endpoint is down"
fi

if ! restart_count="$(docker inspect --format '{{.RestartCount}}' "$CONTAINER_NAME" 2>/dev/null)"; then
    signal_failure "Docker container is missing"
fi

mkdir -p "$STATE_DIR"
if [[ -f "$RESTART_COUNT_FILE" ]]; then
    previous_restart_count="$(<"$RESTART_COUNT_FILE")"
    if [[ "$restart_count" =~ ^[0-9]+$ \
        && "$previous_restart_count" =~ ^[0-9]+$ \
        && "$restart_count" -gt "$previous_restart_count" ]]; then
        printf '%s\n' "$restart_count" > "$RESTART_COUNT_FILE"
        signal_failure "container restart count increased from $previous_restart_count to $restart_count"
    fi
fi
printf '%s\n' "$restart_count" > "$RESTART_COUNT_FILE"

recent_logs="$(docker logs --since 6m "$CONTAINER_NAME" 2>&1 || true)"
if grep -qE 'Caused by: 409:|409.*Conflict.*getUpdates' <<<"$recent_logs"; then
    signal_failure "Telegram polling conflict (409) detected"
fi

polling_error_count="$(grep -c 'Error received from Telegram GetUpdates Request' <<<"$recent_logs" || true)"
if [[ "$polling_error_count" =~ ^[0-9]+$ && "$polling_error_count" -ge 5 ]]; then
    signal_failure "$polling_error_count Telegram polling errors occurred in six minutes"
fi

curl --fail --silent --show-error --max-time 10 --retry 2 "$PING_URL" >/dev/null
