"""Windows 0.3.9 — add read-only ADB and Fastboot mode diagnostics."""
from pathlib import Path
import sys
p=Path(sys.argv[1]).resolve()/"src"/"app.py"
s=p.read_text(encoding="utf-8")
old='VERSION = "0.3.8"'
if s.count(old)!=1:raise RuntimeError("Unexpected Windows source version for 0.3.9")
p.write_text(s.replace(old,'VERSION = "0.3.9"',1),encoding="utf-8")
print("Windows 0.3.9: read-only Platform-Tools and Fastbootd diagnostics")
