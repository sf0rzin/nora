#!/usr/bin/env bash
# Prints the measured test coverage of one service, from the report its test run just wrote.
#
#   scripts/report-coverage.sh backend   # reads services/api/target/site/jacoco/jacoco.csv
#   scripts/report-coverage.sh worker    # reads services/nlp-worker/.coverage
#   scripts/report-coverage.sh web       # reads apps/web/coverage/coverage-summary.json
#
# This script MEASURES NOTHING. It reads the artifact the test run already produced, so the
# number in a CI log and the number on a workstation come from one implementation — the same
# argument `tests/pii_corpus/harness.py` makes for the two PII rates, and the same reason it
# runs as its own CI step: to put a number where a reviewer can read it without re-running
# anything.
#
# Why it exists: `docs/product/roadmap.md` and `docs/engineering/standards.md` carried backend
# and worker coverage figures measured on 2026-05-13 and marked "to be re-measured" for three
# months while roughly seventy pull requests merged. A figure nobody re-measures is a figure
# nobody can defend. The fix is not a better one-off measurement, it is a measurement that
# happens on every run.
#
# NOT A GATE, on purpose. It exits 0 whatever the numbers say. The coverage gates this
# repository actually enforces are elsewhere and are untouched by this script:
#   - `services/api/pom.xml`      — three JaCoCo rules under one `check-iam-coverage` execution,
#                                   haltOnFailure, bound to `verify`: CLASS on PolicyEvaluator,
#                                   PACKAGE on `domain.iam`, and a whole-BUNDLE floor
#   - `.github/workflows/ci.yml`  — `pytest --cov=nora_nlp.services.pii_shield
#                                   --cov-fail-under=90` over that one module
#   - `apps/web/vitest.config.mts` — per-module `coverage.thresholds`, applied by the test run
#                                   itself (ADR 0042). The modules are NOT listed here: this
#                                   script reads them out of that file, and a second copy of
#                                   the list is the thing that went stale last time.
# Turning THIS into a gate would mean picking a global threshold, and ADR 0018 already
# considered and rejected exactly that (Alternatives Considered, item 1).
#
# When the report is missing it says so and still exits 0: the step that should have produced
# it has already failed loudly by then, and a second red step adds noise, not information.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

JACOCO_CSV="services/api/target/site/jacoco/jacoco.csv"
WORKER_DIR="services/nlp-worker"
WEB_SUMMARY="apps/web/coverage/coverage-summary.json"

usage() {
  echo "usage: scripts/report-coverage.sh <backend|worker|web>" >&2
  exit 2
}

# Everything goes to stdout AND, under GitHub Actions, to the run summary page — which is the
# copy a human reads without opening a job log.
summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    cat >> "$GITHUB_STEP_SUMMARY"
  else
    cat > /dev/null
  fi
}

# ---------------------------------------------------------------------------
# Backend — JaCoCo
#
# jacoco.csv is one row per CLASS with the columns
#   GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,
#   LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
# so every figure below is a sum over rows, never a re-derivation of anything.
#
# INSTRUCTION comes first because it is the counter this repository already gates on
# (`services/api/pom.xml` uses `<counter>INSTRUCTION</counter>`) and the counter ADR 0018's
# backend figure was expressed in. LINE sits beside it because that is what most readers assume
# a bare percentage means, and the two are not the same number.
#
# The two named areas come from ADR 0018, which sets its >85% target on "IAM, Auth, PII" rather
# than on a global figure. On the backend that is the `*.iam.*` packages and the
# `*.identity.*` / `*.security.*` packages; PII lives in the worker.
# ---------------------------------------------------------------------------
report_backend() {
  if [ ! -f "$JACOCO_CSV" ]; then
    echo "BACKEND COVERAGE: no report at $JACOCO_CSV"
    echo "  'mvn -B verify' did not reach the jacoco:report execution."
    return 0
  fi

  local rows
  rows=$(
    awk -F, -v OFS='\t' '
      function pct(cov, missed,   total) {
        total = cov + missed
        if (total == 0) return "n/a"
        return sprintf("%.1f%% (%d/%d)", 100 * cov / total, cov, total)
      }
      function add(key) {
        im[key] += $4; ic[key] += $5
        bm[key] += $6; bc[key] += $7
        lm[key] += $8; lc[key] += $9
      }
      NR == 1 { next }
      {
        add("all")
        if ($2 ~ /\.iam($|\.)/) add("iam")
        if ($2 ~ /\.(identity|security)($|\.)/) add("auth")
        if ($3 == "PolicyEvaluator") add("policyeval")
      }
      END {
        n = split("all iam auth policyeval", order, " ")
        labels["all"]        = "overall (all main sources)"
        labels["iam"]        = "IAM packages (*.iam)"
        labels["auth"]       = "Auth packages (*.identity, *.security)"
        labels["policyeval"] = "PolicyEvaluator (the gated CLASS rule)"
        for (i = 1; i <= n; i++) {
          k = order[i]
          if (ic[k] + im[k] == 0) continue
          print labels[k], pct(ic[k], im[k]), pct(bc[k], bm[k]), pct(lc[k], lm[k])
        }
      }
    ' "$JACOCO_CSV"
  )

  echo "BACKEND COVERAGE (JaCoCo)"
  echo "source:      $JACOCO_CSV"
  echo "produced by: mvn -B verify   (jacoco:report, phase verify)"
  echo
  printf '%-40s %-24s %-22s %-22s\n' "scope" "instruction" "branch" "line"
  echo "$rows" | while IFS=$'\t' read -r label instruction branch line; do
    [ -n "$label" ] || continue
    printf '%-40s %-24s %-22s %-22s\n' "$label" "$instruction" "$branch" "$line"
  done
  echo
  echo "Three of these scopes are gated by services/api/pom.xml (haltOnFailure, phase verify):"
  echo "the whole BUNDLE, the domain.iam PACKAGE and the PolicyEvaluator CLASS. The Auth row is"
  echo "a report. The thresholds live in that pom and are not repeated here."

  {
    echo "### Backend coverage, measured by this run"
    echo
    echo "\`mvn -B verify\` wrote \`$JACOCO_CSV\`; this is a report, not a gate."
    echo "The gate is the JaCoCo rule on \`PolicyEvaluator\` in \`services/api/pom.xml\`."
    echo
    echo "| scope | instruction | branch | line |"
    echo "|---|---|---|---|"
    echo "$rows" | while IFS=$'\t' read -r label instruction branch line; do
      [ -n "$label" ] || continue
      echo "| $label | $instruction | $branch | $line |"
    done
    echo
  } | summary
}

# ---------------------------------------------------------------------------
# Worker — coverage.py
#
# Reads the `.coverage` that the `--cov=nora_nlp` run left behind. The scope is the whole point
# of printing this: CI's gate is `--cov=nora_nlp.services.pii_shield`, ONE module, and quoting
# that number as "the worker's coverage" overstates it by a wide margin. Both are printed here,
# side by side, so neither can be read as the other.
# ---------------------------------------------------------------------------
cov_total() {
  # `coverage report` exits non-zero when a filter selects no file; under `set -e` that would
  # take the whole script down over a formatting detail, so the failure becomes "n/a".
  (cd "$WORKER_DIR" && python -m coverage report --precision=1 --format=total "$@") 2> /dev/null ||
    echo "n/a"
}

report_worker() {
  if [ ! -f "$WORKER_DIR/.coverage" ]; then
    echo "WORKER COVERAGE: no coverage data at $WORKER_DIR/.coverage"
    echo "  The test step runs 'pytest --cov=nora_nlp'; it did not reach the end."
    return 0
  fi

  local table total shield
  table=$( (cd "$WORKER_DIR" && python -m coverage report --precision=1) || echo "(report failed)")
  total=$(cov_total)
  shield=$(cov_total --include='*/services/pii_shield.py')

  echo "WORKER COVERAGE (coverage.py)"
  echo "source:      $WORKER_DIR/.coverage"
  echo "produced by: pytest --cov=nora_nlp   (whole suite, whole package)"
  echo
  echo "worker-wide (nora_nlp)  : ${total}%"
  echo "services/pii_shield.py  : ${shield}%   <- the single module the CI gate scopes to (>= 90)"
  echo
  echo "$table"

  {
    echo "### Worker coverage, measured by this run"
    echo
    echo "| scope | coverage |"
    echo "|---|---|"
    echo "| worker-wide (\`nora_nlp\`, whole suite) | ${total}% |"
    echo "| \`services/pii_shield.py\`, the gated module | ${shield}% |"
    echo
    echo "\`--cov-fail-under=90\` applies to the second row only. The first row is a report."
    echo
    echo "<details><summary>per file</summary>"
    echo
    echo '```'
    echo "$table"
    echo '```'
    echo
    echo "</details>"
    echo
  } | summary
}

# ---------------------------------------------------------------------------
# Web — Vitest + @vitest/coverage-v8
#
# Reads the `coverage/coverage-summary.json` that `npm run test:coverage` left behind. Keys are
# ABSOLUTE paths plus a `total` entry, so everything below is a lookup, never a re-derivation.
#
# Two scopes, printed together for the same reason as the worker's: the modules the gate scopes
# to sit far above the application around them, because the screens have no unit tests.
# Publishing only the second number would describe an application that does not exist. The
# whole-app row is the honest denominator; the per-module rows are the gate.
#
# No figure is quoted in this comment on purpose. An earlier version said "a low single-digit
# percentage", which was true when it was written and stopped being true without anything
# noticing — in the header of the script this repository added SO THAT nobody would have to
# trust a number written down somewhere.
#
# `node`, not `jq`: the `web` job already has Node and does not have jq.
# ---------------------------------------------------------------------------
report_web() {
  if [ ! -f "$WEB_SUMMARY" ]; then
    echo "WEB COVERAGE: no summary at $WEB_SUMMARY"
    echo "  The test step runs 'npm run test:coverage'; it did not reach the end."
    return 0
  fi

  local rows
  rows=$(
    node -e '
      const fs = require("node:fs");
      const path = require("node:path");
      const summary = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));

      // DERIVED from `apps/web/vitest.config.mts`, not copied out of it. This was a
      // hand-maintained array under a comment reading "anything added there should be added
      // here", and it carried five of the six thresholds — the missing one being
      // `src/lib/iam/policy-document.ts`, whose whole job is to REFUSE a policy document the
      // form cannot represent exactly. A gated module with no reported row is the one nobody
      // notices sliding, and an instruction to keep two lists in step is not a mechanism.
      //
      // Text extraction rather than an import, because this runs from the repository root and
      // that file is TypeScript importing `vitest/config`: evaluating it would make a coverage
      // REPORT depend on node_modules being installed under apps/web. Inside the thresholds
      // object the module paths are the only QUOTED keys — the nested `statements`/`branches`
      // ones are bare identifiers — so the match below is exact rather than approximate.
      // \x27 and \x60 are the quote and the backtick, spelled in hex because this whole program
      // is a single-quoted shell argument.
      const readGatedModules = (webRoot) => {
        // Every failure here returns an empty list and is reported by the caller. A REPORT that
        // dies because it could not find a config would take the test job down with it, on a
        // run where the tests themselves passed.
        let source;
        try {
          source = fs.readFileSync(path.join(webRoot, "vitest.config.mts"), "utf8");
        } catch {
          return [];
        }
        const start = source.indexOf("thresholds:");
        if (start === -1) return [];
        let depth = 0;
        let end = -1;
        for (let i = source.indexOf("{", start); i !== -1 && i < source.length; i++) {
          if (source[i] === "{") depth++;
          else if (source[i] === "}" && --depth === 0) { end = i; break; }
        }
        if (end === -1) return [];
        const keys = /[\"\x27\x60]([^\"\x27\x60]+)[\"\x27\x60]\s*:/g;
        return [...source.slice(start, end).matchAll(keys)].map((m) => m[1]);
      };

      const thresholded = readGatedModules(process.argv[2]);
      // Loud rather than silent, and still not a gate: this script exits 0 whatever it finds.
      // An empty list means it stopped being able to read the file it derives from, and
      // printing the whole-app row alone while saying nothing is how that goes unnoticed.
      if (thresholded.length === 0) {
        process.stderr.write(
          "WARNING: no coverage.thresholds could be read from apps/web/vitest.config.mts — " +
            "the per-module rows below are MISSING, not empty.\n",
        );
      }
      const gated = [
        ...thresholded,
        // Reported, deliberately NOT gated: 78 one-line wrappers make a file-level percentage a
        // count of wrappers rather than a statement about the shared request() they all call.
        // It carries no threshold, so the derivation above cannot find it and it is named here.
        "src/lib/api/client.ts",
      ];
      const pct = (m) => (m ? `${m.pct.toFixed(1)}% (${m.covered}/${m.total})` : "n/a");
      const row = (label, entry) =>
        [label, pct(entry?.statements), pct(entry?.branches), pct(entry?.functions), pct(entry?.lines)].join("\t");
      const byRelative = new Map();
      for (const [key, entry] of Object.entries(summary)) {
        if (key === "total") continue;
        byRelative.set(path.relative(process.argv[2], key).split(path.sep).join("/"), entry);
      }
      const out = [row("whole app (every file under src/)", summary.total)];
      for (const file of gated) out.push(row(file, byRelative.get(file)));
      process.stdout.write(out.join("\n"));
    ' "$WEB_SUMMARY" apps/web
  )

  echo "WEB COVERAGE (Vitest + v8)"
  echo "source:      $WEB_SUMMARY"
  echo "produced by: npm run test:coverage   (in apps/web)"
  echo
  printf '%-42s %-22s %-22s %-22s %-22s\n' "scope" "statements" "branches" "functions" "lines"
  echo "$rows" | while IFS=$'\t' read -r label statements branches functions lines; do
    [ -n "$label" ] || continue
    printf '%-42s %-22s %-22s %-22s %-22s\n' "$label" "$statements" "$branches" "$functions" "$lines"
  done
  echo
  echo "Thresholds are declared per module in apps/web/vitest.config.mts and enforced by the"
  echo "test run itself. There is no whole-app threshold; the first row is a report (ADR 0042)."

  {
    echo "### Web coverage, measured by this run"
    echo
    echo "| scope | statements | branches | functions | lines |"
    echo "|---|---|---|---|---|"
    echo "$rows" | while IFS=$'\t' read -r label statements branches functions lines; do
      [ -n "$label" ] || continue
      echo "| $label | $statements | $branches | $functions | $lines |"
    done
    echo
    echo "The first row is a **report**. The gate is the per-module \`coverage.thresholds\` in"
    echo "\`apps/web/vitest.config.mts\`, applied by the test run — see ADR 0042."
    echo
  } | summary
}

case "${1:-}" in
  backend) report_backend ;;
  web) report_web ;;
  worker) report_worker ;;
  *) usage ;;
esac
