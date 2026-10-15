#!/usr/bin/env bash
set -u
set -o pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
report_dir=$(mktemp -d "${TMPDIR:-/tmp}/monitor-verification.XXXXXXXX")
compose_project="monitor-verify-$(basename "$report_dir" | tr '[:upper:].' '[:lower:]-')"
compose_started=false
failed=0

cd "$project_root" || exit 1
printf 'Отчёт: %s\n' "$report_dir"
printf 'stage\tresult\texit_code\tlog\n' > "$report_dir/results.tsv"

record() {
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$report_dir/results.tsv"
  printf '%s: %s\n' "$1" "$2"
}

run_stage() {
  local name=$1
  local log="$report_dir/$name.log"
  printf 'Запуск %s…\n' "$name"
  if perform_stage "$name" > "$log" 2>&1; then
    record "$name" PASS 0 "$log"
    return 0
  else
    local code=$?
    record "$name" FAIL "$code" "$log"
    failed=1
    return "$code"
  fi
}

verify_backend() (
  cd "$project_root/backend" || exit 1
  if [ -n "${MONITOR_MAVEN_REPO:-}" ]; then
    ./mvnw --batch-mode "-Dmaven.repo.local=$MONITOR_MAVEN_REPO" clean verify
  else
    ./mvnw --batch-mode clean verify
  fi
)

verify_frontend() (
  cd "$project_root/frontend" || exit 1
  if [ -n "${MONITOR_NPM_CACHE:-}" ]; then
    npm ci --cache "$MONITOR_NPM_CACHE" || exit $?
  else
    npm ci || exit $?
  fi
  npm run lint && npm run format:check && npm run typecheck && npm test && npm run build
)

verify_compose() {
  ./scripts/init-env.sh || return $?
  compose_started=true
  docker compose --project-name "$compose_project" up --build --wait --wait-timeout 240 postgres kafka backend frontend || return $?
  docker compose --project-name "$compose_project" run --rm --no-deps kafka-init || return $?
  python3 scripts/smoke.py
}

perform_stage() {
  case "$1" in
    backend) verify_backend ;;
    frontend) verify_frontend ;;
    compose) verify_compose ;;
    *) return 2 ;;
  esac
}

cleanup() {
  if "$compose_started"; then
    docker compose --project-name "$compose_project" logs --no-color > "$report_dir/compose-runtime.log" 2>&1
    if ! docker compose --project-name "$compose_project" down --volumes > "$report_dir/compose-cleanup.log" 2>&1; then
      record cleanup FAIL 1 "$report_dir/compose-cleanup.log"
      failed=1
    fi
  fi
}
trap 'cleanup; exit "$failed"' EXIT
trap 'failed=1; exit 1' INT TERM

backend_ok=false
frontend_ok=false
if run_stage backend; then backend_ok=true; fi
if run_stage frontend; then frontend_ok=true; fi
if "$backend_ok" && "$frontend_ok"; then
  run_stage compose || true
else
  record compose NOT_RUN - 'backend/frontend verification failed'
  failed=1
fi
printf 'Результаты: %s/results.tsv\n' "$report_dir"
cleanup
trap - EXIT
exit "$failed"
