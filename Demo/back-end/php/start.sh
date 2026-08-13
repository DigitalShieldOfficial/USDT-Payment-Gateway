#!/bin/sh

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PORT=${PORT:-3000}

cd "$ROOT"
exec php -S "localhost:$PORT" server.php
