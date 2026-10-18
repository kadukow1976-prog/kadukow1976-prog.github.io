"""Android Control Hub / Windows: explicit Android privilege capability center.

Only opt-in ADB commands and read-only discovery. The tool DOES NOT silently
grant permissions, enroll Device Owner, alter FRP, perform root, or install apps.
"""
from __future__ import annotations

import re
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import webbrowser
from dataclasses import dataclass

AGENT_PACKAGE="ru.kadukov.androidcontrolhub"
SHIZUKU_PACKAGE="moe.shizuku.privileged.api"

@dataclass
class AccessResult:
    name: str
    state: str
    explanation: str

def _read(service, *args):
    # Service.shell also pins commands to the explicitly chosen device serial.
    try:
        response=service.shell(*args,timeout=14)
        return str(response).strip()
    except Exception as e:
        return "[недоступно] " + str(e)[:130]

def parse_uid(value):
    # Avoid turning parsing failures into a claimed privilege grant.
    match=re.search(r"(?m)^\s*(\d{1,5})\s*$",value or "")
    return int(match.group(1)) if match else None

def exists_from_pm(text):
    return bool(re.search(r"(?m)^package:",text or ""))

def probe_access(service):
    """Read-only inspection, no pm grant, appops set, dpm, su, reset or pm clear."""
    uid=parse_uid(_read(service,"id","-u"))
    agent=exists_from_pm(_read(service,"pm","path",AGENT_PACKAGE))
    shizuku=exists_from_pm(_read(service,"pm","path",SHIZUKU_PACKAGE))
    build=_read(service,"getprop","ro.build.type").strip()
    enforce=_read(service,"getenforce").strip()
    output=[
        AccessResult("ADB-подключение",
            "Подтверждено · UID "+str(uid) if uid is not None else "Не удалось определить",
            "Android Debug Bridge разрешён владельцем при подключении. "
            "Это не system и не root."),
        AccessResult("Сервисный агент",
            "Установлен" if agent else "Не найден",
            "APK можно установить через ADB только после выбора файла и подтверждения. "
            "Установка не выдаёт системные привилегии."),
        AccessResult("Shizuku",
            "APK найден; служба и разрешение не проверены" if shizuku else "Приложение не найдено",
            "Shizuku нужно запустить и разрешить доступ на телефоне. "
            "Сам факт установки не означает подключение."),
        AccessResult("Device Admin",
            "Требует проверки и согласия на телефоне",
            "Обычный администратор устройства не получает права очищать данные других программ."),
        AccessResult("Profile Owner / Device Owner",
            "Не активируется этой программой",
            "Корпоративный DPC и соответствующее развёртывание. "
            "Для обычного уже настроенного телефона не включается одной кнопкой."),
        AccessResult("Root / su",
            "Не проверяется автоматически",
            "Root требует заранее подготовленного устройства и отдельного подтверждения. "
            "В этой версии root-команды не выполняются."),
        AccessResult("Системная подпись / privileged APK",
            "Не выдаётся обычной установкой",
            "Нужны подпись OEM/производителя и соответствующие привилегии в системном образе."),
        AccessResult("SELinux",
            enforce if enforce in ("Enforcing","Permissive","Disabled") else "Неизвестно",
            "Программа не меняет режим SELinux."),
        AccessResult("Тип сборки Android",
            build if build in ("user","userdebug","eng") else "Неизвестно",
            "Тип сборки влияет на доступность отладочных возможностей; сам по себе не даёт root."),
    ]
    return output

def attach_access_center(app):
    """Attach a compact new tab to the existing notebook; no startup permissions."""
    frame=ttk.Frame(app.tabs,padding=9)
    app.tabs.add(frame,text="Полномочия")
    ttk.Label(frame,text="Центр полномочий и сервисного агента",style="Header.TLabel").pack(anchor="w",pady=(0,4))
    ttk.Label(frame,text="Проверка реальных возможностей USB/ADB, без изменения телефона.",
              style="Muted.TLabel").pack(anchor="w",pady=(0,8))
    listbox=tk.Text(frame,height=17,wrap="word",font=("Segoe UI",10),
                    relief="flat",bg="#142538",fg="#eaf5fc")
    listbox.pack(fill="both",expand=True,pady=5)
    listbox.insert("1.0","Нажмите «Проверить доступы». Система не запрашивает опасные разрешения автоматически.")
    listbox.configure(state="disabled")
    buttons=ttk.Frame(frame)
    buttons.pack(fill="x",pady=(5,4))

    def display(rows):
        listbox.configure(state="normal")
        listbox.delete("1.0","end")
        for r in rows:
            listbox.insert("end",r.name+" — "+r.state+"\n"+r.explanation+"\n\n")
        listbox.configure(state="disabled")

    def audit():
        # Reuses existing ADB service and background worker; UI stays responsive.
        app.busy_call("Проверка доступов Android",lambda:probe_access(app.service),
                      lambda results:(display(results),app.log("Проверены уровни доступа и наличие сервисного агента")))

    def install_agent():
        filename=filedialog.askopenfilename(
            parent=app,title="Выберите доверенный APK Android Control Hub",
            filetypes=[("Установочные файлы Android","*.apk")])
        if not filename:return
        if not messagebox.askyesno("Установить APK сервисного агента",
            "Установить выбранный APK на активный авторизованный телефон?\n\n"
            "Это НЕ активирует root, Device Owner или системные права.\n"
            "Проверяйте источник и цифровую подпись APK перед установкой.",
            parent=app,default=messagebox.NO):return
        app.busy_call("Установка выбранного APK",lambda:app.service.install_apk([filename]),
                      lambda result:(app.log("Установка APK: "+str(result)),audit()))

    def open_agent_settings():
        app.busy_call("Открыть системные настройки агента",
            lambda:app.service.open_package_settings(AGENT_PACKAGE),
            lambda result:app.log(str(result)))

    def doc(url):
        webbrowser.open(url)
    for label,callback in (
        ("Проверить уровни",audit),
        ("Установить APK агента",install_agent),
        ("Настройки агента",open_agent_settings),
        ("Shizuku — инструкция",lambda:doc("https://shizuku.rikka.app/guide/setup/")),
        ("Device Owner — инструкция",lambda:doc("https://developer.android.com/work/dpc/dedicated-devices")),
    ):
        ttk.Button(buttons,text=label,command=callback).pack(side="left",padx=(0,5))
    ttk.Label(frame,text="Проверка прав ≠ выдача прав. Действия с очисткой данных остаются в разделе «Приложения».",
              style="Muted.TLabel").pack(anchor="w",pady=(5,0))
