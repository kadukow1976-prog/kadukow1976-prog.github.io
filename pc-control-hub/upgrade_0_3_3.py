"""Android Control Hub 0.3.3: SamFw-like batch app selection, safe ADB results.
Apply after 0.3.2. Exact anchors checked; build fails on unexpected UI changes.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
p=root/"src"/"app.py"
app=p.read_text(encoding="utf-8")
def replace(old,new,feature):
    global app
    n=app.count(old)
    if n!=1: raise RuntimeError("0.3.3: "+feature+" source anchor count="+str(n))
    app=app.replace(old,new,1)

replace('VERSION = "0.3.2"','VERSION = "0.3.3"',"version")
replace('        self.app_table = ttk.Treeview(table_box, columns=("package", "overlay", "boot"), show="headings",',
'''        self.marked_packages = set()
        self.mark_counter = tk.StringVar(value="Отмечено: 0")
        self.app_table = ttk.Treeview(table_box, columns=("checked", "package", "overlay", "boot"), show="headings",''',
"add check column")
replace('        self.app_table.heading("package", text="Пакет установленного приложения")',
'''        self.app_table.heading("checked", text="✓")
        self.app_table.column("checked", width=34, minwidth=34, stretch=False, anchor="center")
        self.app_table.heading("package", text="Пакет установленного приложения")''',"column header")
replace('        self.app_table.bind("<Double-1>", lambda _event: self.details())',
'''        self.app_table.bind("<Button-1>", self.toggle_package_mark, add="+")
        self.app_table.bind("<Double-1>", lambda _event: self.details())
        markers = ttk.Frame(self.t_apps)
        markers.pack(fill="x", pady=(4, 4))
        ttk.Button(markers, text="✓ Отметить видимые", command=self.mark_visible_packages).pack(side="left", padx=(0, 5))
        ttk.Button(markers, text="Снять отметки", command=self.unmark_packages).pack(side="left", padx=(0, 8))
        ttk.Label(markers, textvariable=self.mark_counter, style="Muted.TLabel").pack(side="left")
        ttk.Label(markers, text="Нажмите квадрат в первом столбце", style="Muted.TLabel").pack(side="right")''',"batch toolbar")
replace('        self.app_table.delete(*self.app_table.get_children())',
'''        # Keep checkmarks even when search changes, but drop packages no longer installed.
        self.marked_packages.intersection_update(self.current_packages)
        self.app_table.delete(*self.app_table.get_children())''',"persist checks")
replace('''                                  values=(package, "Заявлено" if package in self.flagged_overlays else "—",
                                          "Заявлено" if package in self.flagged_boot else "—"))''',
'''                                  values=("☑" if package in self.marked_packages else "☐",
                                          package, "Заявлено" if package in self.flagged_overlays else "—",
                                          "Заявлено" if package in self.flagged_boot else "—"))''',"checked values")
replace('        self.app_table.selection_set([p for p in selected if p in visible])',
'''        self.app_table.selection_set([p for p in selected if p in visible])
        self.mark_counter.set("Отмечено: " + str(len(self.marked_packages)))''',"counter")
replace('    def selected_packages(self):',
'''    def toggle_package_mark(self, event):
        """Only clicks on check column toggle persistent marks."""
        if self.app_table.identify_region(event.x,event.y)!="cell":
            return
        if self.app_table.identify_column(event.x)!="#1":
            return
        package=self.app_table.identify_row(event.y)
        if not package:
            return "break"
        if package in self.marked_packages:
            self.marked_packages.remove(package)
            symbol="☐"
        else:
            self.marked_packages.add(package)
            symbol="☑"
        self.app_table.set(package,"checked",symbol)
        self.mark_counter.set("Отмечено: " + str(len(self.marked_packages)))
        return "break"

    def mark_visible_packages(self):
        """Visible == current search filter; never select hidden packages."""
        self.marked_packages.update(self.app_table.get_children())
        for item in self.app_table.get_children():
            self.app_table.set(item,"checked","☑")
        self.mark_counter.set("Отмечено: "+str(len(self.marked_packages)))

    def unmark_packages(self):
        self.marked_packages.clear()
        for item in self.app_table.get_children():
            self.app_table.set(item,"checked","☐")
        self.app_table.selection_remove(*self.app_table.selection())
        self.mark_counter.set("Отмечено: 0")

    def selected_packages(self):''',"mark and unmark methods")
replace('''        values = [self.app_table.set(item, "package") for item in self.app_table.selection()]''',
'''        # Explicit checkmarks take priority. Ctrl/Shift selection is still supported.
        values = sorted(self.marked_packages) if self.marked_packages else [
            self.app_table.set(item, "package") for item in self.app_table.selection()]''',"marked precedence")
replace('''        def do():
            successes, failures = [], []
            for pkg in pkgs:
                try:
                    successes.append((pkg, self.service.maintenance(pkg, mode)))
                except Exception as exc:
                    failures.append((pkg, str(exc)))
            return successes, failures
        def done(res):
            successes, failures = res''',
'''        def do():
            successes, failures, skipped = [], [], []
            for index, pkg in enumerate(pkgs):
                try:
                    successes.append((pkg, self.service.maintenance(pkg, mode)))
                except Exception as exc:
                    error=str(exc)
                    failures.append((pkg,error))
                    # All selected packages are typically subject to the same shell privilege.
                    # A permission denial must NOT be retried on each remaining app.
                    if mode=="data" and "CLEAR_APP_USER_DATA" in error:
                        skipped=pkgs[index+1:]
                        break
                    # Cache-only timeout is ambiguous: do not continue the batch blindly.
                    if mode=="cache" and ("ТАЙМ-АУТ" in error or "Превышено ожидание" in error):
                        skipped=pkgs[index+1:]
                        break
            return successes, failures, skipped
        def done(res):
            successes, failures, skipped = res''',"stop batch on common deny")
replace('''            for pkg, error in failures:
                self.log(f"ОШИБКА {pkg}: {error}")''',
'''            for pkg, error in failures:
                self.log(f"ОШИБКА {pkg}: {error}")
            for pkg in skipped:
                self.log(f"ПРОПУЩЕНО, команда не запускалась: {pkg}")''',"log skipped")
replace('''f"Действие: {desc}\\nУспешно: {len(successes)}\\nС ошибкой: {len(failures)}\\n\\n"''',
'''f"Действие: {desc}\\nУспешно: {len(successes)}\\nС ошибкой: {len(failures)}\\nПропущено без запуска: {len(skipped)}\\n\\n"''',"separate skipped count")
replace('''            if mode == "data" and len(pkgs) == 1 and failures and any(
                    "CLEAR_APP_USER_DATA" in error for _, error in failures):''',
'''            if mode == "data" and len(pkgs) == 1 and failures and any(
                    "CLEAR_APP_USER_DATA" in error for _, error in failures):''',"preserve original failover") if False else None
replace('''            if mode in ("uninstall", "disable", "enable"):
                self.load_packages()''',
'''            if mode == "data" and len(pkgs)>1 and any(
                    "CLEAR_APP_USER_DATA" in error for _,error in failures):
                self.offer_manual_app_settings([pkg for pkg,_ in failures] + skipped)
            if mode in ("uninstall", "disable", "enable"):
                self.load_packages()''',"manual queue")
replace('    def bulk(self, mode):',
'''    def offer_manual_app_settings(self, packages):
        """Manual Android Settings queue; no UI automation, no silent data clear."""
        packages=list(dict.fromkeys(p for p in packages if p in self.current_packages))
        if not packages:
            return
        win=tk.Toplevel(self)
        win.title("Ручная очистка данных — доступ ограничен Android")
        win.geometry("530x380")
        win.minsize(420,290)
        win.configure(bg=BG)
        ttk.Label(win,text="ADB не получил право на очистку данных. Откройте настройки",style="Header.TLabel").pack(anchor="w",padx=15,pady=(12,6))
        ttk.Label(win,text="Выберите программу, затем очистите её данные на телефоне вручную.",style="Muted.TLabel").pack(anchor="w",padx=15)
        listbox=tk.Listbox(win,selectmode="browse",height=10,exportselection=False,
                           bg=CARD,fg=TEXT,selectbackground="#265f58",font=("Segoe UI",10))
        listbox.pack(fill="both",expand=True,padx=15,pady=10)
        for pkg in packages:
            listbox.insert("end",pkg)
        listbox.selection_set(0)
        def open_one():
            if not listbox.curselection():return
            package=packages[listbox.curselection()[0]]
            self.busy_call("Открыть сведения о "+package,
                           lambda: self.service.open_package_settings(package),
                           lambda text: self.log(text))
        buttons=ttk.Frame(win)
        buttons.pack(fill="x",padx=15,pady=(0,12))
        ttk.Button(buttons,text="Открыть выбранное на телефоне",
                   command=open_one).pack(side="left")
        ttk.Button(buttons,text="Закрыть",command=win.destroy).pack(side="right")
        win.transient(self)
        win.lift()

    def bulk(self, mode):''',"manual settings queue")
p.write_text(app,encoding="utf-8")
print("Windows 0.3.3 patch applied: SamFw-like checkbox multi-select and safe batch behavior")
