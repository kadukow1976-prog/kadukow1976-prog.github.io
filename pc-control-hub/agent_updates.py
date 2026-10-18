"""Verified Android Control Hub agent updater for Windows.

Reads the current agent release manifest from this project's public repository,
checks installed versionCode, then downloads and SHA-256 verifies the exact APK.
No silent install, downgrade, uninstall, data clear or permission escalation.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request

PACKAGE="ru.kadukov.androidcontrolhub"
MANIFEST_URL=(
    "https://raw.githubusercontent.com/kadukow1976-prog/"
    "kadukow1976-prog.github.io/main/pc-control-hub/agent-release.json"
)
ASSET_PREFIX=(
    "https://github.com/kadukow1976-prog/kadukow1976-prog.github.io/"
    "releases/download/"
)
OFFICIAL_DEFAULT={
    "schema":1,
    "package":PACKAGE,
    "version_name":"0.8.1-beta",
    "version_code":9,
    "url":ASSET_PREFIX+"android-hub-beta-0.8.1/android-hub-beta-0.8.1.apk",
    "sha256":"e7c5964ce0ba6a5f42c33327bdd9f2c00dc3d3a8f89c61a2b7e30d6f7e6af9dc",
    "size":164205
}
MAX_APK_BYTES=50*1024*1024

@dataclass(frozen=True)
class InstalledAgent:
    installed: bool
    code: int | None
    serial: str | None
    diagnostics: str = ""
    user_id: str | None = None
    on_device: bool = False
    enabled: bool | None = None

def validate_manifest(data):
    if not isinstance(data,dict):raise ValueError("Неверная структура версии агента")
    if data.get("schema")!=1 or data.get("package")!=PACKAGE:
        raise ValueError("Неподдерживаемый агент или пакет")
    code=data.get("version_code")
    if type(code)!=int or not 9<=code<=10000000:
        raise ValueError("Недопустимый versionCode агента")
    name=data.get("version_name")
    if not isinstance(name,str) or not re.fullmatch(r"[0-9]+\.[0-9]+(?:\.[0-9]+)?-beta",name):
        raise ValueError("Недопустимое имя версии агента")
    url=data.get("url")
    pattern=(r"https://github\.com/kadukow1976-prog/kadukow1976-prog\.github\.io/"
             r"releases/download/android-hub-beta-[0-9.]+/android-hub-beta-[0-9.]+\.apk")
    if not isinstance(url,str) or not re.fullmatch(pattern,url):
        raise ValueError("Адрес APK не принадлежит ожидаемому GitHub Release")
    digest=data.get("sha256")
    if not isinstance(digest,str) or not re.fullmatch(r"[0-9a-f]{64}",digest):
        raise ValueError("Отсутствует достоверный SHA-256 APK")
    size=data.get("size")
    if type(size)!=int or not 0<size<=MAX_APK_BYTES:
        raise ValueError("Недопустимый размер APK")
    return dict(data)

def load_manifest(opener=None):
    """Fall back to pinned v0.8.1 when release manifest is unavailable."""
    if opener is None: opener=urllib.request.urlopen
    try:
        request=urllib.request.Request(MANIFEST_URL,headers={
            "User-Agent":"AndroidControlHub-Windows-AgentUpdater/0.3.5",
            "Accept":"application/json"
        })
        with opener(request,timeout=15) as response:
            raw=response.read(32769)
        if len(raw)>32768:raise ValueError("Слишком большой файл метаданных")
        result=validate_manifest(json.loads(raw.decode("utf-8")))
        if result["version_code"]>=OFFICIAL_DEFAULT["version_code"]:
            return result,"Актуальный манифест GitHub"
    except Exception:
        pass
    return validate_manifest(OFFICIAL_DEFAULT),"Проверенные встроенные сведения (офлайн)"

def parse_version_code(text):
    match=re.search(r"(?m)^\s*versionCode=(\d+)\b",text or "")
    return int(match.group(1)) if match else None

def _matching_package_info(text):
    """Parse ONLY our package from pm output; no cross-package version confusion."""
    for line in str(text or "").splitlines():
        match=re.fullmatch(
            r"package:"+re.escape(PACKAGE)+r"(?:\s+versionCode:(\d+))?(?:\s+.*)?",
            line.strip()
        )
        if match:
            return True, int(match.group(1)) if match.group(1) else None
    return False,None

def _current_android_user(service):
    """Never assume user 0; work with the currently selected Android profile."""
    failures=[]
    for args in (("am","get-current-user"),("cmd","activity","get-current-user")):
        try:
            result=str(service.shell(*args,timeout=12)).strip()
            if re.fullmatch(r"\d{1,4}",result):
                return result
            failures.append(" ".join(args)+": "+result[:100])
        except Exception as exc:
            failures.append(" ".join(args)+": "+str(exc)[:130])
    raise RuntimeError(
        "Не удалось определить активного пользователя Android. "
        "Установка и восстановление агента остановлены.\n"+
        "; ".join(failures)
    )

def _query_package_list(service,user,package,allow_uninstalled=False,disabled=False):
    """Read a package-list scoped to the actual Android user.

    None => failed query, (False,None) => successful absence,
    (True, versionCode|None) => package visible for current user.
    """
    variants=[
        ("pm","list","packages"),
        ("cmd","package","list","packages")
    ]
    issues=[]
    for prefix in variants:
        for show_version in (True,False):
            args=prefix + (
                ("-u",) if allow_uninstalled else ()
            ) + (
                ("-d",) if disabled else ()
            ) + ("--user",user) + (
                ("--show-versioncode",) if show_version else ()
            ) + (package,)
            try:
                value=str(service.shell(*args,timeout=18))
            except Exception as exc:
                issues.append(" ".join(args)+": "+str(exc)[:120])
                continue
            present,version=_matching_package_info(value)
            return (present,version), issues
    return None,issues

def get_installed_agent(service):
    """Confirm agent availability for ACTIVE Android user, not cached APK metadata.

    Package lists with -u and dumpsys may mention packages not installed
    for this user. These sources cannot prove an agent is operational.
    """
    serial=getattr(service,"serial",None)
    if not serial:
        raise RuntimeError("Выберите подключённый и авторизованный телефон.")
    user=_current_android_user(service)
    active,failures=_query_package_list(service,user,PACKAGE)
    if active is None:
        return InstalledAgent(True,None,str(serial),
            "Не удалось проверить установку для пользователя "+user+". "+
            "; ".join(failures[-4:]),user_id=user)

    present,code=active
    if not present:
        cached,cache_errors=_query_package_list(
            service,user,PACKAGE,allow_uninstalled=True)
        cached_found=bool(cached and cached[0])
        cached_code=cached[1] if cached_found else None
        diagnostics=("APK сохранён в Android, но НЕ установлен для профиля "+user+"."
                     if cached_found else "Агент отсутствует в профиле "+user+".")
        if cached is None:
            diagnostics+=" Проверка архива APK недоступна: "+"; ".join(cache_errors[-2:])
        return InstalledAgent(False,cached_code,str(serial),diagnostics,
                              user_id=user,on_device=cached_found)

    if code is None:
        detail=None
        try:
            detail=str(service.shell("dumpsys","package",PACKAGE,timeout=18))
        except Exception as exc:
            failures.append("dumpsys package: "+str(exc)[:100])
        if detail is not None and ("Package ["+PACKAGE+"]") in detail:
            code=parse_version_code(detail)
    code_errors="; ".join(failures[-3:]) if code is None else ""
    disabled_result,disabled_errors=_query_package_list(
        service,user,PACKAGE,disabled=True)
    enabled=None if disabled_result is None else not disabled_result[0]
    return InstalledAgent(True,code,str(serial),code_errors,
                          user_id=user,on_device=True,enabled=enabled)

def action_for(installed,manifest):
    code=manifest["version_code"]
    if not installed.installed:
        return "restore" if installed.on_device else "install"
    if installed.enabled is False:
        return "disabled"
    if installed.code is None:return "unknown"
    if installed.code>code:return "newer"
    if installed.code==code:return "current"
    return "upgrade"

def restore_existing_agent(service,expected_serial,expected_user):
    """Explicitly requested recovery for already-present package, no data wipe."""
    if str(getattr(service,"serial",None))!=str(expected_serial):
        raise RuntimeError("Активный телефон изменился: операция отменена.")
    state=get_installed_agent(service)
    if state.user_id!=expected_user:
        raise RuntimeError("Активный пользователь Android изменился.")
    if state.installed:
        return "Агент уже установлен в текущем профиле; восстановление не нужно."
    if not state.on_device:
        raise RuntimeError("В системе нет сохранённого APK. Используйте обычную установку.")
    response=str(service.shell("cmd","package","install-existing",
                               "--user",expected_user,PACKAGE,timeout=40))
    after=get_installed_agent(service)
    if not after.installed:
        raise RuntimeError("Android не подтвердил восстановление агента: "+response[:400])
    return ("Агент восстановлен для пользователя "+expected_user+
            ".\n"+response[:250])

def enable_existing_agent(service,expected_serial,expected_user):
    """Enable only our own known package, only after user consent."""
    if str(getattr(service,"serial",None))!=str(expected_serial):
        raise RuntimeError("Активный телефон изменился.")
    state=get_installed_agent(service)
    if state.user_id!=expected_user or not state.installed:
        raise RuntimeError("Агент не подтверждён для текущего профиля.")
    if state.enabled is True:
        return "Агент уже включён."
    response=str(service.shell("pm","enable","--user",expected_user,
                               PACKAGE,timeout=25))
    after=get_installed_agent(service)
    if after.enabled is False:
        raise RuntimeError("Агент остался отключённым: "+response[:250])
    return "Отправлена команда включения агента.\n"+response[:200]

def launch_agent(service,expected_serial=None):
    """Open the known exported WorkshopActivity, without silent privilege changes."""
    serial=str(getattr(service,"serial",None) or "")
    if not serial or (expected_serial is not None and serial!=str(expected_serial)):
        raise RuntimeError("Не выбран нужный телефон.")
    state=get_installed_agent(service)
    if not state.installed:
        raise RuntimeError("Агент не установлен в активном профиле.")
    if state.enabled is False:
        raise RuntimeError("Агент отключён. Сначала включите его вручную.")
    output=str(service.shell(
        "am","start","--user",str(state.user_id),"-n",
        PACKAGE+"/.WorkshopActivity",timeout=25))
    if re.search(r"(?i)\b(error|exception|does not exist|unable to resolve)\b",output):
        raise RuntimeError("Не удалось открыть Android Control Hub на телефоне: "+output[:500])
    return ("Отправлена команда открытия Android Control Hub на телефоне.\n"+
            output[:250])

def check_sha(path,manifest):
    f=Path(path)
    if not f.is_file() or f.stat().st_size!=manifest["size"]:return False
    digest=hashlib.sha256()
    with f.open("rb") as inp:
        for block in iter(lambda:inp.read(65536),b""):digest.update(block)
    return digest.hexdigest()==manifest["sha256"]

def agent_storage():
    root=Path(os.getenv("LOCALAPPDATA") or (Path.home()/".android-control-hub"))
    return root/"AndroidControlHub"/"agents"

def download_verified_agent(manifest,dest=None,opener=None):
    """Download to a temp file, verify sha256+size, then atomically replace."""
    manifest=validate_manifest(manifest)
    if opener is None:opener=urllib.request.urlopen
    folder=Path(dest) if dest is not None else agent_storage()
    folder.mkdir(parents=True,exist_ok=True)
    filename="AndroidControlHub-Agent-"+str(manifest["version_code"])+".apk"
    target=folder/filename
    if check_sha(target,manifest):return str(target)
    request=urllib.request.Request(manifest["url"],headers={
        "User-Agent":"AndroidControlHub-Windows-AgentUpdater/0.3.5"
    })
    temp_name=None
    try:
        with opener(request,timeout=45) as response:
            with tempfile.NamedTemporaryFile(mode="wb",dir=folder,
                  prefix="agent-",suffix=".part",delete=False) as temp:
                temp_name=temp.name
                digest=hashlib.sha256()
                total=0
                while True:
                    part=response.read(65536)
                    if not part:break
                    total+=len(part)
                    if total>MAX_APK_BYTES or total>manifest["size"]:
                        raise ValueError("Неверный размер загружаемого APK")
                    digest.update(part)
                    temp.write(part)
        if total!=manifest["size"] or digest.hexdigest()!=manifest["sha256"]:
            raise ValueError("Контрольная сумма или размер APK не совпали. Установка отменена.")
        os.replace(temp_name,target)
        temp_name=None
        return str(target)
    finally:
        if temp_name:
            try:os.unlink(temp_name)
            except OSError:pass

def install_agent_if_needed(service,manifest,path):
    """Recheck target serial and version immediately before invoking adb install."""
    manifest=validate_manifest(manifest)
    if not check_sha(path,manifest):
        raise ValueError("APK не соответствует контрольной сумме официального релиза")
    state=get_installed_agent(service)
    status=action_for(state,manifest)
    if status=="current":return "Агент уже установлен в версии "+str(state.code)+"; установка не требуется."
    if status=="newer":return "На телефоне версия "+str(state.code)+" новее доступной "+str(manifest["version_code"])+". Понижение запрещено."
    if status=="unknown":
        raise RuntimeError("Версия установленного агента не определена; автоматическая установка запрещена.")
    if status in ("restore","disabled"):
        raise RuntimeError("Агент найден в системе, но не активен. Используйте восстановление или включение после подтверждения.")
    # No -d flag, no uninstall or user-data reset.
    result=service.install_apk([str(path)])
    return "Установка APK: "+str(result)

def known_old_apk_name(filename):
    """Known beta 0.2 file reported by OnePlus owner (versionCode 2)."""
    return bool(re.fullmatch(r"android-hub-beta-0\.2\.apk",Path(filename).name,re.I))
