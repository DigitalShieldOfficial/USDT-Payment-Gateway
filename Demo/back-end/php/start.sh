#!/bin/bash
# DSPay mock merchant (PHP) — background start script

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$ROOT/server.pid"
LOG_DIR="$ROOT/logs"
LOG_FILE="$LOG_DIR/server.log"

# Skip if already running
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "Server already running (PID=$PID). Run ./stop.sh first to restart."
        exit 1
    fi
    rm -f "$PID_FILE"
fi

if ! command -v php >/dev/null 2>&1; then
    echo "PHP CLI not found. Install PHP 7.4 or newer (php-cli) before starting." >&2
    exit 1
fi

prompt_required() {
    local name="$1" description="$2" value
    value="${!name-}"
    if [ -n "$value" ]; then
        return
    fi
    if [ ! -t 0 ]; then
        echo "Missing $name ($description). Set it as an environment variable." >&2
        exit 1
    fi
    while [ -z "$value" ]; do
        read -r -p "$description ($name): " value || exit 1
        [ -n "$value" ] || echo "$name cannot be empty." >&2
    done
    printf -v "$name" '%s' "$value"
}

prompt_secret() {
    if [ -n "${API_SECRET:-}" ]; then
        return
    fi
    if [ ! -t 0 ]; then
        echo "Missing API_SECRET. Set it as an environment variable." >&2
        exit 1
    fi
    while [ -z "${API_SECRET:-}" ]; do
        read -r -s -p "API secret (API_SECRET, hidden): " API_SECRET || exit 1
        echo
        [ -n "$API_SECRET" ] || echo "API_SECRET cannot be empty." >&2
    done
}

echo "Required configuration (preset environment variables are reused):"
echo "  DSPAY_BASE_URL   DSPay API base URL"
echo "  PUBLIC_BASE_URL  public URL of this demo"
echo "  MERCHANT_NO      merchant number"
echo "  API_SECRET       API secret (hidden input)"
echo "  PORT             optional; default 3000"
echo "  FRONT_END_DIR    optional; default ../../front-end (relative to this script)"
prompt_required DSPAY_BASE_URL "DSPay API base URL"
prompt_required PUBLIC_BASE_URL "Public demo URL"
prompt_required MERCHANT_NO "Merchant number"
prompt_secret

PORT="${PORT:-3000}"
if ! [[ "$PORT" =~ ^[1-9][0-9]*$ ]] || (( PORT > 65535 )); then
    echo "Invalid PORT: expected an integer from 1 to 65535." >&2
    exit 1
fi
export DSPAY_BASE_URL PUBLIC_BASE_URL MERCHANT_NO API_SECRET PORT

echo "Starting server..."

cd "$ROOT"
mkdir -p "$LOG_DIR"
printf '\n=== Starting %s ===\n' "$(date)" >> "$LOG_FILE"
LOG_START_LINE=$(wc -l < "$LOG_FILE")
# Bind 0.0.0.0 (not localhost) so the demo is reachable when deployed on a server,
# matching the Java/Node.js versions.
nohup php -S "0.0.0.0:$PORT" server.php >> "$LOG_FILE" 2>&1 &
PID=$!
echo $PID > "$PID_FILE"

for ((attempt = 0; attempt < 30; attempt++)); do
    if tail -n +"$((LOG_START_LINE + 1))" "$LOG_FILE" | grep -q 'Development Server' && kill -0 "$PID" 2>/dev/null; then
        echo "Server started successfully (PID=$PID)"
        echo "Log file: $LOG_FILE"
        echo "Port: $PORT"
        echo "Demo page: ${PUBLIC_BASE_URL%/}/"
        echo "View logs: tail -f '$LOG_FILE'"
        echo "Stop server: ./stop.sh"
        exit 0
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        break
    fi
    sleep 1
done

echo "Server failed to start. Recent logs:" >&2
tail -n +"$((LOG_START_LINE + 1))" "$LOG_FILE" >&2
if kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
fi
rm -f "$PID_FILE"
exit 1
