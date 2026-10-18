"""Windows v0.3.6: version detection fallback for Android 16/OxygenOS.

Backend implementation lives in agent_updates.py; this upgrades only UI version.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
p=root/"src"/"app.py"
content=p.read_text(encoding="utf-8")
old='VERSION = "0.3.5"'
if content.count(old)!=1:raise RuntimeError("Expected 0.3.5 before 0.3.6 upgrade")
p.write_text(content.replace(old,'VERSION = "0.3.6"',1),encoding="utf-8")
print("Windows 0.3.6 upgrade applied: robust ADB version probe")
