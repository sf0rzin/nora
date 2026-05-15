# -*- coding: utf-8 -*-
"""
PyInstaller spec file for NORA STT Sidecar (Windows).

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

from PyInstaller.utils.hooks import collect_dynamic_libs, collect_data_files

# Collect all native libraries from azure-cognitiveservices.speech
azure_binaries = collect_dynamic_libs('azure.cognitiveservices.speech')

# Collect data files if any
azure_datas = collect_data_files('azure.cognitiveservices.speech')

block_cipher = None

a = Analysis(
    ['src/nora_stt_sidecar/__main__.py'],
    pathex=[str(src_path)],
    binaries=azure_binaries,
    datas=azure_datas,
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
        'pydantic',
        'pydantic_core',
        'pydantic.deprecated.decorator',
        'typing_extensions',
        'annotated_types',
    ],
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
    name='nora-stt-sidecar-x86_64-pc-windows-msvc',
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
