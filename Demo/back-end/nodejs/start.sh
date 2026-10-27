#!/bin/bash
# DSPay mock merchant (Node.js) — background start script

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

prompt_required() {
    local name="$1" description="$2" value
    value="${!name}"
    if [ -n "$value" ]; then
        return
    fi
    if [ ! -t 0 ]; then
        echo "Missing $name ($description). Set it as an environment variable." >&2
        exit 1
    fi
    while [ -z "$value" ]; do
        IFS= read -r -p "$description ($name): " value || exit 1
        [ -n "$value" ] || echo "$name cannot be empty." >&2
    done
    printf -v "$name" '%s' "$value"
}

prompt_secret() {
    if [ -n "$API_SECRET" ]; then
        return
    fi
    if [ ! -t 0 ]; then
        echo "Missing API_SECRET. Set it as an environment variable." >&2
        exit 1
    fi
    while [ -z "$API_SECRET" ]; do
        IFS= read -r -s -p "API secret (API_SECRET, hidden): " API_SECRET || exit 1
        echo
        [ -n "$API_SECRET" ] || echo "API_SECRET cannot be empty." >&2
    done
}

PORT="${PORT-3000}"
if ! [[ "$PORT" =~ ^[1-9][0-9]{0,4}$ ]] || (( PORT > 65535 )); then
    echo "Invalid PORT: expected an integer from 1 to 65535." >&2
    exit 1
fi
LOCAL_BASE_URL="http://localhost:$PORT"
DSPAY_BASE_URL="${DSPAY_BASE_URL-https://pay-api.ds.pro}"
echo "DSPay API: $DSPAY_BASE_URL"
echo "Local port: $PORT (set PORT before startup only if you need another port)"
prompt_required MERCHANT_NO "Merchant number"
prompt_secret

export DSPAY_BASE_URL MERCHANT_NO API_SECRET PORT

echo "Starting server..."

cd "$ROOT"
mkdir -p "$LOG_DIR"
printf '\n=== Starting %s ===\n' "$(date)" >> "$LOG_FILE"
LOG_START_LINE=$(wc -l < "$LOG_FILE")
nohup node src/server.js >> "$LOG_FILE" 2>&1 &
PID=$!
echo $PID > "$PID_FILE"

for ((attempt = 0; attempt < 30; attempt++)); do
    if tail -n +"$((LOG_START_LINE + 1))" "$LOG_FILE" | grep -q '^Mock merchant:' && kill -0 "$PID" 2>/dev/null; then
        echo "Server started successfully (PID=$PID)"
        echo "Listening port: $PORT"
        echo "Log file: $LOG_FILE"
        echo "Local page: $LOCAL_BASE_URL/"
        echo "Local callback endpoint: $LOCAL_BASE_URL/notify (not reachable by DSPay)"
        echo "Next: open $LOCAL_BASE_URL/ in your browser."
        echo "For public access: run ngrok http $PORT, or proxy nginx to port $PORT."
        echo "Open the public URL to create orders; payment return links follow that request URL."
        echo "Merchant Portal callback URL: <your public URL>/notify"
        echo "Example: https://your-name.ngrok-free.dev/notify"
        echo "Keep the tunnel running; update the portal if the public URL changes."
        echo "nginx: pass Host and X-Forwarded-Proto; connect to this Demo through loopback."
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
