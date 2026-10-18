"""Patch baseline Windows 0.1 source into Windows 0.2 auto-recovery build.
Fails closed if expected upstream markers change.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
core_path=root/"src"/"ach_core.py"
app_path=root/"src"/"app.py"
core=core_path.read_text(encoding="utf-8")
app=app_path.read_text(encoding="utf-8")

def rep(src, old, new):
    if src.count(old)!=1: raise RuntimeError("Upstream source mismatch: "+old[:65])
    return src.replace(old,new,1)

core=rep(core, "import io\n", "import io\nimport hashlib\n")
p=core.index("    def _tool(self, which: str) -> str:")
q=core.index("    def run(self, tool: str,",p)
core=core[:p]+'''    def discover_platform_tools(self) -> tuple[str, str] | None:
        """Find existing Android SDK or managed tools, never trust stale config."""
        if self.adb and self.fastboot and Path(self.adb).is_file() and Path(self.fastboot).is_file():
            return self.adb, self.fastboot
        folders = [config_root() / "platform-tools",
                   Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "Android" / "Sdk" / "platform-tools"]
        for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            if os.environ.get(env):
                folders.append(Path(os.environ[env]) / "platform-tools")
        for folder in folders:
            adb, fastboot = folder / "adb.exe", folder / "fastboot.exe"
            if adb.is_file() and fastboot.is_file() and all((folder / n).is_file()
                    for n in ("AdbWinApi.dll","AdbWinUsbApi.dll")):
                return str(adb), str(fastboot)
        adb, fastboot = shutil.which("adb"), shutil.which("fastboot")
        return (adb, fastboot) if adb and fastboot else None

    def _tool(self, which: str) -> str:
        candidate = self.adb if which == "adb" else self.fastboot
        if candidate and Path(candidate).is_file():
            return candidate
        pair = self.discover_platform_tools()
        if pair:
            self.adb, self.fastboot = pair
            return pair[0 if which == "adb" else 1]
        raise ServiceError(f"{which.upper()} не найден. Программа автоматически восстановит Platform Tools при запуске.")

'''+core[q:]
core=rep(core,
    '    def install_platform_tools(self) -> tuple[str, str]:',
    '    def install_platform_tools(self, progress=None) -> tuple[str, str]:')
core=rep(core,
    '        root = config_root() / "platform-tools"\n        wanted = {"adb.exe", "fastboot.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll"}',
    '''        if progress: progress("Проверка загруженного архива Google...")
        root = config_root() / "platform-tools"
        wanted = {"adb.exe", "fastboot.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll"}''')
core=rep(core,
    '                root.mkdir(parents=True, exist_ok=True)\n                for name, info in members.items():',
    '''                # ZIP CRC and PE signatures must pass before replacing any existing files.
                new_files = {name: archive.read(info) for name, info in members.items()}
                for name, binary in new_files.items():
                    if not binary.startswith(b"MZ"):
                        raise ServiceError(f"Повреждённый PE-файл в архиве: {name}")
                if progress: progress("Установка проверенных файлов ADB/Fastboot...")
                root.mkdir(parents=True, exist_ok=True)
                for name, info in members.items():''')
core=rep(core,
    '''                    temp.write_bytes(archive.read(info))
                    os.replace(temp, target)''',
    '''                    temp.write_bytes(new_files[name])
                    os.replace(temp, target)''')
core=rep(core,
    '        self.adb = str(root / "adb.exe")',
    '''        metadata = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in wanted}
        (root / "install-info.json").write_text(json.dumps({"origin":url,"sha256":metadata},indent=2),encoding="utf-8")
        if progress: progress("ADB и Fastboot готовы")
        self.adb = str(root / "adb.exe")''')

app=rep(app,'VERSION = "0.1.0"','VERSION = "0.2.0"')
app=rep(app,'        self.after(350, self.refresh_devices)','        self.after(350, self.bootstrap_platform_tools)')
app=rep(app,'text="Установить ADB автоматически"','text="Восстановить ADB/Fastboot"')
app=rep(app,'    def refresh_devices(self):\n', '''    def bootstrap_platform_tools(self):
        if self.running:
            self.after(700, self.bootstrap_platform_tools)
            return
        pair = self.service.discover_platform_tools()
        if pair:
            self.apply_platform_tools(pair)
            self.refresh_devices()
        else:
            self.log("Не найден комплект ADB/Fastboot. Автоматическая загрузка с Google.")
            self.install_tools(automatic=True)

    def apply_platform_tools(self, pair):
        self.service.adb, self.service.fastboot = pair
        self.adb_path.set(pair[0]); self.fastboot_path.set(pair[1])
        self.config_path.write_text(json.dumps({"adb":pair[0],"fastboot":pair[1]},
            ensure_ascii=False, indent=2),encoding="utf-8")

    def refresh_devices(self):
        if not self.service.discover_platform_tools():
            self.install_tools(automatic=True)
            return
''')
p=app.index('    def install_tools(self):')
q=app.index('    def tools_dialog(self):',p)
app=app[:p]+'''    def install_tools(self, automatic=False):
        if sys.platform != "win32":
            messagebox.showinfo("Windows", "Автоматическая установка доступна на Windows.")
            return
        if self.running: return
        if not automatic and not messagebox.askyesno("Восстановить ADB/Fastboot",
            "Скачать официальный пакет Android Platform Tools с dl.google.com и установить без администратора?"):
            return
        if automatic:
            self.state_value.set("ADB/Fastboot отсутствуют — загружаю официальный пакет Google...")
        def progress(msg):
            try: self.after(0, lambda text=msg: self.state_value.set(text))
            except RuntimeError: pass
        def success(pair):
            self.apply_platform_tools(pair)
            self.log("✓ ADB/Fastboot готовы: "+str(Path(pair[0]).parent))
            if not automatic:
                messagebox.showinfo("Готово", "Android Platform Tools установлены")
            self.refresh_devices()
        self.busy_call("Автоматическое восстановление ADB/Fastboot",
            lambda: self.service.install_platform_tools(progress), success, require_device=False)

'''+app[q:]
core_path.write_text(core,encoding="utf-8")
app_path.write_text(app,encoding="utf-8")
print("Windows 0.2 patch applied")
