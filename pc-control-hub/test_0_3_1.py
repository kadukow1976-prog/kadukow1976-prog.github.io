"""Regression tests for Windows 0.3.1: timeout uncertainty, safe cache fallback."""
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
from ach_core import Service, ServiceError

class CacheRunner:
    def __init__(self, fail_cache=False):
        self.calls = []
        self.fail_cache = fail_cache

    def __call__(self, argv, timeout):
        cmd = argv[1:]
        self.calls.append((cmd, timeout))
        shell = cmd[3:] if cmd[:3] == ["-s", "ABC123", "shell"] else []
        if cmd == ["devices", "-l"]:
            return SimpleNamespace(returncode=0, stdout="List of devices attached\nABC123 device model:Test\n", stderr="")
        if shell == ["pm", "list", "packages", "-3"]:
            return SimpleNamespace(returncode=0, stdout="package:com.example.testing\n", stderr="")
        if shell == ["am", "get-current-user"]:
            return SimpleNamespace(returncode=0, stdout="0\n", stderr="")
        if shell == ["pm", "help"]:
            return SimpleNamespace(returncode=0, stdout="clear [--cache-only] PACKAGE\n", stderr="")
        if "clear" in shell and "--cache-only" in shell:
            if self.fail_cache:
                raise subprocess.TimeoutExpired(cmd, timeout=timeout)
            return SimpleNamespace(returncode=0, stdout="Success\n", stderr="")
        if shell[:2] == ["am", "start"]:
            return SimpleNamespace(returncode=0, stdout="Starting: Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS }\n", stderr="")
        raise AssertionError("Unexpected ADB operation: " + repr(cmd))

class CacheFallbackTests(unittest.TestCase):
    def service(self, fail_cache=False):
        runner = CacheRunner(fail_cache)
        svc = Service(adb=__file__, fastboot=__file__, runner=runner)
        svc.serial = "ABC123"
        return svc, runner

    def test_cache_timeout_is_not_reported_as_permission_denial_or_success(self):
        svc, runner = self.service(True)
        with self.assertRaisesRegex(ServiceError, "НЕИЗВЕСТЕН"):
            svc.maintenance("com.example.testing", "cache")
        self.assertTrue(any("--cache-only" in cmd for cmd, _ in runner.calls))
        self.assertFalse(any(cmd[-4:-2] == ["clear", "--user"] and "--cache-only" not in cmd for cmd, _ in runner.calls))

    def test_open_settings_uses_explicit_safe_android_intent(self):
        svc, runner = self.service()
        result = svc.open_package_settings("com.example.testing")
        self.assertIn("карточка приложения", result)
        commands = [cmd for cmd, _ in runner.calls]
        self.assertTrue(any("android.settings.APPLICATION_DETAILS_SETTINGS" in cmd
                            and "package:com.example.testing" in cmd for cmd in commands))
        self.assertFalse(any("clear" in cmd or "uninstall" in cmd for cmd in commands))

    def test_settings_does_not_accept_command_injection(self):
        svc, runner = self.service()
        with self.assertRaisesRegex(ServiceError,"Некорректное имя"):
            svc.open_package_settings("com.example.testing;reboot")
        self.assertEqual(runner.calls, [])

    def test_ui_includes_safe_fallback_and_keeps_destructive_guard(self):
        ui = (ROOT / "src" / "app.py").read_text(encoding="utf-8")
        self.assertIn('("Настройки", self.open_selected_app_settings)',ui)
        self.assertIn('mode == "cache" and len(pkgs) == 1 and failures',ui)
        self.assertIn('mode in ("data", "uninstall", "disable")',ui)
        self.assertIn('default=messagebox.NO',ui)
        self.assertNotIn('self.service.maintenance(package, "data")',ui)

if __name__ == "__main__":
    unittest.main()
