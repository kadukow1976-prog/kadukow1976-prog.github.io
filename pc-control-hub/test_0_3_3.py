"""Regression: SamFw-style multi app selection and safe batch behavior, v0.3.3."""
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE/"src"))
from ach_core import Service,ServiceError

class FakeAndroid:
    def __init__(self):self.calls=[]
    def __call__(self,argv,timeout):
        command=argv[1:]
        self.calls.append(command)
        if command==["devices","-l"]:
            return SimpleNamespace(returncode=0,stdout="List of devices attached\nABC123 device model:OnePlus15\n",stderr="")
        shell=command[3:] if command[:3]==["-s","ABC123","shell"] else []
        if shell==["pm","list","packages","-3"]:
            return SimpleNamespace(returncode=0,stdout="package:com.example.first\npackage:com.example.second\n",stderr="")
        if shell==["am","get-current-user"]:
            return SimpleNamespace(returncode=0,stdout="0\n",stderr="")
        if shell[:2]==["pm","clear"]:
            return SimpleNamespace(returncode=255,stdout="",stderr=
                "java.lang.SecurityException: Permission Denial: "
                "android.permission.CLEAR_APP_USER_DATA")
        raise AssertionError("Unexpected command: "+repr(command))

class BatchSelectionTests(unittest.TestCase):
    def test_explicit_checkbox_selection_retained(self):
        ui=(BASE/"src"/"app.py").read_text(encoding="utf-8")
        for s in ('columns=("checked", "package", "overlay", "boot")',
                  "def toggle_package_mark(self, event):",
                  "def mark_visible_packages(self):",
                  "def unmark_packages(self):",
                  "self.marked_packages.intersection_update(self.current_packages)",
                  'values=("☑" if package in self.marked_packages else "☐"',
                  "self.app_table.identify_column(event.x)!=\"#1\"",
                  "values = sorted(self.marked_packages) if self.marked_packages else"):
            self.assertIn(s,ui,s)
        self.assertIn("selection_set([p for p in selected if p in visible])",ui)

    def test_common_permission_denial_stops_remaining_bulk(self):
        ui=(BASE/"src"/"app.py").read_text(encoding="utf-8")
        self.assertIn('if mode=="data" and "CLEAR_APP_USER_DATA" in error:',ui)
        self.assertIn("skipped=pkgs[index+1:]",ui)
        self.assertIn('return successes, failures, skipped',ui)
        self.assertIn("Пропущено без запуска",ui)
        self.assertIn("def offer_manual_app_settings(self, packages):",ui)
        self.assertIn('self.service.open_package_settings(package)',ui)
        self.assertNotIn('self.service.maintenance(package, "data")',ui)

    def test_batch_confirmation_still_required(self):
        ui=(BASE/"src"/"app.py").read_text(encoding="utf-8")
        self.assertIn('mode in ("data", "uninstall", "disable")',ui)
        self.assertIn('default=messagebox.NO',ui)
        self.assertIn('self.bulk("uninstall")',ui)
        self.assertIn('self.bulk("data")',ui)

    def test_single_permission_denial_is_not_success(self):
        runner=FakeAndroid()
        svc=Service(adb=__file__,fastboot=__file__,runner=runner)
        svc.serial="ABC123"
        with self.assertRaises(ServiceError) as e:
            svc.maintenance("com.example.first","data")
        self.assertIn("CLEAR_APP_USER_DATA",str(e.exception))
        clears=[cmd for cmd in runner.calls if "clear" in cmd]
        self.assertEqual(len(clears),1)
        self.assertFalse(any("uninstall" in cmd for cmd in runner.calls))
        self.assertFalse(any("--cache-only" in cmd for cmd in clears))

if __name__=="__main__":
    unittest.main()
