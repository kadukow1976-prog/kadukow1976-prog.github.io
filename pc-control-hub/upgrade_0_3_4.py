"""Windows 0.3.4: add a transparent privilege capability center.
Apply after 0.3.3. Fail build if existing UI anchor changes.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
p=root/"src"/"app.py"
app=p.read_text(encoding="utf-8")
old='        self.t_log = ttk.Frame(self.tabs, padding=9)'
assert app.count(old)==1, "Expected one UI tab initialization anchor"
app=app.replace(old,old+'''
        from access_center import attach_access_center
        attach_access_center(self)''',1)
old='VERSION = "0.3.3"'
assert app.count(old)==1,"Wrong base version"
app=app.replace(old,'VERSION = "0.3.4"',1)
p.write_text(app,encoding="utf-8")
print("Windows 0.3.4 privileges center integrated")
