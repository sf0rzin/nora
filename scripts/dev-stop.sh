#!/usr/bin/env bash
# Stops the web/api/worker services started by `make dev`.
#
# Strategy: identify processes by PORT (3000, 8080, 8001), climb up to
# the root of the tree (e.g.: maven -> java, pnpm -> node, uvicorn-reloader ->
# python) and kill the whole tree. This works better than PID tracking
# in environments where Git Bash/MSYS, nohup and process wrappers confuse
# the mapping between the shell PID and the real Windows PID.
#
# Works on:
#   - Windows (Git Bash + powershell + Get-NetTCPConnection / CIM)
#   - Linux/macOS (lsof + recursive ps)
set -u

DEV_RUN_DIR="${DEV_RUN_DIR:-.run}"
PORTS=(3000 8080 8001)

is_windows() {
  command -v powershell >/dev/null 2>&1 && command -v taskkill >/dev/null 2>&1
}

stop_windows() {
  echo ">> [Windows] killing processes on ports ${PORTS[*]}..."
  # Heredoc with powershell syntax. Careful: $... variables are PS's, not bash's.
  powershell -NoProfile -Command '
    $ports = @(3000, 8080, 8001)
    $wrappers = @("cmd.exe","mvn.cmd","mvnw.cmd","bash.exe","sh.exe",
                  "pnpm.cmd","pnpm.exe","npm.cmd","npm.exe","node.exe",
                  "python.exe","py.exe","conhost.exe")

    function Kill-Tree($id) {
      try {
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" -ErrorAction SilentlyContinue |
          ForEach-Object { Kill-Tree $_.ProcessId }
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
      } catch {}
    }

    foreach ($port in $ports) {
      $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
      if (-not $conns) {
        Write-Host "  port $($port): nothing listening"
        continue
      }
      foreach ($c in $conns) {
        $rootPid = [int]$c.OwningProcess
        if ($rootPid -le 4) { continue }
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$rootPid" -ErrorAction SilentlyContinue
        # climb up to the last known wrapper (mvn/pnpm/uvicorn-reloader/etc.)
        while ($proc) {
          $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.ParentProcessId)" -ErrorAction SilentlyContinue
          if ($parent -and ($wrappers -contains $parent.Name)) {
            $proc = $parent
          } else { break }
        }
        Write-Host "  port $($port): killing the process tree starting at PID $($proc.ProcessId) ($($proc.Name))"
        Kill-Tree $proc.ProcessId
      }
    }
  '
}

# Lists all descendants (recursive) of a PID via ps.
list_descendants() {
  local root=$1
  ps -A -o pid,ppid 2>/dev/null | awk -v root="$root" '
    BEGIN { list[1]=root; n=1 }
    { ch[$2] = ch[$2] " " $1 }
    END {
      for (i=1; i<=n; i++) {
        split(ch[list[i]], kids, " ")
        for (k in kids) if (kids[k] != "") { n++; list[n]=kids[k] }
      }
      for (i=2; i<=n; i++) print list[i]
    }'
}

stop_unix() {
  echo ">> [Unix] killing processes on ports ${PORTS[*]}..."
  for port in "${PORTS[@]}"; do
    pids=$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null || true)
    if [ -z "$pids" ]; then
      echo "  port $port: nothing listening"
      continue
    fi
    for pid in $pids; do
      echo "  port $port: killing the process tree starting at PID $pid"
      descendants=$(list_descendants "$pid")
      for d in $descendants; do kill -15 "$d" 2>/dev/null || true; done
      kill -15 "$pid" 2>/dev/null || true
      sleep 1
      if kill -0 "$pid" 2>/dev/null; then
        for d in $descendants; do kill -9 "$d" 2>/dev/null || true; done
        kill -9 "$pid" 2>/dev/null || true
      fi
    done
  done
}

if is_windows; then
  stop_windows
else
  stop_unix
fi

rm -f "$DEV_RUN_DIR"/web.pid "$DEV_RUN_DIR"/api.pid "$DEV_RUN_DIR"/worker.pid 2>/dev/null || true
echo "OK -- processes stopped. The DB is still up (use 'make db-down' to bring it down)."
