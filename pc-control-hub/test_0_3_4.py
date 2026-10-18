"""Windows 0.3.4: non-invasive privileges report and UI tests."""
import sys
import unittest
from pathlib import Path

BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE/"src"))
from access_center import probe_access,parse_uid,exists_from_pm,AGENT_PACKAGE,SHIZUKU_PACKAGE

class MockADB:
    def __init__(self,agent=True,shizuku=False):
        self.agent=agent
        self.shizuku=shizuku
        self.commands=[]
    def shell(self,*args,timeout=0):
        self.commands.append(tuple(args))
        lookup={
            ("id","-u"):"2000\n",
            ("pm","path",AGENT_PACKAGE):"package:/data/app/agent/base.apk\n" if self.agent else "",
            ("pm","path",SHIZUKU_PACKAGE):"package:/data/app/shizuku/base.apk\n" if self.shizuku else "",
            ("getprop","ro.build.type"):"user",
            ("getenforce",):"Enforcing"
        }
        if tuple(args) not in lookup:
            raise AssertionError("Unexpected command: "+repr(args))
        return lookup[tuple(args)]

class AccessCenterTests(unittest.TestCase):
    def test_uid_cannot_be_inferred_from_other_text(self):
        self.assertEqual(parse_uid("2000\n"),2000)
        self.assertIsNone(parse_uid("uid=2000(shell) gid=2000(shell)"))
        self.assertIsNone(parse_uid("[недоступно] ADB has error 2000"))

    def test_package_is_not_guessed_from_error(self):
        self.assertTrue(exists_from_pm("package:/data/app/base.apk"))
        self.assertFalse(exists_from_pm("Error: package: not found"))

    def test_audit_is_read_only(self):
        mock=MockADB(agent=True)
        report=probe_access(mock)
        titles={row.name:row.state for row in report}
        self.assertIn("Установлен",titles["Сервисный агент"])
        self.assertIn("UID 2000",titles["ADB-подключение"])
        self.assertIn("Не найден",titles["Shizuku"])
        self.assertEqual(len(report),9)
        for cmd in mock.commands:
            self.assertIn(cmd[0],{"id","pm","getprop","getenforce"})
            self.assertNotIn("grant",cmd)
            self.assertNotIn("clear",cmd)
            self.assertNotIn("set-device-owner",cmd)

    def test_agent_absent_reported(self):
        report=probe_access(MockADB(agent=False,shizuku=True))
        states={row.name:row.state for row in report}
        self.assertEqual(states["Сервисный агент"],"Не найден")
        self.assertIn("не проверены",states["Shizuku"])

    def test_destructive_actions_still_require_confirmation(self):
        app=(BASE/"src"/"app.py").read_text(encoding="utf-8")
        self.assertIn('from access_center import attach_access_center',app)
        self.assertIn('mode in ("data", "uninstall", "disable")',app)
        self.assertIn('default=messagebox.NO',app)
        self.assertIn('VERSION = "0.3.4"',app)

if __name__=="__main__":unittest.main()
