import io
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"src"))
from ach_core import Service,ServiceError,config_root

REQUIRED=("adb.exe","fastboot.exe","AdbWinApi.dll","AdbWinUsbApi.dll")

class Reply(io.BytesIO):
    url="https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

def make_zip(files=REQUIRED,signature=b"MZ"):
    mem=io.BytesIO()
    with zipfile.ZipFile(mem,"w",zipfile.ZIP_DEFLATED) as z:
        for name in files:
            z.writestr("platform-tools/"+name,signature+b"\0"*9000)
        z.writestr("platform-tools/NOTICE.txt","Official ZIP")
    return mem.getvalue()

class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.env=patch.dict(os.environ,{"LOCALAPPDATA":self.tmp.name},clear=False)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.svc=Service()

    def fetch(self,zip_bytes):
        with patch("ach_core.urllib.request.urlopen",return_value=Reply(zip_bytes)):
            return self.svc.install_platform_tools()

    def test_complete_install_and_discovery(self):
        pair=self.fetch(make_zip())
        self.assertTrue(all((config_root()/"platform-tools"/x).is_file() for x in REQUIRED))
        self.assertEqual(pair,self.svc.discover_platform_tools())
        self.assertTrue((config_root()/"platform-tools"/"install-info.json").is_file())

    def test_invalid_bundle_does_not_damage_old_tools(self):
        pair=self.fetch(make_zip())
        with patch("ach_core.urllib.request.urlopen",return_value=Reply(make_zip(REQUIRED[:3]))):
            with self.assertRaises(ServiceError):self.svc.install_platform_tools()
        self.assertEqual(pair,self.svc.discover_platform_tools())

    def test_invalid_executable_rejected(self):
        with patch("ach_core.urllib.request.urlopen",return_value=Reply(make_zip(signature=b"NO"))):
            with self.assertRaisesRegex(ServiceError,"PE-файл"):self.svc.install_platform_tools()
        self.assertFalse((config_root()/"platform-tools").exists())

    def test_partial_old_install_gets_repaired(self):
        folder=config_root()/"platform-tools"
        folder.mkdir()
        (folder/"adb.exe").write_bytes(b"MZ"+b"\0"*9000)
        with patch("ach_core.shutil.which",return_value=None):
            self.assertIsNone(self.svc.discover_platform_tools())
        self.fetch(make_zip())
        self.assertTrue((folder/"fastboot.exe").exists())

    def test_old_config_paths_do_not_block_new_install(self):
        pair=self.fetch(make_zip())
        stale=Service(adb="C:/removed/adb.exe",fastboot="C:/removed/fastboot.exe")
        self.assertEqual(pair,stale.discover_platform_tools())
        self.assertEqual(pair[0],stale._tool("adb"))

    def test_offline_returns_useful_error(self):
        with patch("ach_core.urllib.request.urlopen",side_effect=OSError("network offline")):
            with self.assertRaisesRegex(ServiceError,"network offline"):self.svc.install_platform_tools()

    def test_reinstallation_updates_manifest(self):
        self.fetch(make_zip())
        manifest=(config_root()/"platform-tools"/"install-info.json").read_text(encoding="utf-8")
        self.assertIn("sha256",manifest)
        self.assertIn("adb.exe",manifest)

if __name__=="__main__":unittest.main()
