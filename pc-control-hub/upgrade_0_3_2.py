"""Android Control Hub Windows 0.3.2 — handle Android 16 CLEAR_APP_USER_DATA refusal.

Apply AFTER upgrades 0.2, 0.3 and 0.3.1. Guard every source anchor.
Nothing invokes pm uninstall / pm clear automatically as a fallback.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
core_path=root/"src"/"ach_core.py"
app_path=root/"src"/"app.py"
core=core_path.read_text(encoding="utf-8")
app=app_path.read_text(encoding="utf-8")

def rep(src,old,new,what):
    n=src.count(old)
    if n!=1:
        raise RuntimeError(f"0.3.2 {what}: expected one source anchor, got {n}")
    return src.replace(old,new,1)

core=rep(core,
'''        elif action == "data":
            out = self.shell("pm", "clear", "--user", user, package, timeout=60)''',
'''        elif action == "data":
            try:
                out = self.shell("pm", "clear", "--user", user, package, timeout=60)
            except ServiceError as exc:
                raw=str(exc)
                if "CLEAR_APP_USER_DATA" in raw or ("SecurityException" in raw and "clear" in raw):
                    raise ServiceError(
                        "ЗАПРЕЩЕНО ANDROID: этой ADB-оболочке не предоставлено "
                        "android.permission.CLEAR_APP_USER_DATA. "
                        "Это ограничение Android/OxygenOS, а не ошибка соединения. "
                        "Полная очистка через ADB НЕ ВЫПОЛНЕНА. "
                        "Откройте на телефоне «Настройки → Приложения → "
                        "Управление приложениями → Память → Очистить данные». "
                        "Программа может открыть карточку выбранного приложения "
                        "на телефоне, но не обходит защиту Android.\\n"
                        "Системный ответ: " + raw[:450]
                    ) from exc
                raise''',
"recognize Android clear data permission error")

app=rep(app,'VERSION = "0.3.1"','VERSION = "0.3.2"',"version")
app=rep(app,
'''            if mode == "cache" and len(pkgs) == 1 and failures:
                package = pkgs[0]
                self.log("Кэш: результат не подтверждён. Открываем безопасные настройки " + package)
                self.busy_call("Открыть настройки " + package,
                               lambda: self.service.open_package_settings(package),
                               lambda outcome: self.log(outcome))''',
'''            if mode == "cache" and len(pkgs) == 1 and failures:
                package = pkgs[0]
                self.log("Кэш: результат не подтверждён. Открываем безопасные настройки " + package)
                self.busy_call("Открыть настройки " + package,
                               lambda: self.service.open_package_settings(package),
                               lambda outcome: self.log(outcome))
            if mode == "data" and len(pkgs) == 1 and failures and any(
                    "CLEAR_APP_USER_DATA" in error for _, error in failures):
                package = pkgs[0]
                self.log("Очистка данных запрещена Android. Открываю сведения "
                         "приложения на телефоне без автоматического удаления: " + package)
                self.busy_call("Открыть настройки " + package,
                               lambda: self.service.open_package_settings(package),
                               lambda outcome: self.log(outcome))''',
"safe manual data clear settings fallback")
# For a batch request do not open many settings pages. The original failed
# operations remain failures in the journal and result dialog.
app=rep(app,
'''            detail = "\\n".join(f"• {pkg}: {error[:370]}" for pkg, error in failures[:4])''',
'''            detail = "\\n".join(
                f"• {pkg}: " +
                ("Android запретил очистку данных (CLEAR_APP_USER_DATA). "
                 "Данные не очищены; откройте системные настройки приложения. "
                 if mode == "data" and "CLEAR_APP_USER_DATA" in error
                 else "") +
                error[:420]
                for pkg, error in failures[:4])''',
"readable failure UI")
core_path.write_text(core,encoding="utf-8")
app_path.write_text(app,encoding="utf-8")
print("Windows 0.3.2 patch applied: clear-data permission denial and safe Settings fallback")
