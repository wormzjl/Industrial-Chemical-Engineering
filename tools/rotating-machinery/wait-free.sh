#!/usr/bin/env bash
# Waits until no test, dev-client or campaign JVM runs on the machine (AGENTS.md: no suite beside a dev client).
busy(){ powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|gradle-wrapper.jar|GradleWorkerMain.*Test|net.neoforged.devlaunch|runClient|runclient|runMcpClient' } | Measure-Object | Select-Object -ExpandProperty Count" | tr -d '\r'; }
until [ "$(busy)" = "0" ]; do sleep 30; done
echo "machine free at $(date '+%H:%M:%S')"
