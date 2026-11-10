#!/usr/bin/env python3
"""Rewrite a jar with sorted entries, fixed timestamps and fixed attributes: normalize-jar.py IN OUT."""
import sys
import zipfile

source, target = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zout:
    for name in sorted(zin.namelist()):
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o755 if name.endswith("/") else 0o644) << 16
        zout.writestr(info, zin.read(name))
