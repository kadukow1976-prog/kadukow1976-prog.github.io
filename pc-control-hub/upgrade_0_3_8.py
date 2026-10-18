"""Windows 0.3.8 — distinguish cached APK from active Android-user install."""
from pathlib import Path
import sys
p=Path(sys.argv[1]).resolve()/"src"/"app.py"
s=p.read_text(encoding="utf-8")
old='VERSION = "0.3.7"'
if s.count(old)!=1:raise RuntimeError("Wrong starting Windows version")
p.write_text(s.replace(old,'VERSION = "0.3.8"',1),encoding="utf-8")
print("Windows 0.3.8 agent state, restore and launch module integrated")
