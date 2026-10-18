"""Regression: status panel and agent updater must agree on Android 16."""
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"src"))
from access_center import probe_access,AGENT_PACKAGE,SHIZUKU_PACKAGE
from agent_updates import get_installed_agent,OFFICIAL_DEFAULT,action_for

class OxygenOS16:
    serial="ONEPLUS-15"
    def __init__(self):
        self.commands=[]
    def shell(self,*args,timeout=None):
        self.commands.append(args)
        if args==("id","-u"):return "2000\n"
        if args==("am","get-current-user"):return "0"
        if args==("pm","list","packages","-d","--user","0","--show-versioncode",AGENT_PACKAGE):return ""
        if args==("pm","path",AGENT_PACKAGE):
            raise RuntimeError("Ошибка adb (код 1)")
        if args==("pm","list","packages","--user","0","--show-versioncode",AGENT_PACKAGE):
            return "package:ru.kadukov.androidcontrolhub versionCode:9\n"
        if args==("pm","path",SHIZUKU_PACKAGE):return ""
        if args==("getprop","ro.build.type"):return "user"
        if args==("getenforce",):return "Enforcing"
        raise AssertionError("Нежелательная ADB-команда "+repr(args))

class StatusTests(unittest.TestCase):
    def test_panel_and_updater_both_detect_version9(self):
        device=OxygenOS16()
        state=get_installed_agent(device)
        self.assertEqual(state.code,9)
        self.assertEqual(action_for(state,OFFICIAL_DEFAULT),"current")
        entries=probe_access(device)
        agent=next(x for x in entries if x.name=="Сервисный агент")
        self.assertIn("Установлен",agent.state)
        self.assertIn("versionCode 9",agent.state)
        self.assertNotIn("Не найден",agent.state)
        self.assertTrue(all(cmd[0] in ("id","pm","am","cmd","getprop","getenforce")
                            for cmd in device.commands))

    def test_unavailable_is_not_false_absence(self):
        class Blocked(OxygenOS16):
            def shell(self,*args,timeout=None):
                if args==("pm","list","packages","--user","0","--show-versioncode",AGENT_PACKAGE):
                    raise RuntimeError("Ошибка adb (код 1)")
                if args==("cmd","package","list","packages","--user","0","--show-versioncode",AGENT_PACKAGE):
                    raise RuntimeError("Ошибка adb (код 1)")
                return super().shell(*args,timeout=timeout)
        report=probe_access(Blocked())
        state=next(x.state for x in report if x.name=="Сервисный агент")
        self.assertEqual(state,"Не удалось проверить")

    def test_updater_refreshes_audit(self):
        code=(ROOT/"src"/"access_center.py").read_text(encoding="utf-8")
        self.assertIn("agent_info=get_installed_agent(service)",code)
        self.assertIn("agent_status=",code)
        self.assertIn("lambda result:(display_agent_state(result[2],result[0],result[1]),audit())",code)
        self.assertNotIn('"Установлен" if agent else "Не найден"',code)

if __name__=="__main__":
    unittest.main()
