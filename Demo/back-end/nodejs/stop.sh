#!/bin/bash
# DSPay mock merchant — 停止脚本

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$ROOT/server.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "PID 文件不存在，服务可能未在运行"
    exit 0
fi

PID=$(cat "$PID_FILE")

if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    rm -f "$PID_FILE"
    echo "服务已停止 (PID=$PID)"
else
    echo "进程已不存在 (PID=$PID)，清理 PID 文件"
    rm -f "$PID_FILE"
fi
