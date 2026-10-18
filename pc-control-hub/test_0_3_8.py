"""Windows 0.3.8: current-user agent availability, restore and launch verification."""
import sys
import unittest
from pathlib import Path
BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE/"src"))
from agent_updates import (PACKAGE,OFFICIAL_DEFAULT,get_installed_agent,action_for,
                           restore_existing_agent,enable_existing_agent,launch_agent)
from access_center import probe_access

class FakePhone:
    serial="ONEPLUS-15"
    def __init__(self,active=False,cached=True,disabled=False,user="0"):
        self.active=active;self.cached=cached;self.disabled=disabled
        self.user=user;self.commands=[]
    def shell(self,*args,timeout=None):
        self.commands.append(args)
        if args==("am","get-current-user"):return self.user
        if args==("id","-u"):return "2000"
        if args==("pm","list","packages","--user",self.user,"--show-versioncode",PACKAGE):
            return "package:"+PACKAGE+" versionCode:9" if self.active else ""
        if args==("pm","list","packages","-u","--user",self.user,"--show-versioncode",PACKAGE):
            return "package:"+PACKAGE+" versionCode:9" if self.cached else ""
        if args==("pm","list","packages","-d","--user",self.user,"--show-versioncode",PACKAGE):
            return "package:"+PACKAGE+" versionCode:9" if self.active and self.disabled else ""
        if args==("cmd","package","install-existing","--user",self.user,PACKAGE):
            self.active=True;self.disabled=False
            return "Package "+PACKAGE+" installed for user: "+self.user
        if args==("pm","enable","--user",self.user,PACKAGE):
            self.disabled=False
            return "Package "+PACKAGE+" new state: enabled"
        if args==("am","start","--user",self.user,"-n",PACKAGE+"/.WorkshopActivity"):
            if not self.active:raise RuntimeError("app missing")
            return "Starting: Intent"
        if args==("pm","path","moe.shizuku.privileged.api"):return ""
        if args==("getprop","ro.build.type"):return "user"
        if args==("getenforce",):return "Enforcing"
        raise AssertionError("Unexpected command "+repr(args))

class PerProfileTests(unittest.TestCase):
    def test_cached_v9_does_not_mean_installed(self):
        phone=FakePhone(active=False,cached=True)
        state=get_installed_agent(phone)
        self.assertFalse(state.installed)
        self.assertTrue(state.on_device)
        self.assertEqual(state.code,9)
        self.assertEqual(state.user_id,"0")
        self.assertEqual(action_for(state,OFFICIAL_DEFAULT),"restore")
        report=probe_access(phone)
        label=next(x.state for x in report if x.name=="Сервисный агент")
        self.assertIn("Удалён для профиля",label)
        self.assertNotIn("Установлен",label)
        self.assertFalse(any("install-existing" in part for cmd in phone.commands for part in cmd))

    def test_restore_only_after_explicit_command_and_verify(self):
        phone=FakePhone(active=False,cached=True)
        result=restore_existing_agent(phone,"ONEPLUS-15","0")
        self.assertIn("восстановлен",result)
        self.assertEqual(action_for(get_installed_agent(phone),OFFICIAL_DEFAULT),"current")
        self.assertEqual(len([c for c in phone.commands if "install-existing" in c]),1)
        self.assertFalse(any("clear" in cmd or "uninstall" in cmd for c in phone.commands for cmd in c))

    def test_no_cached_apk_requires_install_from_verified_release(self):
        phone=FakePhone(active=False,cached=False)
        status=get_installed_agent(phone)
        self.assertEqual(action_for(status,OFFICIAL_DEFAULT),"install")
        with self.assertRaisesRegex(RuntimeError,"нет сохранённого APK"):
            restore_existing_agent(phone,"ONEPLUS-15","0")
        self.assertFalse(any("install-existing" in x for c in phone.commands for x in c))

    def test_current_and_disabled_are_different(self):
        phone=FakePhone(active=True,cached=True,disabled=True)
        self.assertEqual(action_for(get_installed_agent(phone),OFFICIAL_DEFAULT),"disabled")
        enable_existing_agent(phone,"ONEPLUS-15","0")
        self.assertEqual(action_for(get_installed_agent(phone),OFFICIAL_DEFAULT),"current")

    def test_launch_opens_known_exported_workshop_activity(self):
        phone=FakePhone(active=True)
        out=launch_agent(phone,"ONEPLUS-15")
        self.assertIn("открытия",out)
        self.assertIn(("am","start","--user","0","-n",
            PACKAGE+"/.WorkshopActivity"),phone.commands)

    def test_wrong_serial_or_user_does_not_mutate(self):
        phone=FakePhone(active=False)
        with self.assertRaises(RuntimeError):
            restore_existing_agent(phone,"DIFFERENT","0")
        with self.assertRaises(RuntimeError):
            restore_existing_agent(phone,"ONEPLUS-15","10")
        self.assertFalse(any("install-existing" in x for cmd in phone.commands for x in cmd))

    def test_diagnostic_read_failure_must_not_claim_installed(self):
        class Blocked(FakePhone):
            def shell(self,*args,timeout=None):
                if ("list" in args and "packages" in args and PACKAGE in args):
                    raise RuntimeError("ADB rejected command")
                return super().shell(*args,timeout=timeout)
        row=next(x for x in probe_access(Blocked()) if x.name=="Сервисный агент")
        self.assertIn("Не удалось проверить",row.state)

    def test_all_operations_are_opt_in_and_package_scoped(self):
        ui=(BASE/"src"/"access_center.py").read_text(encoding="utf-8")
        self.assertIn("default=messagebox.NO",ui)
        self.assertIn("restore_existing_agent(",ui)
        self.assertIn("enable_existing_agent(",ui)
        self.assertIn("launch_agent(",ui)
        self.assertIn("Открыть агент на телефоне",ui)

if __name__=="__main__":unittest.main()
