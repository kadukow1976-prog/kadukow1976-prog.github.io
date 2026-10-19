"""Read-only local Platform-Tools and fastbootd diagnostics, without hardware."""
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"src"))
from platform_tools_health import (_version,_fastboot_var,_adb_devices,
                                   _fastboot_devices,diagnose_platform_tools)

class Service:
    def __init__(self,pair):self.pair=pair
    def discover_platform_tools(self):return self.pair

class FakeRunner:
    def __init__(self,outputs):self.outputs=outputs;self.calls=[]
    def __call__(self,args,**kwargs):
        self.calls.append(tuple(args))
        self.timeout=kwargs["timeout"]
        status,stdout,stderr=self.outputs.get(tuple(args),(-1,"","Unexpected command"))
        return SimpleNamespace(returncode=status,stdout=stdout,stderr=stderr)

class ToolchainTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.adb=str(Path(self.temp.name)/"adb.exe")
        self.fastboot=str(Path(self.temp.name)/"fastboot.exe")
        Path(self.adb).write_bytes(b"MZ")
        Path(self.fastboot).write_bytes(b"MZ")

    def test_parses_realistic_versions_not_adb_protocol_number(self):
        self.assertEqual(_version("Android Debug Bridge version 1.0.41\nVersion 37.0.1-12345678\n","adb"),"37.0.1")
        self.assertIsNone(_version("Android Debug Bridge version 1.0.41","adb"))
        self.assertEqual(_version("fastboot version 37.0.1-12345678\n","fastboot"),"37.0.1")

    def test_fastbootd_bootloader_and_slot_parsing(self):
        self.assertEqual(_fastboot_var("(bootloader) is-userspace: yes\nFinished.","is-userspace"),"yes")
        self.assertEqual(_fastboot_var("is-userspace: no\nOKAY","is-userspace"),"no")
        self.assertEqual(_fastboot_var("current-slot: a","current-slot"),"a")
        self.assertIsNone(_fastboot_var("FAILED (remote: unknown variable)","is-userspace"))

    def test_serials_are_unique_and_checked(self):
        self.assertEqual(_fastboot_devices("ABC123\tfastboot\nABC123\tfastboot\nBAD;X\tfastboot\n"),["ABC123"])
        self.assertEqual(_adb_devices("List of devices attached\nABC123 device product:test\nB unauthorized\n"),
                         [("ABC123","device"),("B","unauthorized")])

    def test_report_with_two_fastboot_modes_and_adb(self):
        a,f=self.adb,self.fastboot
        output={
            (a,"version"):(0,"Android Debug Bridge version 1.0.41\nVersion 37.0.1-123\n",""),
            (f,"--version"):(0,"fastboot version 37.0.1-123\n",""),
            (a,"devices","-l"):(0,"List of devices attached\nSERIAL3 unauthorized\n",""),
            (f,"devices"):(0,"SERIAL1\tfastboot\nSERIAL2\tfastboot\n",""),
            (f,"-s","SERIAL1","getvar","is-userspace"):(0,"","(bootloader) is-userspace: yes\n"),
            (f,"-s","SERIAL1","getvar","current-slot"):(0,"","current-slot: a\n"),
            (f,"-s","SERIAL2","getvar","is-userspace"):(0,"","is-userspace: no\n"),
            (f,"-s","SERIAL2","getvar","current-slot"):(1,"","FAILED (remote: unknown variable)\n"),
        }
        runner=FakeRunner(output)
        report=diagnose_platform_tools(Service((a,f)),runner)
        self.assertIn("SERIAL1 — fastbootd (userspace)",report)
        self.assertIn("SERIAL2 — Bootloader Fastboot",report)
        self.assertIn("Активный слот: A",report)
        self.assertIn("SERIAL3 — требуется подтверждение",report)
        self.assertEqual(len(runner.calls),8)
        self.assertTrue(all("flash" not in cmd and "erase" not in cmd and "reboot" not in cmd
                            for call in runner.calls for cmd in call))
        self.assertEqual(runner.timeout,12)

    def test_missing_tools_do_not_run_commands(self):
        runner=FakeRunner({})
        self.assertIn("не найдены",diagnose_platform_tools(Service(None),runner))
        self.assertEqual(runner.calls,[])

    def test_outdated_tools_are_warned_not_updated(self):
        a,f=self.adb,self.fastboot
        outputs={(a,"version"):(0,"Version 36.0.2-123\n",""),
                 (f,"--version"):(0,"fastboot version 36.0.2-123\n",""),
                 (a,"devices","-l"):(0,"List of devices attached\n",""),
                 (f,"devices"):(0,"","")}
        runner=FakeRunner(outputs)
        report=diagnose_platform_tools(Service((a,f)),runner)
        self.assertIn("старее 37.0.1",report)
        self.assertEqual(len(runner.calls),4)

    def test_gui_uses_background_worker_without_device(self):
        ui=(ROOT/"src"/"access_center.py").read_text(encoding="utf-8")
        self.assertIn("diagnose_platform_tools(app.service)",ui)
        self.assertIn("require_device=False",ui)
        self.assertIn("Диагностика ADB/Fastboot",ui)

if __name__=="__main__":unittest.main()
