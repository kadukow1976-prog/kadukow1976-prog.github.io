"""Android Hub Windows 0.3.6: robust, read-only version detection on Android 16."""
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"src"))
from agent_updates import (
    PACKAGE, OFFICIAL_DEFAULT, get_installed_agent,
    _matching_package_info, action_for, InstalledAgent
)

class FakeOxygenOS:
    serial="CPH2747-serial"
    def __init__(self,path_ok=True,version_list=True,cmd_list=False):
        self.path_ok=path_ok
        self.version_list=version_list
        self.cmd_list=cmd_list
        self.calls=[]
    def shell(self,*args,timeout=None):
        self.calls.append(args)
        if args==("am","get-current-user"):return "0"
        if args==("pm","list","packages","-d","--user","0","--show-versioncode",PACKAGE):return ""
        if args==("pm","path",PACKAGE):
            if not self.path_ok:raise RuntimeError("Ошибка adb (код 1)")
            return "package:/data/app/~~xxx/ru.kadukov.androidcontrolhub/base.apk"
        if args==("dumpsys","package",PACKAGE):
            raise RuntimeError("Ошибка adb (код 1)")
        if args==("pm","list","packages","--user","0","--show-versioncode",PACKAGE):
            if self.version_list:
                return "package:ru.kadukov.androidcontrolhub versionCode:9"
            raise RuntimeError("Ошибка adb (код 1)")
        if args==("cmd","package","list","packages","--user","0","--show-versioncode",PACKAGE):
            if self.cmd_list:
                return "package:ru.kadukov.androidcontrolhub versionCode:9"
            raise RuntimeError("Ошибка adb (код 1)")
        raise AssertionError("Unexpected "+repr(args))

class OxygenVersionFallbackTests(unittest.TestCase):
    def test_dumpsys_denied_uses_pm_list(self):
        device=FakeOxygenOS()
        state=get_installed_agent(device)
        self.assertTrue(state.installed)
        self.assertEqual(state.code,9)
        self.assertEqual(action_for(state,OFFICIAL_DEFAULT),"current")
        self.assertIn(("pm","list","packages","--user","0","--show-versioncode",PACKAGE),device.calls)
        self.assertFalse(any("install" in x or "uninstall" in x or "clear" in x for c in device.calls for x in c))

    def test_pm_path_denied_but_list_returns_installed(self):
        device=FakeOxygenOS(path_ok=False)
        state=get_installed_agent(device)
        self.assertTrue(state.installed)
        self.assertEqual(state.code,9)

    def test_cmd_package_list_fallback(self):
        device=FakeOxygenOS(version_list=False,cmd_list=True)
        state=get_installed_agent(device)
        self.assertTrue(state.installed)
        self.assertEqual(state.code,9)

    def test_no_supported_query_never_authorizes_downgrade(self):
        device=FakeOxygenOS(version_list=False)
        state=get_installed_agent(device)
        self.assertTrue(state.installed)
        self.assertIsNone(state.code)
        self.assertEqual(action_for(state,OFFICIAL_DEFAULT),"unknown")
        self.assertIn("Ошибка adb (код 1)",state.diagnostics)

    def test_no_working_queries_blocks_install(self):
        device=FakeOxygenOS(path_ok=False,version_list=False)
        state=get_installed_agent(device)
        self.assertEqual(action_for(state,OFFICIAL_DEFAULT),"unknown")
        self.assertFalse(any("install" in s or "uninstall" in s
                             for c in device.calls for s in c))

    def test_only_exact_package_is_accepted(self):
        self.assertEqual(_matching_package_info(
            "package:ru.kadukov.androidcontrolhub versionCode:9"),(True,9))
        self.assertEqual(_matching_package_info(
            "package:ru.kadukov.androidcontrolhub.fake versionCode:900"),(False,None))
        self.assertEqual(_matching_package_info(
            "package:com.example.other versionCode:900"),(False,None))
        self.assertEqual(_matching_package_info(
            "Permission denied: ru.kadukov.androidcontrolhub versionCode:9"),(False,None))

    def test_missing_package_is_not_upgraded_without_owner_approval(self):
        class Missing:
            serial="CPH2747-serial"
            def shell(self,*args,timeout=None):
                if args==("am","get-current-user"): return "0"
                if args==("pm","list","packages","--user","0","--show-versioncode",PACKAGE): return ""
                if args==("pm","list","packages","-u","--user","0","--show-versioncode",PACKAGE): return ""
                raise AssertionError("No more probes should be needed")
        state=get_installed_agent(Missing())
        self.assertFalse(state.installed)
        self.assertEqual(action_for(state,OFFICIAL_DEFAULT),"install")

    def test_ui_shows_version_probe_diagnostics(self):
        ui=(ROOT/"src"/"access_center.py").read_text(encoding="utf8")
        self.assertIn("state.diagnostics",ui)
        self.assertIn("Скачать / обновить агент",ui)
        self.assertIn("default=messagebox.NO",ui)

if __name__=="__main__":
    unittest.main()
