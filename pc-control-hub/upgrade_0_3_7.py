"""Windows 0.3.7: unify agent inventory status with verified updater."""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
p=root/"src"/"app.py"
text=p.read_text(encoding="utf-8")
old='VERSION = "0.3.6"'
if text.count(old)!=1:raise RuntimeError("0.3.7 unexpected source version")
p.write_text(text.replace(old,'VERSION = "0.3.7"',1),encoding="utf-8")
print("Windows 0.3.7: access center and agent updater now use the same version query")
