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
from agent_updates import (load_manifest, get_installed_agent, action_for,
                           download_verified_agent, install_agent_if_needed,
                           check_sha, known_old_apk_name, restore_existing_agent,
                           enable_existing_agent, launch_agent)

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
    # Use the same multi-command inspection as the updater. Some Android 16
    # builds reject 'pm path' but allow 'pm list packages --show-versioncode'.
    # Never confuse a failed query with an absent application.
    try:
        agent_info=get_installed_agent(service)
        if agent_info.installed:
            if agent_info.code is None and agent_info.diagnostics.startswith("Не удалось проверить установку"):
                agent_status="Не удалось проверить для профиля "+str(agent_info.user_id)
            else:
                agent_status=("Установлен · versionCode "+str(agent_info.code)
                          if agent_info.code is not None
                          else "Установлен · версия не определена")
                if agent_info.enabled is False:
                    agent_status+=" · ОТКЛЮЧЁН"
                agent_status+=" · профиль "+str(agent_info.user_id)
        elif agent_info.on_device:
            agent_status=("Удалён для профиля "+str(agent_info.user_id)+
                          " · возможно восстановление без скачивания")
        else:
            agent_status="Не установлен для профиля "+str(agent_info.user_id)
        agent_diag=agent_info.diagnostics
    except Exception as exc:
        agent_status="Не удалось проверить"
        agent_diag=str(exc)[:320]
    shizuku=exists_from_pm(_read(service,"pm","path",SHIZUKU_PACKAGE))
    build=_read(service,"getprop","ro.build.type").strip()
    enforce=_read(service,"getenforce").strip()
    output=[
        AccessResult("ADB-подключение",
            "Подтверждено · UID "+str(uid) if uid is not None else "Не удалось определить",
            "Android Debug Bridge разрешён владельцем при подключении. "
            "Это не system и не root."),
        AccessResult("Сервисный агент",
            agent_status,
            "Версия определяется теми же безопасными командами, что и при обновлении. "
            "Установка APK не выдаёт системные привилегии."
            + (" Детали ADB: "+agent_diag if agent_diag else "")),
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

    def perform_special(state,operation):
        if state.user_id is None:
            messagebox.showwarning("Не определён профиль Android",
                "Нельзя выполнять восстановление без идентификатора пользователя.",parent=app)
            return
        if operation=="restore":
            text=("Восстановить зарегистрированный APK Android Control Hub "
                  "для профиля "+str(state.user_id)+"?\n\n"
                  "Команда Android: cmd package install-existing --user "
                  +str(state.user_id)+" "+AGENT_PACKAGE+
                  "\n\nДанные приложения не очищаются. Root не требуется.")
            label="Восстановление агента"
            call=lambda:restore_existing_agent(app.service,state.serial,state.user_id)
        else:
            text=("Включить Android Control Hub для профиля "+str(state.user_id)+
                  "?\nДействие применяется только к этому пакету.")
            label="Включение агента"
            call=lambda:enable_existing_agent(app.service,state.serial,state.user_id)
        if not messagebox.askyesno(label,text,parent=app,default=messagebox.NO):
            return
        def complete(result):
            app.log(label+": "+str(result))
            messagebox.showinfo(label,str(result),parent=app)
            audit()
        app.busy_call(label,call,complete)

    def open_agent():
        def done(result):
            app.log("Запуск агента: "+str(result))
            messagebox.showinfo("Запуск Android-агента",
                str(result)+"\n\nПроверьте экран телефона.",parent=app)
        app.busy_call("Открыть Android Control Hub на телефоне",
                      lambda:launch_agent(app.service),done)

    def display_agent_state(state,manifest,origin):
        plan=action_for(state,manifest)
        if plan=="restore":
            if messagebox.askyesno("Агент удалён из текущего профиля",
                "Android сохранил APK агента (versionCode "+
                str(state.code if state.code is not None else "неизвестен")+
                "), но он НЕ установлен для пользователя "+str(state.user_id)+
                ".\n\nВосстановить его штатной командой Android "
                "без загрузки и очистки данных?",
                parent=app,default=messagebox.NO):
                perform_special(state,"restore")
            return
        if plan=="disabled":
            if messagebox.askyesno("Агент отключён",
                "Android подтверждает, что агент установлен, но отключён "
                "для пользователя "+str(state.user_id)+
                ".\nВключить приложение?",
                parent=app,default=messagebox.NO):
                perform_special(state,"enable")
            return
        if plan=="current":
            messagebox.showinfo("Агент уже актуален",
                "Агент обнаружен для пользователя "+str(state.user_id)+
                ", версия "+str(state.code)+".\n\n"
                "Если значка нет, нажмите «Открыть агент на телефоне» "
                "либо проверьте скрытые приложения OxygenOS.\n"
                "Релиз: "+manifest["version_name"]+"\nИсточник: "+origin,
                parent=app)
            return
        if plan=="newer":
            messagebox.showinfo("Новая версия уже на телефоне",
                "На телефоне versionCode "+str(state.code)+", а доступный агент — "
                +str(manifest["version_code"])+".\nПонижение версии запрещено.",
                parent=app)
            return
        if plan=="unknown":
            messagebox.showwarning("Не удалось определить версию агента",
                "Агент обнаружен, но Android не предоставил versionCode.\n"
                "Установка не выполнена, чтобы не понизить версию.\n\n"
                "Подробности проверок ADB:\n" + (state.diagnostics or
                "Доступные способы проверки не вернули номер версии.")[:1100],
                parent=app)
            return
        text=("На устройстве агент не обнаружен." if plan=="install"
              else "На устройстве версия "+str(state.code)+".")
        if not messagebox.askyesno("Установить актуальный агент",
            text+"\n\nДоступна "+manifest["version_name"]+" (versionCode "
            +str(manifest["version_code"])+").\n"
            "Файл будет загружен с GitHub и проверен по SHA-256.\n"
            "После проверки выполнить установку на выбранный телефон?\n\n"
            "Root, Device Owner или права system это НЕ предоставляет.",
            parent=app,default=messagebox.NO):
            return
        def task():
            expected_serial=state.serial
            if str(getattr(app.service,"serial",None))!=expected_serial:
                raise RuntimeError("Выбранный телефон изменился; установка отменена.")
            verified=download_verified_agent(manifest)
            if str(getattr(app.service,"serial",None))!=expected_serial:
                raise RuntimeError("Подключённый телефон изменился после загрузки.")
            return install_agent_if_needed(app.service,manifest,verified)
        def complete(result):
            app.log("Агент: "+str(result))
            messagebox.showinfo("Установка агента",str(result),parent=app)
            audit()
        app.busy_call("Загрузить, проверить и установить агент",
                      task,complete)

    def update_agent():
        def inspect():
            manifest,source=load_manifest()
            state=get_installed_agent(app.service)
            return manifest,source,state
        app.busy_call("Проверить версию агента",
                      inspect,
                      lambda result:(display_agent_state(result[2],result[0],result[1]),audit()))

    def install_agent():
        filename=filedialog.askopenfilename(
            parent=app,title="Выберите официальный APK Android Control Hub",
            filetypes=[("Установочные файлы Android","*.apk")])
        if not filename:return
        if known_old_apk_name(filename):
            messagebox.showerror("Старый агент — установка запрещена",
                "Выбран android-hub-beta-0.2.apk (versionCode 2).\n"
                "На телефоне уже была версия 9. Android не разрешает понижение.\n\n"
                "Нажмите «Скачать / обновить агент» для установки проверенной версии. "
                "Удалять текущую программу не нужно.",parent=app)
            return
        def inspect_file():
            manifest,source=load_manifest()
            if not check_sha(filename,manifest):
                raise RuntimeError(
                    "Выбранный APK не совпадает с проверенным официальным релизом "
                    +manifest["version_name"]+".\n\n"
                    "Используйте кнопку «Скачать / обновить агент». "
                    "Для любых других APK используйте раздел «Установка / Fastboot».")
            return manifest,source,get_installed_agent(app.service)
        def report(result):
            manifest,origin,state=result
            plan=action_for(state,manifest)
            if plan in ("current","newer","unknown","restore","disabled"):
                display_agent_state(state,manifest,origin)
                return
            if not messagebox.askyesno("Установить проверенный APK",
                "Файл совпал с официальным релизом "+manifest["version_name"]+
                ".\nТелефон: "+str(state.serial)+
                "\nУстановленная версия: "+
                (str(state.code) if state.installed else "агента нет")+
                "\n\nУстановить выбранный APK без потери данных?",
                parent=app,default=messagebox.NO):
                return
            def execute():
                if str(getattr(app.service,"serial",None))!=state.serial:
                    raise RuntimeError("Выбранный телефон изменился.")
                return install_agent_if_needed(app.service,manifest,filename)
            app.busy_call("Установить проверенный APK агента",execute,
                lambda outcome:(app.log("Агент: "+str(outcome)),
                                messagebox.showinfo("Установка",str(outcome),parent=app),
                                audit()))
        app.busy_call("Проверить выбранный файл агента",inspect_file,report)

    def open_agent_settings():
        app.busy_call("Открыть системные настройки агента",
            lambda:app.service.open_package_settings(AGENT_PACKAGE),
            lambda result:app.log(str(result)))

    def doc(url):
        webbrowser.open(url)
    for label,callback in (
        ("Проверить уровни",audit),
        ("Скачать / обновить агент",update_agent),
        ("Открыть агент на телефоне",open_agent),
        ("Установить проверенный APK",install_agent),
        ("Настройки агента",open_agent_settings),
        ("Shizuku — инструкция",lambda:doc("https://shizuku.rikka.app/guide/setup/")),
        ("Device Owner — инструкция",lambda:doc("https://developer.android.com/work/dpc/dedicated-devices")),
    ):
        ttk.Button(buttons,text=label,command=callback).pack(side="left",padx=(0,5))
    ttk.Label(frame,text="Проверка прав ≠ выдача прав. Действия с очисткой данных остаются в разделе «Приложения».",
              style="Muted.TLabel").pack(anchor="w",pady=(5,0))
