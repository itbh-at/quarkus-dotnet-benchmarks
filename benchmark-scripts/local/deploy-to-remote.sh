#!/usr/bin/env bash
# Deploy this repository's working tree to a remote benchmark target via rsync.
#
# This replaces the previous git-clone-on-remote workflow used by the upstream
# perf-lab. The remote never needs git access, never pulls — it only receives
# whatever the local working tree contains. Local edits to scripts, qDup yml,
# or app sources can therefore be tested on the remote in seconds.
#
# Layout on the remote (relative to the deployment dir):
#   apps/                     — Quarkus and dotnet applications
#   benchmark-scripts/local/  — local-side scripts referenced by qDup
#   benchmark-scripts/remote/ — qDup yml files and helpers
#   RUN_INFO                  — git metadata captured at deploy time
#
# The local repo's database/, results/, reports/, target/, and .git/ are
# excluded — the remote produces logs, not stores history.

set -euo pipefail

# --- Preconditions -----------------------------------------------------------
command -v rsync >/dev/null 2>&1 || {
  echo "ERROR: rsync is required but not installed." >&2
  exit 1
}
command -v git >/dev/null 2>&1 || {
  echo "ERROR: git is required but not installed." >&2
  exit 1
}

# --- Argument parsing --------------------------------------------------------
usage() {
  cat <<EOF
Usage: $(basename "$0") <user@host[:path]> [extra rsync args...]

If no path is given on the target spec, deploys to ~/quarkus-dotnet-benchmarks
on the remote (matching config.deployment.dir in main.yml).

Examples:
  $(basename "$0") perf@perf-lab.example.com
  $(basename "$0") perf@perf-lab.example.com:/opt/benchmarks
  $(basename "$0") perf@perf-lab.example.com -n         # dry run
EOF
}

if [[ $# -lt 1 || "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 1
fi

TARGET="$1"
shift

# Default deployment dir if no path was specified.
if [[ "$TARGET" != *:* ]]; then
  TARGET="${TARGET}:quarkus-dotnet-benchmarks"
fi

# --- Locate repo root --------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

# --- Generate RUN_INFO -------------------------------------------------------
# capture-repo-info on the remote reads this file. With rsync deployment there
# is no .git on the target, so we capture commit metadata locally.
#
# Deliberately *not* captured: hostname, username, or any path under /Users.
# That data has no analytical value (we don't compare runs on developer-machine
# identity) and only adds risk of leaking PII into committed SQL dumps and
# shared reports. An opaque UUID identifies the run instead.
RUN_INFO_FILE="$(mktemp)"
trap 'rm -f "${RUN_INFO_FILE}"' EXIT

dirty=false
git diff --quiet 2>/dev/null && git diff --cached --quiet 2>/dev/null || dirty=true

# uuidgen exists on macOS and most Linux distros; fall back to /proc on Linux,
# and to a deterministic openssl rand on systems lacking both.
if command -v uuidgen >/dev/null 2>&1; then
  run_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
elif [ -r /proc/sys/kernel/random/uuid ]; then
  run_id=$(cat /proc/sys/kernel/random/uuid)
else
  run_id=$(openssl rand -hex 16 | sed 's/\(........\)\(....\)\(....\)\(....\)\(.*\)/\1-\2-\3-\4-\5/')
fi

cat > "${RUN_INFO_FILE}" <<EOF
run_id: ${run_id}
commit: $(git rev-parse HEAD)
short_commit: $(git rev-parse --short HEAD)
branch: $(git rev-parse --abbrev-ref HEAD)
dirty: ${dirty}
deployed_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

# --- Rsync -------------------------------------------------------------------
echo "==> Deploying ${REPO_ROOT} to ${TARGET}"
echo

# Keep transient remote artifacts (logs/, builds/) safe across redeploys via
# --exclude (they're not in our local source, and we don't want --delete to
# remove them).
rsync -avz --delete \
  --exclude='/.git/' \
  --exclude='/.idea/' \
  --exclude='/.vscode/' \
  --exclude='.DS_Store' \
  --exclude='/target/' --exclude='**/target/' \
  --exclude='**/bin/Debug/' \
  --exclude='**/bin/Release/' \
  --exclude='**/bin/obj/' \
  --exclude='**/obj/' \
  --exclude='/database/' \
  --exclude='/reports/' \
  --exclude='/results/' \
  --exclude='/logs/' \
  --exclude='/builds/' \
  "$@" \
  apps \
  benchmark-scripts \
  upstream.yml \
  CLAUDE.md \
  "${TARGET}/"

# RUN_INFO sent separately so it doesn't need to live in the working tree.
rsync -av "$@" "${RUN_INFO_FILE}" "${TARGET}/RUN_INFO"

echo
echo "==> Deploy complete."
echo

# Strip user@ prefix and any :path suffix to get bare host for the hint.
hint_user_host="${TARGET%%:*}"
hint_host="${hint_user_host#*@}"
hint_user="${hint_user_host%@*}"

echo "Run benchmarks (qDup orchestrates from your local machine):"
echo "    cd benchmark-scripts/remote"
echo "    bash run-benchmarks.sh --host ${hint_host} --user ${hint_user}"
