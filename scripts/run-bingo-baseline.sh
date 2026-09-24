#!/bin/zsh
set -euo pipefail

clipboard_environment=$(pbpaste)
export PUBG_API_KEY=$(print -r -- "$clipboard_environment" | tr ';' '\n' | sed -n 's/^PUBG_API_KEY=//p')
unset clipboard_environment
printf '' | pbcopy

if [[ -z "$PUBG_API_KEY" ]]; then
  run_configuration=$(rg -l --hidden --no-messages "PUBG_API_KEY" \
    "$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.2/workspace" | head -1)
  if [[ -n "$run_configuration" ]]; then
    export PUBG_API_KEY=$(python3 - "$run_configuration" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for option in root.iter("option"):
    value = option.get("value", "")
    for item in value.split(";"):
        if item.startswith("PUBG_API_KEY="):
            print(item.removeprefix("PUBG_API_KEY="))
            raise SystemExit
PY
)
  fi
fi

if [[ -z "$PUBG_API_KEY" ]]; then
  print -u2 -- "PUBG_API_KEY was not found in the copied local run configuration"
  exit 2
fi

export DISCORD_BOT_TOKEN=baseline-unused
test_selector="${1:-BingoProductionDumpBaselineTests}"
log_file="${2:-target/bingo-baseline-app-valid.log}"
./mvnw -Dtest="$test_selector" test -Dspring.main.banner-mode=off 2>&1 | tee "$log_file"
exit ${pipestatus[1]}
