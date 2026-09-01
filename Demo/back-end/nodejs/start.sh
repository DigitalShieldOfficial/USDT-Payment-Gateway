#!/bin/bash
# DSPay mock merchant — 后台启动脚本

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$ROOT/server.pid"
LOG_DIR="$ROOT/logs"
LOG_FILE="$LOG_DIR/server.log"

# 已运行则跳过
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "服务已在运行 (PID=$PID)，如需重启请先执行 ./stop.sh"
        exit 1
    fi
    rm -f "$PID_FILE"
fi

echo "启动服务..."

cd "$ROOT"
mkdir -p "$LOG_DIR"
nohup node src/server.js >> "$LOG_FILE" 2>&1 &
PID=$!
echo $PID > "$PID_FILE"

sleep 1

if kill -0 "$PID" 2>/dev/null; then
    echo "服务启动成功 (PID=$PID)"
    echo "日志文件: $LOG_FILE"
    echo "监听端口: ${PORT:-3000}"
    echo ""
    echo "查看日志: tail -f '$LOG_FILE'"
    echo "停止服务: ./stop.sh"
else
    echo "服务启动失败，请检查: node src/server.js"
    rm -f "$PID_FILE"
    exit 1
fi
