#!/usr/bin/env python3
"""
Cross-platform build script for the NORA STT Sidecar.

Generates a standalone binary in apps/desktop/src-tauri/binaries/
using PyInstaller. Detects the platform automatically.

Usage:
    python build_sidecar.py

Requirements:
    pip install pyinstaller
    pip install -e ".[dev]"
"""

import platform
import shutil
import subprocess
import sys
from pathlib import Path


def _normalize_machine(machine: str) -> str:
    """Maps platform.machine() → triple used by Rust/Tauri."""
    m = machine.lower()
    if m in ("amd64", "x86_64"):
        return "x86_64"
    if m in ("arm64", "aarch64"):
        return "aarch64"
    return m


def detect_target() -> tuple[str, str]:
    """Detects the system and the Tauri target triple.

    Comes out with the name matching what `apps/desktop/src-tauri/src/stt_sidecar.rs`
    builds via `std::env::consts::ARCH` + `OS`. GitHub Actions CI runners
    on macos-latest are ARM (aarch64) — the build can NO longer hardcode x86_64.
    """
    system = platform.system().lower()
    arch = _normalize_machine(platform.machine())

    if system == "linux":
        return "linux", f"nora-stt-sidecar-{arch}-unknown-linux-gnu"
    elif system == "windows":
        return "windows", f"nora-stt-sidecar-{arch}-pc-windows-msvc"
    elif system == "darwin":
        return "macos", f"nora-stt-sidecar-{arch}-apple-darwin"
    else:
        raise RuntimeError(f"Sistema não suportado: {system}")


def main() -> int:
    project_root = Path(__file__).parent.resolve()
    binaries_dir = project_root.parent / "src-tauri" / "binaries"
    binaries_dir.mkdir(parents=True, exist_ok=True)

    system, expected_name = detect_target()
    spec_file = project_root / f"sidecar-{system}.spec"

    if not spec_file.exists():
        print(f"ERRO: Spec não encontrado: {spec_file}", file=sys.stderr)
        print("Specs disponíveis:", file=sys.stderr)
        for s in project_root.glob("sidecar-*.spec"):
            print(f"  - {s.name}", file=sys.stderr)
        return 1

    print(f"Plataforma detectada: {system}")
    print(f"Target: {expected_name}")
    print(f"Spec: {spec_file}")
    print()

    cmd = [
        sys.executable, "-m", "PyInstaller",
        str(spec_file),
        "--clean",
        "--noconfirm",
    ]

    print(f"Executando: {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=project_root)

    if result.returncode != 0:
        print("ERRO: PyInstaller falhou", file=sys.stderr)
        return result.returncode

    # PyInstaller generates into dist/
    dist_dir = project_root / "dist"
    built_exe = dist_dir / expected_name

    # On Windows, PyInstaller adds .exe automatically
    if system == "windows":
        built_exe_with_ext = dist_dir / f"{expected_name}.exe"
        if built_exe_with_ext.exists():
            built_exe = built_exe_with_ext

    if not built_exe.exists():
        # Look for any file generated in dist
        candidates = list(dist_dir.iterdir()) if dist_dir.exists() else []
        print(f"ERRO: Binário esperado não encontrado: {built_exe}", file=sys.stderr)
        print(f"Conteúdo de {dist_dir}:", file=sys.stderr)
        for c in candidates:
            print(f"  - {c.name}", file=sys.stderr)
        return 1

    # Copy to src-tauri/binaries/
    dest = binaries_dir / built_exe.name
    shutil.copy2(built_exe, dest)

    print()
    print("[OK] Sidecar compilado com sucesso!")
    print(f"   Origem: {built_exe}")
    print(f"   Destino: {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
