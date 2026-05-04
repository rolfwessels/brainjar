#!/usr/bin/env bash
# Destructive: removes Recall + MemPalace palace data, optional docs/mempalace artifacts,
# then re-ingests docs/ into MemPalace and Recall (same flow as the research doc).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
DOCS="${ROOT}/docs"

usage() {
  cat <<'EOF'
Usage: .cursor/skills/retrieval-benchmark/scripts/clear-and-mine.sh --yes

  Deletes:
    - ~/.recall
    - ~/.mempalace/palace
    - docs/mempalace.yaml and docs/entities.json (if present)

  Then runs:
    mempalace init --yes <repo>/docs && mempalace mine <repo>/docs
    ./brainjar --mine docs --shelf docs

  Requires mempalace on PATH, repo root .env for brainjar, and ./brainjar wrapper.
  Pass --yes to confirm (no prompt).

  Writes temp/mining-timings.md (per-step seconds). Re-run the search benchmark to append
  that file into temp/retrieval-report.md.

Env: RECALL_SHELF (default docs) — passed to brainjar --mine
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ "${1:-}" != "--yes" ]]; then
  echo "Refusing to run without --yes (destructive: rm -rf ~/.recall ~/.mempalace/palace)." >&2
  usage >&2
  exit 1
fi

if ! command -v mempalace >/dev/null 2>&1; then
  echo "mempalace not on PATH (pip install mempalace)" >&2
  exit 1
fi

if [[ ! -d "$DOCS" ]]; then
  echo "Missing corpus directory: $DOCS" >&2
  exit 1
fi

if [[ ! -x "${ROOT}/brainjar" ]]; then
  chmod +x "${ROOT}/brainjar" 2>/dev/null || true
fi

if [[ ! -f "${ROOT}/brainjar" ]]; then
  echo "Missing ${ROOT}/brainjar" >&2
  exit 1
fi

if [[ -f "${ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT}/.env"
  set +a
else
  echo "Warning: no ${ROOT}/.env — brainjar may fail." >&2
fi

RECALL_SHELF="${RECALL_SHELF:-docs}"

delta_sec() {
  awk -v a="$1" -v b="$2" 'BEGIN { printf "%.3f", b - a }'
}

add_float() {
  awk -v a="$1" -v b="$2" 'BEGIN { printf "%.3f", a + b }'
}

OUT="${ROOT}/temp"
mkdir -p "$OUT"
MINING_REPORT="${OUT}/mining-timings.md"

echo "Removing ~/.recall ~/.mempalace/palace …"
rm -rf "${HOME}/.recall" "${HOME}/.mempalace/palace"
rm -f "${DOCS}/mempalace.yaml" "${DOCS}/entities.json"

echo "MemPalace init on ${DOCS} …"
t0=$(date +%s.%N)
mempalace init --yes "$DOCS"
t1=$(date +%s.%N)
init_s=$(delta_sec "$t0" "$t1")
printf '  mempalace init: %ss\n' "$init_s"

echo "MemPalace mine on ${DOCS} …"
t0=$(date +%s.%N)
mempalace mine "$DOCS"
t1=$(date +%s.%N)
mine_mp_s=$(delta_sec "$t0" "$t1")
printf '  mempalace mine: %ss\n' "$mine_mp_s"

echo "Recall mine docs → shelf ${RECALL_SHELF} …"
t0=$(date +%s.%N)
(cd "$ROOT" && ./brainjar --mine docs --shelf "$RECALL_SHELF")
t1=$(date +%s.%N)
mine_recall_s=$(delta_sec "$t0" "$t1")
printf '  brainjar --mine: %ss\n' "$mine_recall_s"

mining_total=$(add_float "$(add_float "$init_s" "$mine_mp_s")" "$mine_recall_s")

{
  echo "## Mining timings (clear-and-mine)"
  echo
  echo "Generated: $(date -Iseconds)"
  echo
  echo "Wall-clock seconds per step (sequential)."
  echo
  echo "| Step | Seconds |"
  echo "|------|---------|"
  echo "| \`mempalace init --yes docs\` | ${init_s} |"
  echo "| \`mempalace mine docs\` | ${mine_mp_s} |"
  echo "| \`./brainjar --mine docs --shelf ${RECALL_SHELF}\` | ${mine_recall_s} |"
  echo "| **Σ (mining)** | **${mining_total}** |"
  echo
  echo "The search benchmark runner inlines this block near the top of \`temp/retrieval-report.md\` when that file is generated."
} >"$MINING_REPORT"

printf 'Mining total (init + mempalace mine + recall mine): %ss\n' "$mining_total"
printf 'Wrote %s\n' "$MINING_REPORT"
echo "Done. From repo root: bash .cursor/skills/retrieval-benchmark/scripts/run-retrieval-benchmark.sh"
