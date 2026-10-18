"""Android Control Hub 0.3 update applied after upgrade_0_2.py.

Idempotent only for a clean 0.2 intermediate source. All anchors are checked:
an unexpected upstream layout fails the CI build instead of silently shipping.
"""
from __future__ import annotations

from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
core_path = root / "src" / "ach_core.py"
app_path = root / "src" / "app.py"
core = core_path.read_text(encoding="utf-8")
app = app_path.read_text(encoding="utf-8")

def patch(text: str, old: str, new: str, feature: str) -> str:
    n = text.count(old)
    if n != 1:
        raise RuntimeError(f"0.3 patch: unexpected anchor for {feature}: {n}")
    return text.replace(old, new, 1)

helpers = '''
def format_device_report(report: dict[str, str]) -> str:
    """Concise, readable Russian diagnosis instead of full raw dumpsys output."""
    top = ["Производитель", "Модель", "Android", "API", "Сборка", "SoC",
           "ABI", "Патч безопасности", "USB ID", "Время отчёта"]
    lines = ["СВОДКА УСТРОЙСТВА", "─" * 42]
    for key in top:
        if report.get(key):
            lines.append(f"{key}: {report[key].strip()}")
    battery = report.get("Заряд и питание", "")
    def battery_field(key):
        match = re.search(r"^\\s*" + re.escape(key) + r":\\s*([^\\r\\n]+)", battery, re.M | re.I)
        return match.group(1).strip() if match else ""
    lines.extend(["", "АККУМУЛЯТОР", "─" * 42])
    lvl, scale = battery_field("level"), battery_field("scale")
    if lvl:
        try:
            percent = round(int(lvl) / max(int(scale or "100"), 1) * 100)
            lines.append(f"Заряд: {percent}%")
        except ValueError:
            lines.append(f"Заряд (код): {lvl}")
    temp = battery_field("temperature")
    if temp:
        try: lines.append(f"Температура: {int(temp)/10:.1f} °C")
        except ValueError: lines.append(f"Температура: {temp}")
    volt = battery_field("voltage")
    if volt:
        try: lines.append(f"Напряжение: {int(volt)/1000:.2f} В")
        except ValueError: lines.append(f"Напряжение: {volt}")
    ac, usb, wireless = battery_field("AC powered"), battery_field("USB powered"), battery_field("Wireless powered")
    if ac or usb or wireless:
        lines.append("Питание: " + ("зарядка подключена" if "true" in (ac.lower(),usb.lower(),wireless.lower()) else "от батареи"))
    memory = report.get("Оперативная память", "")
    match = re.search(r"^MemTotal:\\s*(\\d+)\\s*kB", memory, re.M)
    if match:
        lines.extend(["", "ПАМЯТЬ", "─" * 42,
                      f"ОЗУ всего: {int(match.group(1))/1048576:.2f} ГБ"])
    match = re.search(r"^MemAvailable:\\s*(\\d+)\\s*kB", memory, re.M)
    if match:
        lines.append(f"ОЗУ доступно: {int(match.group(1))/1048576:.2f} ГБ")
    df = report.get("Накопитель", "").splitlines()
    if df:
        lines.extend(["", "НАКОПИТЕЛЬ /data", "─" * 42,
                      df[-1].strip()])
    lines.extend(["", "Полный технический вывод можно сохранить в отчёте JSON.",
                  "Часть параметров может не предоставляться прошивкой Android."])
    return "\\n".join(lines)


def describe_package_dump(package: str, raw: str) -> str:
    """Explain declared permissions as capabilities, not as proof of harmful activity."""
    fields = [
        ("Версия приложения", r"\\bversionName=([^\\s]+)"),
        ("Код версии", r"\\bversionCode=(\\d+)"),
        ("Целевой Android API", r"\\btargetSdk=(\\d+)"),
        ("Первая установка", r"\\bfirstInstallTime=([^\\r\\n]+)"),
        ("Последнее обновление", r"\\blastUpdateTime=([^\\r\\n]+)"),
    ]
    lines = [f"ПРИЛОЖЕНИЕ: {package}", "─" * 48]
    for title, pattern in fields:
        match = re.search(pattern, raw)
        if match:
            lines.append(f"{title}: {match.group(1).strip()}")
    known = {
        "android.permission.SYSTEM_ALERT_WINDOW": "Показ окон поверх других приложений (возможность)",
        "android.permission.RECEIVE_BOOT_COMPLETED": "Получение события загрузки Android (автозапуск возможен)",
        "android.permission.SCHEDULE_EXACT_ALARM": "Возможность точного будильника / отложенного запуска",
        "android.permission.POST_NOTIFICATIONS": "Показ уведомлений",
        "android.permission.FOREGROUND_SERVICE": "Фоновые службы с уведомлением",
        "android.permission.RECORD_AUDIO": "Доступ к микрофону",
        "android.permission.CAMERA": "Доступ к камере",
        "android.permission.ACCESS_FINE_LOCATION": "Точное местоположение",
        "android.permission.READ_CONTACTS": "Чтение контактов",
        "android.permission.READ_SMS": "Чтение SMS",
        "android.permission.INTERNET": "Доступ к интернету"
    }
    lines.extend(["", "ОБЪЯВЛЕННЫЕ ВОЗМОЖНОСТИ", "─" * 48])
    matches = [(name, label) for name, label in known.items() if name in raw]
    if matches:
        for name, label in matches:
            lines.append("• " + label)
            lines.append("  " + name)
    else:
        lines.append("В предоставленном выводе явные признаки не обнаружены.")
    lines.extend(["", "ВАЖНО: заявленное разрешение не доказывает, что программа",
                  "фактически использует функцию или показывает рекламу.",
                  "Детали о полном списке прав доступны в системных настройках Android."])
    return "\\n".join(lines)

'''
core = patch(core, 'class Service:\n', helpers + 'class Service:\n', "Russian report formatting helpers")
core = patch(core,
'''        pattern = re.compile(r"(?:versionName|versionCode|firstInstallTime|lastUpdateTime|targetSdk|userId|requested permissions|android.permission.SYSTEM_ALERT_WINDOW|android.permission.RECEIVE_BOOT_COMPLETED|android.permission.SCHEDULE_EXACT_ALARM|android.permission.FOREGROUND_SERVICE).*", re.I)
        lines = [line.strip() for line in raw.splitlines() if pattern.search(line)]
        return "\\n".join(lines[:90]) if lines else "Система не предоставила подробности"''',
'''        return describe_package_dump(package, raw)''',
"Russian package details")
core = patch(core,
'''            out = self.shell("pm", "clear", "--user", user, "--cache-only", package, timeout=60)''',
'''            try:
                out = self.shell("pm", "clear", "--user", user, "--cache-only", package, timeout=60)
            except ServiceError as exc:
                raise ServiceError("Android отклонил очистку только кэша: " + str(exc) +
                                   "\\nКэш приложения можно попробовать очистить вручную: "
                                   "Настройки Android → Приложения → Память → Очистить кэш. "
                                   "Полная очистка данных НЕ запускалась.") from exc''',
"cache refusal details")
app = patch(app, 'from ach_core import Service, ServiceError, FILE_FOLDERS, config_root',
                   'from ach_core import Service, ServiceError, FILE_FOLDERS, config_root, format_device_report',
                   "format import")
app = patch(app, 'VERSION = "0.2.0"', 'VERSION = "0.3.0"', "app version")
app = patch(app, 'self.geometry("1150x790")', 'self.geometry("1080x700")', "compact window")
app = patch(app, 'self.minsize(900, 620)', 'self.minsize(900, 570)', "compact minimum")
app = patch(app, '"Title.TLabel").pack(side="left")', '"Title.TLabel").pack(side="left")', "no-op guard") if False else app
app = patch(app, 'font=("Segoe UI Semibold", 21)', 'font=("Segoe UI Semibold", 18)', "compact heading")
app = patch(app, 'style.configure("TButton", padding=(13, 9)', 'style.configure("TButton", padding=(9, 5)', "compact buttons")
app = patch(app, 'style.configure("TNotebook.Tab", padding=(19, 12)', 'style.configure("TNotebook.Tab", padding=(11, 7)', "compact tabs")
app = patch(app, 'rowheight=29', 'rowheight=23', "compact app table")
app = patch(app, 'header = ttk.Frame(self, padding=(24, 17))', 'header = ttk.Frame(self, padding=(14, 8))', "compact header")
app = patch(app, 'select = ttk.Frame(self, padding=(24, 0, 24, 13))',
                  'select = ttk.Frame(self, padding=(14, 0, 14, 7))', "compact toolbar")
app = patch(app, 'self.tabs.pack(fill="both", expand=True, padx=24, pady=(0, 6))',
                  'self.tabs.pack(fill="both", expand=True, padx=14, pady=(0, 4))', "compact pane")
app = patch(app, 'self.t_apps = ttk.Frame(self.tabs, padding=17)',
                  'self.t_apps = ttk.Frame(self.tabs, padding=9)', "compact apps")
app = patch(app, 'self.t_overview = ttk.Frame(self.tabs, padding=17)',
                  'self.t_overview = ttk.Frame(self.tabs, padding=9)', "compact overview")
app = patch(app, 'self.t_files = ttk.Frame(self.tabs, padding=17)',
                  'self.t_files = ttk.Frame(self.tabs, padding=9)', "compact files")
app = patch(app, 'self.t_tools = ttk.Frame(self.tabs, padding=17)',
                  'self.t_tools = ttk.Frame(self.tabs, padding=9)', "compact tools")
app = patch(app, 'self.t_log = ttk.Frame(self.tabs, padding=17)',
                  'self.t_log = ttk.Frame(self.tabs, padding=9)', "compact logs")
app = patch(app, 'bottom = ttk.Frame(self, padding=(24, 8, 24, 11))',
                  'bottom = ttk.Frame(self, padding=(14, 3, 14, 5))', "compact footer")
app = patch(app, '''self.set_output(self.diagnostic, "\\n\\n".join(f"{key}\\n{'─'*42}\\n{value}" for key, value in report.items()))''',
                  'self.set_output(self.diagnostic, format_device_report(report))', "compact diagnostic summaries")
app = patch(app, '''        if not messagebox.askyesno("Расширенное сканирование",
                                   f"Проверить {len(self.current_packages)} пакетов? На это может уйти несколько минут.\\n\\n"
                                   "Разрешение на наложение окна НЕ означает показ рекламы."):
            return
''', '', "no extra confirmation for read-only scan")
app = patch(app, '''        buttons = ttk.Frame(self.t_apps)
        buttons.pack(fill="x", pady=(12, 2))
        for title, callback in (("Сведения", self.details), ("Очистить кэш", lambda: self.bulk("cache")),
                                ("Остановить", lambda: self.bulk("stop")),
                                ("Отключить", lambda: self.bulk("disable")),
                                ("Включить", lambda: self.bulk("enable")),
                                ("Удалить", lambda: self.bulk("uninstall")),
                                ("Очистить данные", lambda: self.bulk("data"))):
            ttk.Button(buttons, text=title, command=callback).pack(side="left", padx=(0, 6))
''',
'''        # Non-destructive operations are one-click; irreversible actions are grouped separately.
        normal = ttk.Frame(self.t_apps)
        normal.pack(fill="x", pady=(8, 2))
        for title, callback in (("Сведения", self.details), ("Кэш", lambda: self.bulk("cache")),
                                ("Остановить", lambda: self.bulk("stop")),
                                ("Отключить", lambda: self.bulk("disable")),
                                ("Включить", lambda: self.bulk("enable"))):
            ttk.Button(normal, text=title, command=callback).pack(side="left", padx=(0, 5))
        dangerous = ttk.Frame(self.t_apps)
        dangerous.pack(fill="x", pady=(3, 2))
        ttk.Button(dangerous, text="Удалить выбранные приложения", command=lambda: self.bulk("uninstall")).pack(side="left", padx=(0, 8))
        ttk.Button(dangerous, text="ПОЛНАЯ очистка данных", command=lambda: self.bulk("data")).pack(side="left")
''', "distinct group actions")
app = patch(app, '''        if not messagebox.askyesno(f"Подтверждение: {desc}",
                                   f"{desc} для {len(pkgs)} приложений?\\n\\n{preview}{suffix}\\n\\n"
                                   "Системные программы исключаются автоматически."):
            return
        if mode in ("data", "uninstall"):
            required = "ОЧИСТИТЬ" if mode == "data" else "УДАЛИТЬ"
            answer = simpledialog.askstring("Подтверждение владельца", f"Введите {required}, чтобы продолжить:", parent=self)
            if answer != required:
                messagebox.showinfo("Отменено", "Операция не начата")
                return
''',
'''        # No dialog for non-destructive actions; one explicit batch consent for
        # disable, full data clear, and profile uninstall. No typed keyword.
        if mode in ("data", "uninstall", "disable"):
            if not messagebox.askyesno(
                f"Подтверждение: {desc}",
                f"{desc} для {len(pkgs)} выбранных приложений?\\n\\n{preview}{suffix}\\n\\n"
                "Действие относится к текущему профилю Android.\\n"
                "Удаление/очистка данных необратимы; проверьте резервную копию.\\n"
                "Программа откажет системным пакетам. Продолжить?",
                default=messagebox.NO):
                return
''', "single confirmation for destructive actions")
app = patch(app, '''            messagebox.showinfo("Результат обслуживания",
                                f"Действие: {desc}\\nУспешно: {len(successes)}\\nС ошибкой: {len(failures)}\\n\\n"
                                "Подробности в журнале. Проверяйте состояние приложений на телефоне.")''',
'''            detail = "\\n".join(f"• {pkg}: {error[:370]}" for pkg, error in failures[:4])
            if len(failures) > 4:
                detail += f"\\n…и ещё ошибок: {len(failures)-4}"
            messagebox.showinfo("Результат обслуживания",
                                f"Действие: {desc}\\nУспешно: {len(successes)}\\nС ошибкой: {len(failures)}\\n\\n"
                                + (("ПРИЧИНЫ ОШИБОК:\\n" + detail + "\\n\\n") if failures else "")
                                + "Полные сведения — во вкладке «Журнал».")''',
"show error cause immediately")
# The selected operations are already backed by real ADB commands in 0.2.
# UI now makes uninstall/full clear unambiguous, with one consent for the group.
core_path.write_text(core, encoding="utf-8")
app_path.write_text(app, encoding="utf-8")
print("Applied Android Control Hub Windows 0.3 patch")
