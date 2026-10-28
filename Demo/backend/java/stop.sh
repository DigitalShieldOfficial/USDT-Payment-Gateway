#!/bin/bash
# DSPay mock merchant (Java) — stop script

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$ROOT/server.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "PID file not found, server may not be running"
    exit 0
fi

PID=$(cat "$PID_FILE")

if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    rm -f "$PID_FILE"
    echo "Server stopped (PID=$PID)"
else
    echo "Process no longer exists (PID=$PID), cleaning up PID file"
    rm -f "$PID_FILE"
fi
