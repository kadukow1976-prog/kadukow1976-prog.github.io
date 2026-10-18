"""Version 0.3.1: differentiate cache timeout and open Android's app settings safely.
Apply after upgrade_0_2.py and upgrade_0_3.py. Anchors are checked.
"""
from __future__ import annotations
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
core_file = root / "src" / "ach_core.py"
app_file = root / "src" / "app.py"
core = core_file.read_text(encoding="utf-8")
app = app_file.read_text(encoding="utf-8")

def once(src: str, old: str, new: str, title: str) -> str:
    found = src.count(old)
    if found != 1:
        raise RuntimeError(f"0.3.1: unexpected {title} anchor count: {found}")
    return src.replace(old, new, 1)

core = once(core,
    'raise ServiceError("Android отклонил очистку только кэша: " + str(exc) +',
    'raise ServiceError(("ТАЙМ-АУТ: результат очистки кэша НЕИЗВЕСТЕН. " if "Превышено ожидание" in str(exc) else "Android сообщил об ошибке очистки кэша: ") + str(exc) +',
    "uncertain cache timeout")
core = once(core,
    '    def install_apk(self, paths: list[str]) -> str:',
    '''    def open_package_settings(self, package: str) -> str:
        """Launch Android's own app info screen; never clear anything automatically."""
        if not PACKAGE_RE.fullmatch(package) or len(package) > 200:
            raise ServiceError("Некорректное имя пакета приложения")
        user = self.user_id()
        response = self.shell("am", "start", "--user", user, "-a",
                              "android.settings.APPLICATION_DETAILS_SETTINGS",
                              "-d", "package:" + package, timeout=25)
        if "Error:" in response or "Exception" in response:
            raise ServiceError("Android не открыл сведения о приложении: " + response[:500])
        return ("Открыта карточка приложения на телефоне: " + package
                + "\\nДля ручной очистки кэша: Память/Хранилище → Очистить кэш."
                + "\\nПолная очистка данных не запускалась.")

    def install_apk(self, paths: list[str]) -> str:''',
    "open settings backend")

app = once(app,
    'VERSION = "0.3.0"',
    'VERSION = "0.3.1"',
    "version string")
app = once(app,
    '("Сведения", self.details), ("Кэш", lambda: self.bulk("cache"))',
    '("Сведения", self.details), ("Настройки", self.open_selected_app_settings), ("Кэш", lambda: self.bulk("cache"))',
    "settings action")
app = once(app,
    '    def bulk(self, mode):',
    '''    def open_selected_app_settings(self):
        selected = self.selected_packages()
        if not selected:
            return
        package = selected[0]
        self.busy_call("Открыть сведения об " + package,
                       lambda: self.service.open_package_settings(package),
                       lambda result: self.log(result))

    def bulk(self, mode):''',
    "settings callback")
app = once(app,
    '            if mode in ("uninstall", "disable", "enable"):\n                self.load_packages()',
    '''            # After an unsuccessful cache-only request for ONE package, open
            # its stock Android settings screen. Do not retry the timed-out command:
            # it may still have run on-device. Never fall back to full data clear.
            if mode == "cache" and len(pkgs) == 1 and failures:
                package = pkgs[0]
                self.log("Кэш: результат не подтверждён. Открываем безопасные настройки " + package)
                self.busy_call("Открыть настройки " + package,
                               lambda: self.service.open_package_settings(package),
                               lambda outcome: self.log(outcome))
            if mode in ("uninstall", "disable", "enable"):
                self.load_packages()''',
    "safe fallback")
core_file.write_text(core, encoding="utf-8")
app_file.write_text(app, encoding="utf-8")
print("Applied Windows 0.3.1: cache timeout status + non-destructive settings fallback")
