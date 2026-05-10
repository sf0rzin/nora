#!/usr/bin/env bash
# Para os servicos web/api/worker iniciados pelo `make dev`.
#
# Estrategia: identificar processos pela PORTA (3000, 8080, 8001), subir ate
# o root da arvore (ex.: maven -> java, pnpm -> node, uvicorn-reloader ->
# python) e matar a arvore inteira. Isso funciona melhor que tracking de PID
# em ambientes onde Git Bash/MSYS, nohup e wrappers de processo confundem
# o mapeamento entre PID do shell e PID real do Windows.
#
# Funciona em:
#   - Windows (Git Bash + powershell + Get-NetTCPConnection / CIM)
#   - Linux/macOS (lsof + ps recursivo)
set -u

DEV_RUN_DIR="${DEV_RUN_DIR:-.run}"
PORTS=(3000 8080 8001)

is_windows() {
  command -v powershell >/dev/null 2>&1 && command -v taskkill >/dev/null 2>&1
}

stop_windows() {
  echo ">> [Windows] matando processos nas portas ${PORTS[*]}..."
  # Heredoc com sintaxe powershell. Cuidado: variaveis $... sao do PS, nao do bash.
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
        Write-Host "  port $($port): nada escutando"
        continue
      }
      foreach ($c in $conns) {
        $rootPid = [int]$c.OwningProcess
        if ($rootPid -le 4) { continue }
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$rootPid" -ErrorAction SilentlyContinue
        # sobe ate o ultimo wrapper conhecido (mvn/pnpm/uvicorn-reloader/etc.)
        while ($proc) {
          $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.ParentProcessId)" -ErrorAction SilentlyContinue
          if ($parent -and ($wrappers -contains $parent.Name)) {
            $proc = $parent
          } else { break }
        }
        Write-Host "  port $($port): matando arvore a partir de PID $($proc.ProcessId) ($($proc.Name))"
        Kill-Tree $proc.ProcessId
      }
    }
  '
}

# Lista todos os descendentes (recursivo) de um PID via ps.
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
  echo ">> [Unix] matando processos nas portas ${PORTS[*]}..."
  for port in "${PORTS[@]}"; do
    pids=$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null || true)
    if [ -z "$pids" ]; then
      echo "  port $port: nada escutando"
      continue
    fi
    for pid in $pids; do
      echo "  port $port: matando arvore a partir de PID $pid"
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
echo "OK -- processos parados. DB segue de pe (use 'make db-down' para derrubar)."
