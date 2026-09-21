#!/bin/bash
# DSPay mock merchant (Java) — background start script

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

if ! command -v java >/dev/null 2>&1; then
    echo "Java not found. Install JDK 8 or newer before starting." >&2
    exit 1
fi
JAVA_VERSION_LINE=$(java -version 2>&1 | head -n 1)
JAVA_VERSION="${JAVA_VERSION_LINE#*\"}"
JAVA_VERSION="${JAVA_VERSION%%\"*}"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
if [ "$JAVA_MAJOR" = 1 ]; then
    JAVA_MINOR="${JAVA_VERSION#*.}"
    JAVA_MAJOR="${JAVA_MINOR%%.*}"
fi
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || (( JAVA_MAJOR < 8 )); then
    echo "JDK 8 or newer is required. Current: $JAVA_VERSION_LINE" >&2
    exit 1
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
        read -r -p "$description ($name): " value || exit 1
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
export API_SECRET

echo "Starting server..."

cd "$ROOT"
mkdir -p "$LOG_DIR" "$ROOT/build"
JAVA_ARGS=("-Dport=$PORT" "-DdspayBase=$DSPAY_BASE_URL" "-DpublicBase=$PUBLIC_BASE_URL" "-DmerchantNo=$MERCHANT_NO")
# Compile first: single-file source launch (java Foo.java) is a Java 11+ feature;
# javac + java -cp keeps the demo runnable on JDK 8. (javac -d requires the
# directory to exist beforehand on JDK 8, hence mkdir above.)
if ! javac -d "$ROOT/build" src/DspayMockMerchant.java >> "$LOG_FILE" 2>&1; then
    echo "Compilation failed. Recent logs:" >&2
    tail -n 20 "$LOG_FILE" >&2
    exit 1
fi
printf '\n=== Starting %s ===\n' "$(date)" >> "$LOG_FILE"
LOG_START_LINE=$(wc -l < "$LOG_FILE")
nohup java "${JAVA_ARGS[@]}" -cp "$ROOT/build" DspayMockMerchant >> "$LOG_FILE" 2>&1 &
PID=$!
echo $PID > "$PID_FILE"

for ((attempt = 0; attempt < 30; attempt++)); do
    if tail -n +"$((LOG_START_LINE + 1))" "$LOG_FILE" | grep -q '^Mock merchant:' && kill -0 "$PID" 2>/dev/null; then
        echo "Server started successfully (PID=$PID)"
        echo "Log file: $LOG_FILE"
        echo "Port: $PORT"
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
