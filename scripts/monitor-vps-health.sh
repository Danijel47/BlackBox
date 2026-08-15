#!/usr/bin/env bash

set -Eeuo pipefail

: "${HEALTHCHECKS_PING_URL:?HEALTHCHECKS_PING_URL is required}"

LOCAL_READINESS_URL="http://127.0.0.1:8100/actuator/health/readiness"
LOCAL_TELEGRAM_HEALTH_URL="http://127.0.0.1:8100/actuator/health/telegram"
PING_URL="${HEALTHCHECKS_PING_URL%/}"
CONTAINER_NAME="blackbox"
STATE_DIR="${BLACKBOX_MONITOR_STATE_DIR:-/var/lib/blackbox-monitor}"
RESTART_COUNT_FILE="$STATE_DIR/restart-count"
TELEGRAM_FAILURE_COUNT_FILE="$STATE_DIR/telegram-failure-count"
TELEGRAM_FAILURE_THRESHOLD="${BLACKBOX_TELEGRAM_FAILURE_THRESHOLD:-3}"

signal_failure() {
    local reason="$1"
    echo "BlackBox health check failed: $reason" >&2
    curl --fail --silent --show-error --max-time 10 --retry 2 "$PING_URL/fail" >/dev/null || true
    exit 1
}

if ! [[ "$TELEGRAM_FAILURE_THRESHOLD" =~ ^[1-9][0-9]*$ ]]; then
    echo "BLACKBOX_TELEGRAM_FAILURE_THRESHOLD must be a positive integer" >&2
    exit 2
fi

readiness_up=false
for attempt in 1 2 3; do
    if curl --fail --silent --show-error --max-time 5 "$LOCAL_READINESS_URL" >/dev/null; then
        readiness_up=true
        break
    fi
    if [[ "$attempt" -lt 3 ]]; then
        sleep 2
    fi
done
if [[ "$readiness_up" != true ]]; then
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

telegram_failure_count=0
if [[ -f "$TELEGRAM_FAILURE_COUNT_FILE" ]]; then
    telegram_failure_count="$(<"$TELEGRAM_FAILURE_COUNT_FILE")"
    if ! [[ "$telegram_failure_count" =~ ^[0-9]+$ ]]; then
        telegram_failure_count=0
    fi
fi

if curl --fail --silent --show-error --max-time 8 "$LOCAL_TELEGRAM_HEALTH_URL" >/dev/null; then
    if [[ "$telegram_failure_count" -gt 0 ]]; then
        echo "Telegram health recovered after $telegram_failure_count consecutive failure(s)"
    fi
    printf '0\n' > "$TELEGRAM_FAILURE_COUNT_FILE"
else
    telegram_failure_count=$((telegram_failure_count + 1))
    printf '%s\n' "$telegram_failure_count" > "$TELEGRAM_FAILURE_COUNT_FILE"
    echo "Telegram health check failed ($telegram_failure_count/$TELEGRAM_FAILURE_THRESHOLD consecutive failures)" >&2
    if [[ "$telegram_failure_count" -ge "$TELEGRAM_FAILURE_THRESHOLD" ]]; then
        signal_failure "Telegram health failed $telegram_failure_count consecutive checks"
    fi
fi

curl --fail --silent --show-error --max-time 10 --retry 2 "$PING_URL" >/dev/null
