"""Agent installer 0.3.5: downgrade prevention, verified remote APK, offline status."""
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE/"src"))
from agent_updates import (
    PACKAGE, OFFICIAL_DEFAULT, InstalledAgent, validate_manifest,
    action_for, parse_version_code, known_old_apk_name, load_manifest,
    download_verified_agent, check_sha, get_installed_agent,
    install_agent_if_needed
)

class MockDevice:
    def __init__(self, version=9, installed=True):
        self.version=version
        self.installed=installed
        self.serial="DEV-123"
        self.commands=[]
        self.installs=[]
    def shell(self,*args,timeout=None):
        self.commands.append(args)
        if args==("am","get-current-user"):
            return "0"
        if args==("pm","list","packages","--user","0","--show-versioncode",PACKAGE):
            return ("package:"+PACKAGE+" versionCode:"+str(self.version)) if self.installed else ""
        if args==("pm","list","packages","-u","--user","0","--show-versioncode",PACKAGE):
            return ("package:"+PACKAGE+" versionCode:"+str(self.version)) if self.installed else ""
        if args==("pm","list","packages","-d","--user","0","--show-versioncode",PACKAGE):
            return ""
        if args==("pm","path",PACKAGE):
            return "package:/data/app/base.apk" if self.installed else ""
        if args==("dumpsys","package",PACKAGE):
            return ("Packages:\n  Package [ru.kadukov.androidcontrolhub] (abc):\n"
                    +" versionCode="+str(self.version)+" minSdk=26\n")
        raise AssertionError("Unexpected shell args: "+repr(args))
    def install_apk(self,paths):
        self.installs.append(paths)
        return "Success"

class FakeOpener:
    def __init__(self,data):
        self.data=data
        self.calls=[]
    def __call__(self,req,timeout):
        self.calls.append((req.full_url,timeout))
        return io.BytesIO(self.data)

class AgentUpdaterTests(unittest.TestCase):
    def blob_manifest(self,content=b"FAKE-TEST-APK"):
        obj=dict(OFFICIAL_DEFAULT)
        obj["sha256"]=hashlib.sha256(content).hexdigest()
        obj["size"]=len(content)
        return obj

    def test_original_problem_recognized(self):
        self.assertTrue(known_old_apk_name("C:/Users/A/Downloads/android-hub-beta-0.2.apk"))
        self.assertFalse(known_old_apk_name("android-hub-beta-0.8.1.apk"))
        self.assertEqual(action_for(InstalledAgent(True,9,"D"),OFFICIAL_DEFAULT),"current")
        self.assertEqual(action_for(InstalledAgent(True,12,"D"),OFFICIAL_DEFAULT),"newer")
        self.assertEqual(action_for(InstalledAgent(True,2,"D"),OFFICIAL_DEFAULT),"upgrade")
        self.assertEqual(action_for(InstalledAgent(True,None,"D"),OFFICIAL_DEFAULT),"unknown")

    def test_version_probe_and_installed_flag(self):
        device=MockDevice(version=9)
        state=get_installed_agent(device)
        self.assertTrue(state.installed)
        self.assertEqual(state.code,9)
        self.assertEqual(state.serial,"DEV-123")
        device=MockDevice(installed=False)
        state=get_installed_agent(device)
        self.assertFalse(state.installed)
        self.assertIsNone(state.code)
        self.assertNotIn(("dumpsys","package",PACKAGE),device.commands)

    def test_parsing_is_strict(self):
        self.assertEqual(parse_version_code("  versionCode=9 minSdk=26\n"),9)
        self.assertIsNone(parse_version_code("VersionCode unknown, 9"))
        self.assertIsNone(parse_version_code("permission denied versionCode=9"))

    def test_fallback_offline_manifest_pinned(self):
        def fail(*a,**k):
            raise OSError("offline")
        manifest,origin=load_manifest(opener=fail)
        self.assertEqual(manifest["version_code"],9)
        self.assertIn("офлайн",origin)
        self.assertEqual(manifest["sha256"],OFFICIAL_DEFAULT["sha256"])

    def test_valid_manifest_online(self):
        m=self.blob_manifest()
        fake=FakeOpener(json.dumps(m).encode("utf8"))
        found,origin=load_manifest(opener=fake)
        self.assertEqual(found,m)
        self.assertIn("GitHub",origin)

    def test_reject_unknown_release_url(self):
        for bad in ("http://evil.example/download.apk",
                    "https://github.com/other/other/releases/download/x/y.apk",
                    "file:///home/user/agent.apk"):
            m=self.blob_manifest()
            m["url"]=bad
            with self.assertRaises(ValueError):
                validate_manifest(m)

    def test_download_with_sha_and_atomic_write(self):
        binary=b"FAKE-TEST-APK"
        manifest=self.blob_manifest(binary)
        fake=FakeOpener(binary)
        with tempfile.TemporaryDirectory() as tmp:
            path=download_verified_agent(manifest,dest=tmp,opener=fake)
            self.assertEqual(Path(path).read_bytes(),binary)
            self.assertTrue(check_sha(path,manifest))
            self.assertEqual(len(fake.calls),1)
            second=download_verified_agent(manifest,dest=tmp,opener=fake)
            self.assertEqual(second,path)
            self.assertEqual(len(fake.calls),1)
            self.assertFalse(list(Path(tmp).glob("*.part")))

    def test_bad_download_cannot_be_installed(self):
        manifest=self.blob_manifest()
        fake=FakeOpener(b"TAMPERED CONTENT")
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                download_verified_agent(manifest,dest=tmp,opener=fake)
            self.assertFalse(list(Path(tmp).glob("*.apk")))
            self.assertFalse(list(Path(tmp).glob("*.part")))

    def test_existing_current_or_newer_never_reinstalled(self):
        blob=b"FAKE-TEST-APK"
        manifest=self.blob_manifest(blob)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"agent.apk"
            path.write_bytes(blob)
            dev=MockDevice(version=9)
            self.assertIn("не требуется",install_agent_if_needed(dev,manifest,path))
            self.assertEqual(dev.installs,[])
            newer=MockDevice(version=12)
            self.assertIn("Понижение запрещено",install_agent_if_needed(newer,manifest,path))
            self.assertEqual(newer.installs,[])

    def test_upgrade_and_missing_install_only_verified_apk(self):
        blob=b"FAKE-TEST-APK"
        manifest=self.blob_manifest(blob)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"agent.apk"
            path.write_bytes(blob)
            older=MockDevice(version=2)
            self.assertIn("Success",install_agent_if_needed(older,manifest,path))
            self.assertEqual(older.installs,[[str(path)]])
            missing=MockDevice(installed=False)
            self.assertIn("Success",install_agent_if_needed(missing,manifest,path))
            self.assertEqual(missing.installs,[[str(path)]])
            path.write_bytes(b"altered")
            with self.assertRaises(ValueError):
                install_agent_if_needed(MockDevice(version=2),manifest,path)

    def test_gui_retains_manual_consent(self):
        gui=(BASE/"src"/"access_center.py").read_text(encoding="utf-8")
        self.assertIn('("Скачать / обновить агент",update_agent)',gui)
        self.assertIn('known_old_apk_name(filename)',gui)
        self.assertIn("default=messagebox.NO",gui)
        self.assertIn('check_sha(filename,manifest)',gui)
        self.assertNotIn("INSTALL_FAILED_VERSION_DOWNGRADE -d",gui)

if __name__=="__main__":unittest.main()
