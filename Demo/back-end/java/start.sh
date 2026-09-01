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

echo "Starting server..."

cd "$ROOT"
mkdir -p "$LOG_DIR"
JAVA_ARGS=()
[ -n "$PORT" ] && JAVA_ARGS+=("-Dport=$PORT")
[ -n "$DSPAY_BASE_URL" ] && JAVA_ARGS+=("-DdspayBase=$DSPAY_BASE_URL")
[ -n "$PUBLIC_BASE_URL" ] && JAVA_ARGS+=("-DpublicBase=$PUBLIC_BASE_URL")
[ -n "$MERCHANT_NO" ] && JAVA_ARGS+=("-DmerchantNo=$MERCHANT_NO")
[ -n "$API_SECRET" ] && JAVA_ARGS+=("-DapiSecret=$API_SECRET")
nohup java "${JAVA_ARGS[@]}" src/DspayMockMerchant.java >> "$LOG_FILE" 2>&1 &
PID=$!
echo $PID > "$PID_FILE"

sleep 1

if kill -0 "$PID" 2>/dev/null; then
    echo "Server started successfully (PID=$PID)"
    echo "Log file: $LOG_FILE"
    echo "Port: ${PORT:-3000}"
    echo ""
    echo "View logs: tail -f '$LOG_FILE'"
    echo "Stop server: ./stop.sh"
else
    echo "Server failed to start. Check: java src/DspayMockMerchant.java"
    rm -f "$PID_FILE"
    exit 1
fi
