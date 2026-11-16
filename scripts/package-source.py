#!/usr/bin/env python3
"""Package editable source, tests and docs, excluding machine-local settings and outputs."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
out = root / 'artifacts' / 'AirBridge-source.zip'
excluded = {'.git', '.gradle', '.gradle-home', '.idea', 'build', 'artifacts', 'node_modules', '__pycache__'}
out.parent.mkdir(exist_ok=True)
with ZipFile(out, 'w', ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if path.is_symlink() or not path.is_file() or any(p in excluded for p in relative.parts):
            continue
        if path.name in {'local.properties', '.DS_Store'} or path.suffix == '.iml':
            continue
        archive.write(path, Path('AirBridge') / relative)
print(out)
