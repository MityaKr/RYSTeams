#!/bin/sh
# Сервер и два клиента на этой машине. Вердикты: строки [duo] в run-duo/*/logs/latest.log
cd "$(dirname "$0")/.." || exit 1
./gradlew runDuoServer --console=plain > run-duo/server.log 2>&1 &
sleep 45
./gradlew runDuoAlpha --console=plain > run-duo/alpha.log 2>&1 &
sleep 20
./gradlew runDuoBeta --console=plain > run-duo/beta.log 2>&1 &
wait
