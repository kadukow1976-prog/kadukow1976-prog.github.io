"""Read-only diagnostics for local Android Platform-Tools and USB modes.

No flashing, reboot, device changes, downloads or administrator privileges.
Every fastboot getvar query is pinned to an enumerated device serial.
"""
from __future__ import annotations
import re
import subprocess
import sys
from pathlib import Path

MIN_RECOMMENDED=(37,0,1)  # Official Android Platform-Tools release, July 2026.
MAX_DEVICES=8
SAFE_SERIAL=re.compile(r"^[A-Za-z0-9_.:-]{1,100}$")

def _run(binary,args,runner):
    kwargs={"capture_output":True,"text":True,"errors":"replace","timeout":12}
    if sys.platform=="win32":
        kwargs["creationflags"]=getattr(subprocess,"CREATE_NO_WINDOW",0)
    try:
        result=runner([str(binary),*args],**kwargs)
        output=(result.stdout or "")+"\n"+(result.stderr or "")
        return result.returncode,output.strip()
    except subprocess.TimeoutExpired:
        return -1,"Превышено время ожидания (12 с)"
    except OSError as exc:
        return -1,"Ошибка запуска: "+str(exc)[:200]

def _version(output,tool):
    pattern=(r"(?im)^\s*Version\s+(\d+\.\d+\.\d+)\b"
             if tool=="adb" else
             r"(?im)^\s*fastboot version\s+(\d+\.\d+\.\d+)\b")
    match=re.search(pattern,output)
    return match.group(1) if match else None

def _fastboot_var(output,name):
    # Fastboot commonly writes successful getvar values to stderr.
    match=re.search(r"(?im)^\s*(?:\(bootloader\)\s*)?"+
                    re.escape(name)+r"\s*:\s*([a-z0-9_-]+)\s*$",output)
    return match.group(1).lower() if match else None

def _adb_devices(output):
    devices=[]
    for line in output.splitlines():
        match=re.match(r"^\s*(\S+)\s+(device|unauthorized|offline|recovery|sideload)\b",
                       line,re.I)
        if match and SAFE_SERIAL.fullmatch(match.group(1)):
            devices.append((match.group(1),match.group(2).lower()))
    return devices[:MAX_DEVICES]

def _fastboot_devices(output):
    serials=[]
    for line in output.splitlines():
        match=re.match(r"^\s*(\S+)\s+fastboot(?:\s|$)",line,re.I)
        if match and SAFE_SERIAL.fullmatch(match.group(1)) and match.group(1) not in serials:
            serials.append(match.group(1))
    return serials[:MAX_DEVICES]

def diagnose_platform_tools(service,runner=None):
    """Return a concise Russian read-only report; no connected ADB device required."""
    if runner is None:runner=subprocess.run
    pair=service.discover_platform_tools()
    if not pair:
        return ("ADB/Fastboot не найдены.\n"
                "Используйте «Восстановить ADB/Fastboot» для официального пакета Google.\n"
                "Телефон не изменялся.")
    adb,fastboot=map(str,pair)
    if not all(Path(p).is_file() for p in (adb,fastboot)):
        return "Пути ADB/Fastboot недействительны. Восстановите Platform-Tools."
    lines=["ANDROID PLATFORM-TOOLS · БЕЗ ИЗМЕНЕНИЯ ТЕЛЕФОНА",
           "─"*45]
    versions=[]
    for binary,name in ((adb,"adb"),(fastboot,"fastboot")):
        rc,out=_run(binary,["version" if name=="adb" else "--version"],runner)
        version=_version(out,name) if rc==0 else None
        versions.append(version)
        lines.append(name.upper()+": "+("v"+version if version else "версия не определена"))
        lines.append("Файл: "+binary)
        if rc!=0:lines.append("Ошибка: "+out[:180])
    for v in versions:
        if v and tuple(map(int,v.split(".")))<MIN_RECOMMENDED:
            lines.append("РЕКОМЕНДАЦИЯ: Platform-Tools старее 37.0.1. "
                         "Проверьте обновление из официального источника Google.")
            break

    lines.extend(["","ADB / ОТЛАДКА USB"])
    rc,out=_run(adb,["devices","-l"],runner)
    if rc!=0:
        lines.append("ADB недоступен: "+out[:220])
    else:
        devices=_adb_devices(out)
        if not devices:lines.append("Устройства ADB не обнаружены.")
        for serial,state in devices:
            meaning={"device":"авторизован","unauthorized":"требуется подтверждение на телефоне",
                     "offline":"не в сети","recovery":"Recovery",
                     "sideload":"Sideload"}.get(state,state)
            lines.append(serial+" — "+meaning)

    lines.extend(["","FASTBOOT / BOOTLOADER / FASTBOOTD"])
    rc,out=_run(fastboot,["devices"],runner)
    if rc!=0:
        lines.append("Fastboot недоступен: "+out[:220])
    else:
        serials=_fastboot_devices(out)
        if not serials:lines.append("Устройства Fastboot не обнаружены.")
        for serial in serials:
            rc,mode_text=_run(fastboot,["-s",serial,"getvar","is-userspace"],runner)
            mode=_fastboot_var(mode_text,"is-userspace")
            if rc!=0 and "FAILED" in mode_text.upper():mode=None
            label={"yes":"fastbootd (userspace)","no":"Bootloader Fastboot"}.get(
                mode,"тип Fastboot не определён")
            lines.append(serial+" — "+label)
            rc,slot_text=_run(fastboot,["-s",serial,"getvar","current-slot"],runner)
            slot=_fastboot_var(slot_text,"current-slot")
            if slot in ("a","b"):lines.append("  Активный слот: "+slot.upper())
        if len(serials)==MAX_DEVICES:
            lines.append("Показано не более 8 устройств Fastboot.")
    lines.extend(["","Команды: version, devices, getvar. "
                  "Ни прошивка, ни перезагрузка, ни очистка не запускались."])
    return "\n".join(lines)
