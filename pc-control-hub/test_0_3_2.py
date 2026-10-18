"""Android 16/OxygenOS permission denial: never bypass full app data clear."""
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE/"src"))
from ach_core import Service,ServiceError

class DeniedClear:
    def __init__(self):
        self.calls=[]
    def __call__(self,argv,timeout):
        command=argv[1:]
        self.calls.append(command)
        shell=command[3:] if command[:3]==["-s","ABC123","shell"] else []
        if shell==["pm","list","packages","-3"]:
            return SimpleNamespace(returncode=0,stdout="package:ru.ozon.app.android\n",stderr="")
        if shell==["am","get-current-user"]:
            return SimpleNamespace(returncode=0,stdout="0\n",stderr="")
        if shell[:2]==["pm","clear"]:
            return SimpleNamespace(returncode=255,stdout="",
                stderr="Exception occurred while executing 'clear':\njava.lang.SecurityException: "
                       "PID 5709 does not have android.permission.CLEAR_APP_USER_DATA "
                       "to clear data of package ru.ozon.app.android")
        if shell[:2]==["am","start"]:
            return SimpleNamespace(returncode=0,stdout="Starting: Intent\n",stderr="")
        raise AssertionError("Unexpected call "+repr(shell))

class Android16ClearDenialTests(unittest.TestCase):
    def service(self):
        fake=DeniedClear()
        svc=Service(adb=__file__,fastboot=__file__,runner=fake)
        svc.serial="ABC123"
        return svc,fake

    def test_denied_data_clear_is_explicitly_reported(self):
        svc,fake=self.service()
        with self.assertRaises(ServiceError) as raised:
            svc.maintenance("ru.ozon.app.android","data")
        msg=str(raised.exception)
        self.assertIn("CLEAR_APP_USER_DATA",msg)
        self.assertIn("НЕ ВЫПОЛНЕНА",msg)
        self.assertIn("Настройки",msg)
        self.assertTrue(any("clear" in cmd and "ru.ozon.app.android" in cmd for cmd in fake.calls))
        self.assertFalse(any("uninstall" in cmd for cmd in fake.calls))

    def test_denied_data_clear_has_no_automatic_retry(self):
        svc,fake=self.service()
        with self.assertRaises(ServiceError):
            svc.maintenance("ru.ozon.app.android","data")
        clears=[cmd for cmd in fake.calls if "clear" in cmd]
        self.assertEqual(len(clears),1,clears)

    def test_ui_offers_settings_only_for_single_permission_denial(self):
        app=(BASE/"src"/"app.py").read_text(encoding="utf-8")
        self.assertIn('mode == "data" and len(pkgs) == 1 and failures',app)
        self.assertIn('"CLEAR_APP_USER_DATA" in error',app)
        self.assertIn('self.service.open_package_settings(package)',app)
        self.assertIn('mode in ("data", "uninstall", "disable")',app)
        self.assertIn("default=messagebox.NO",app)

if __name__=="__main__":
    unittest.main()
