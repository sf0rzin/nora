# -*- coding: utf-8 -*-
"""
PyInstaller spec file for NORA STT Sidecar.

This spec ensures all native libraries from azure-cognitiveservices-speech
are properly bundled into the executable.
"""

import sys
import os
from pathlib import Path

# Add src to path so PyInstaller can find nora_stt_sidecar
project_root = Path(SPECPATH).parent
src_path = project_root / "src"
sys.path.insert(0, str(src_path))

from PyInstaller.utils.hooks import collect_dynamic_libs, collect_data_files, collect_all
import platform

# Collect all native libraries from azure-cognitiveservices-speech
azure_binaries = collect_dynamic_libs('azure.cognitiveservices.speech')

# Collect data files if any
azure_datas = collect_data_files('azure.cognitiveservices.speech')

# Pydantic 2 tem pydantic_core como C extension. Sem collect_all, runtime quebra
# com ModuleNotFoundError: pydantic_core._pydantic_core.
pydantic_datas, pydantic_binaries, pydantic_hiddenimports = collect_all('pydantic')
pydantic_core_datas, pydantic_core_binaries, pydantic_core_hiddenimports = collect_all('pydantic_core')

bundled_binaries = azure_binaries + pydantic_binaries + pydantic_core_binaries
bundled_datas = azure_datas + pydantic_datas + pydantic_core_datas

# Triple do Tauri/Rust (alinhado com std::env::consts::ARCH/OS).
_arch_map = {'x86_64': 'x86_64', 'amd64': 'x86_64', 'aarch64': 'aarch64', 'arm64': 'aarch64'}
_arch = _arch_map.get(platform.machine().lower(), platform.machine().lower())
_exe_name = f'nora-stt-sidecar-{_arch}-unknown-linux-gnu'

block_cipher = None

a = Analysis(
    ['src/nora_stt_sidecar/__main__.py'],
    pathex=[str(src_path)],
    binaries=bundled_binaries,
    datas=bundled_datas,
    hiddenimports=[
        'nora_stt_sidecar.logging_setup',
        'nora_stt_sidecar.protocol',
        'nora_stt_sidecar.transcriber',
        'nora_stt_sidecar.audio_pipe',
        'azure.cognitiveservices.speech',
        'azure.cognitiveservices.speech.speech',
        'azure.cognitiveservices.speech.audio',
        'azure.cognitiveservices.speech.transcription',
        'azure.cognitiveservices.speech.interop',
    ] + pydantic_hiddenimports + pydantic_core_hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        'pip',
        'setuptools',
        'pytest',
        'pygments',
        '_pytest',
        'pyinstaller',
        '_pyinstaller_hooks_contrib',
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name=_exe_name,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
