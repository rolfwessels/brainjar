#!/usr/bin/env bash
# Queries existing MemPalace + Recall indexes only. Does NOT clear ~/.mempalace/palace
# or ~/.recall — clearing would empty search results until you mine again. For a clean
# comparison from scratch, run scripts/clear-and-mine.sh, then this script.
set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Usage: .cursor/skills/retrieval-benchmark/scripts/run-retrieval-benchmark.sh

Runs mempalace search and ./brainjar --search for each line in benchmark/retrieval-questions.txt
and writes temp/mp_qN.txt, temp/recall_qN.txt, temp/retrieval-report.md (includes wall-clock timings;
each log line 1 is # wall-clock: …). If temp/mining-timings.md exists (from clear-and-mine), it is included
near the top of the report (before search timings).

Does NOT delete or reset MemPalace or Recall storage. It only searches whatever is already
indexed. To rebuild indexes from empty, run .cursor/skills/retrieval-benchmark/scripts/clear-and-mine.sh --yes (from repo root).

Env: MP_WING (default docs), RECALL_SHELF (default docs)
EOF
  exit 0
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
QUESTIONS="${ROOT}/benchmark/retrieval-questions.txt"
OUT="${ROOT}/temp"
MP_WING="${MP_WING:-docs}"
RECALL_SHELF="${RECALL_SHELF:-docs}"

mkdir -p "$OUT"

if [[ ! -f "$QUESTIONS" ]]; then
  echo "Missing question file: $QUESTIONS" >&2
  exit 1
fi

if ! command -v mempalace >/dev/null 2>&1; then
  echo "mempalace not on PATH (pip install mempalace)" >&2
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
fi

rm -f "${OUT}"/mp_q*.txt "${OUT}"/recall_q*.txt "${OUT}"/mp_q*.raw "${OUT}"/recall_q*.raw "${OUT}"/retrieval-report.md

delta_sec() {
  awk -v a="$1" -v b="$2" 'BEGIN { printf "%.3f", b - a }'
}

add_float() {
  awk -v a="$1" -v b="$2" 'BEGIN { printf "%.3f", a + b }'
}

declare -a MP_SEC RECALL_SEC
n=0
search_phase_start=$(date +%s.%N)
while IFS= read -r line || [[ -n "${line:-}" ]]; do
  [[ -z "${line//[[:space:]]/}" ]] && continue
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  n=$((n + 1))
  printf 'Running Q%d\n' "$n"

  t0=$(date +%s.%N)
  mempalace search "$line" --wing "$MP_WING" >"${OUT}/mp_q${n}.raw" 2>&1
  t1=$(date +%s.%N)
  mp_s=$(delta_sec "$t0" "$t1")
  MP_SEC[$n]=$mp_s
  {
    echo "# wall-clock: ${mp_s}s"
    cat "${OUT}/mp_q${n}.raw"
  } >"${OUT}/mp_q${n}.txt"
  rm -f "${OUT}/mp_q${n}.raw"

  t0=$(date +%s.%N)
  (cd "$ROOT" && ./brainjar --search "$line" --shelf "$RECALL_SHELF") >"${OUT}/recall_q${n}.raw" 2>&1
  t1=$(date +%s.%N)
  r_s=$(delta_sec "$t0" "$t1")
  RECALL_SEC[$n]=$r_s
  {
    echo "# wall-clock: ${r_s}s"
    cat "${OUT}/recall_q${n}.raw"
  } >"${OUT}/recall_q${n}.txt"
  rm -f "${OUT}/recall_q${n}.raw"

  printf '  Q%d  MemPalace %ss  Recall %ss\n' "$n" "$mp_s" "$r_s"
done <"$QUESTIONS"
search_phase_end=$(date +%s.%N)
search_phase_wall=$(delta_sec "$search_phase_start" "$search_phase_end")

mp_sum="0.000"
recall_sum="0.000"
for ((i = 1; i <= n; i++)); do
  mp_sum=$(add_float "$mp_sum" "${MP_SEC[$i]}")
  recall_sum=$(add_float "$recall_sum" "${RECALL_SEC[$i]}")
done
if [[ "$n" -gt 0 ]]; then
  mp_mean=$(awk -v s="$mp_sum" -v c="$n" 'BEGIN { printf "%.3f", s / c }')
  recall_mean=$(awk -v s="$recall_sum" -v c="$n" 'BEGIN { printf "%.3f", s / c }')
else
  mp_mean="0.000"
  recall_mean="0.000"
fi

REPORT="${OUT}/retrieval-report.md"
{
  echo "# Retrieval benchmark report"
  echo
  echo "Generated: $(date -Iseconds)"
  echo
  echo "- Questions file: \`benchmark/retrieval-questions.txt\`"
  echo "- MemPalace: \`mempalace search \"…\" --wing ${MP_WING}\`"
  echo "- Recall: \`./brainjar --search \"…\" --shelf ${RECALL_SHELF}\`"
  echo
  if [[ -f "${OUT}/mining-timings.md" ]]; then
    cat "${OUT}/mining-timings.md"
    echo
    echo "---"
    echo
  fi
  echo "## Search timings (wall-clock)"
  echo
  echo "Per question: sequential (MemPalace, then Recall). Times are in seconds."
  echo
  echo "| # | MemPalace (s) | Recall (s) |"
  echo "|---|---------------|------------|"
  for ((i = 1; i <= n; i++)); do
    echo "| ${i} | ${MP_SEC[$i]} | ${RECALL_SEC[$i]} |"
  done
  echo "| **Σ** | **${mp_sum}** | **${recall_sum}** |"
  echo "| **mean** | **${mp_mean}** | **${recall_mean}** |"
  echo
  echo "- **Search phase wall-clock** (first query start → last query end): **${search_phase_wall}s**"
  echo "- Raw logs start with \`# wall-clock: …\` on line 1."
  echo
  echo "## Index"
  echo
  echo "| # | MemPalace | Recall |"
  echo "|---|-----------|--------|"
  i=0
  while IFS= read -r line || [[ -n "${line:-}" ]]; do
    [[ -z "${line//[[:space:]]/}" ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    i=$((i + 1))
    echo "| ${i} | \`temp/mp_q${i}.txt\` | \`temp/recall_q${i}.txt\` |"
  done <"$QUESTIONS"
  echo
  i=0
  while IFS= read -r line || [[ -n "${line:-}" ]]; do
    [[ -z "${line//[[:space:]]/}" ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    i=$((i + 1))
    echo "## Question ${i}"
    echo
    echo "${line}"
    echo
    echo "- MemPalace: \`temp/mp_q${i}.txt\` (${MP_SEC[$i]}s)"
    echo "- Recall: \`temp/recall_q${i}.txt\` (${RECALL_SEC[$i]}s)"
    echo
  done <"$QUESTIONS"
  echo "## Evaluation"
  echo
  echo "Add scores or notes here, or compare to \`.cursor/skills/retrieval-benchmark/retrieval-comparison-mempalace-vs-recall.md\`."
} >"$REPORT"

printf 'Done. %d question pairs → %s\n' "$n" "$OUT"
printf 'Report: %s\n' "$REPORT"
