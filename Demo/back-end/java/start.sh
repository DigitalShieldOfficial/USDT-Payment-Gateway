#!/bin/bash
# DSPay mock merchant (Java) — background start script

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$ROOT/server.pid"

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
nohup java -Dfile.encoding=UTF-8 src/DspayMockMerchant.java >> /dev/null 2>&1 &
PID=$!
echo $PID > "$PID_FILE"

sleep 1

if kill -0 "$PID" 2>/dev/null; then
    echo "Server started successfully (PID=$PID)"
    echo "Log file: $ROOT/logs/server.log"
    echo "Port: ${PORT:-3000}"
    echo ""
    echo "View logs: tail -f logs/server.log"
    echo "Stop server: ./stop.sh"
else
    echo "Server failed to start. Check: java -Dfile.encoding=UTF-8 src/DspayMockMerchant.java"
    rm -f "$PID_FILE"
    exit 1
fi
