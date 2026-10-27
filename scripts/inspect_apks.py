"""Inspect built APKs and reject packaging regressions using the actual ZIP entries."""
import json
from pathlib import Path
import sys
import zipfile

rows = []
for path in sorted(Path(sys.argv[1]).glob('*.apk')):
    abi = next((name for name in ('arm64-v8a', 'armeabi-v7a', 'x86_64') if name in path.name), None)
    assert abi, f'{path}: universal or unrecognized APK'
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        architectures = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
        assert architectures == {abi}, f'{path}: unexpected native architectures {architectures}'
        assert f'lib/{abi}/libsignal_jni.so' in names, f'{path}: production crypto missing'
        assert f'lib/{abi}/libsqlcipher.so' in names, f'{path}: SQLCipher missing'
        assert not any(name.endswith(('.dll', '.dylib')) or 'jni_testing' in name for name in names), f'{path}: desktop/test native binaries leaked'
        assert path.stat().st_size < 100 * 1024 * 1024, f'{path}: exceeds 100 MiB packaging budget'
        rows.append({
            'apk': path.name,
            'abi': abi,
            'apk_bytes': path.stat().st_size,
            'native_bytes': sum(entry.file_size for entry in entries if entry.filename.startswith('lib/')),
            'dex_bytes': sum(entry.file_size for entry in entries if entry.filename.endswith('.dex')),
        })
assert len(rows) == 3, f'Expected 3 architecture-specific APKs, found {len(rows)}'
print(json.dumps(rows, indent=2))
