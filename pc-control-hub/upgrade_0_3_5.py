"""Windows v0.3.5: verified agent updater with no downgrade or data removal.

Apply after 0.3.4; actual implementation is in agent_updates.py and access_center.py.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
p=root/"src"/"app.py"
source=p.read_text(encoding="utf-8")
old='VERSION = "0.3.4"'
if source.count(old)!=1:raise RuntimeError("0.3.5 wrong starting version")
p.write_text(source.replace(old,'VERSION = "0.3.5"',1),encoding="utf-8")
print("Windows 0.3.5 agent updater integrated")
