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

def get_installed_agent(service):
    """Prefer the existing installed version; gracefully probe alternative Android APIs.

    On Android 16 some OEM builds return adb exit code 1 for 'dumpsys package'.
    A failed read-only probe must NEVER be treated as permission to downgrade,
    uninstall, or clear an existing application.
    """
    serial=getattr(service,"serial",None)
    if not serial:
        raise RuntimeError("Выберите подключённый и авторизованный телефон.")
    issues=[]
    def try_shell(*args):
        try:
            return str(service.shell(*args,timeout=20)).strip()
        except Exception as exc:
            issues.append(" ".join(args)+": "+str(exc)[:160])
            return None

    path=try_shell("pm","path",PACKAGE)
    if path is not None and not re.search(r"(?m)^package:",path):
        # A successful package-manager lookup explicitly reports no APK paths.
        return InstalledAgent(False,None,str(serial))

    # Android's dumpsys sometimes requires capabilities removed on OEM builds.
    # Retain as the first version query for backwards compatibility.
    if path is not None:
        detail=try_shell("dumpsys","package",PACKAGE)
        if detail is not None and ("Package ["+PACKAGE+"]") in detail:
            version=parse_version_code(detail)
            if version is not None:
                return InstalledAgent(True,version,str(serial))

    for args in (
        ("pm","list","packages","--show-versioncode",PACKAGE),
        ("cmd","package","list","packages","--show-versioncode",PACKAGE),
    ):
        result=try_shell(*args)
        if result is None:
            continue
        present,version=_matching_package_info(result)
        if present:
            if version is not None:
                return InstalledAgent(True,version,str(serial))
            # A listing without versionCode is not sufficient to authorize install.
            continue
        if path is None:
            # A successful list-packages query proves it is not installed.
            return InstalledAgent(False,None,str(serial))

    if path is None:
        detail="; ".join(issues[-4:]) or "нет ответа от Android"
        raise RuntimeError(
            "Не удалось проверить установленный агент через ADB. "
            "Android отклонил команды проверки пакета. "
            "Установка отменена, старый агент не затронут.\n"
            "Диагностика: "+detail
        )
    # APK is installed but no reliable version was obtained. Fail closed.
    return InstalledAgent(True,None,str(serial),
        "; ".join(issues[-4:]) or
        "Система не вернула versionCode в доступных командах.")

def action_for(installed,manifest):
    code=manifest["version_code"]
    if not installed.installed:return "install"
    if installed.code is None:return "unknown"
    if installed.code>code:return "newer"
    if installed.code==code:return "current"
    return "upgrade"

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
    # No -d flag, no uninstall or user-data reset.
    result=service.install_apk([str(path)])
    return "Установка APK: "+str(result)

def known_old_apk_name(filename):
    """Known beta 0.2 file reported by OnePlus owner (versionCode 2)."""
    return bool(re.fullmatch(r"android-hub-beta-0\.2\.apk",Path(filename).name,re.I))
