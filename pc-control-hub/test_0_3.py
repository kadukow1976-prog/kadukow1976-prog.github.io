"""Regressions for 0.3: clear Russian diagnostics and safe batch controls."""
import sys
import unittest
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASE / "src"))
from ach_core import format_device_report, describe_package_dump

class RussianReportsTests(unittest.TestCase):
    def test_device_summary_is_compact_russian(self):
        report = {
            "Производитель": "OnePlus", "Модель": "CPH2747",
            "Android": "16", "API": "36",
            "Заряд и питание": "  level: 38\n  scale: 100\n  temperature: 320\n  voltage: 4025\n USB powered: true",
            "Оперативная память": "MemTotal:       11324620 kB\nMemAvailable:   4200000 kB\n",
            "Накопитель": "Filesystem Size Used Available Mounted on\n/dev/fake 210G 115G 95G /data"
        }
        output = format_device_report(report)
        for fragment in ("OnePlus", "CPH2747", "Заряд: 38%", "Температура: 32.0 °C",
                         "Напряжение: 4.03 В", "ОЗУ доступно:", "НАКОПИТЕЛЬ /data"):
            self.assertIn(fragment, output, fragment)
        self.assertLess(len(output), 1300)

    def test_package_permissions_are_explained_without_false_accusation(self):
        raw = ("versionName=1.8.3 targetSdk=36\n"
               "requested permissions:\n"
               "android.permission.SYSTEM_ALERT_WINDOW\n"
               "android.permission.RECEIVE_BOOT_COMPLETED\n")
        output = describe_package_dump("com.example.sample", raw)
        self.assertIn("Показ окон поверх других приложений", output)
        self.assertIn("события загрузки Android", output)
        self.assertIn("не доказывает", output)

    def test_unknown_report_fields_are_safe(self):
        self.assertIn("СВОДКА", format_device_report({}))
        self.assertIn("ПРИЛОЖЕНИЕ", describe_package_dump("com.example.test", ""))

    def test_no_unsafe_bulk_confirmation_bypass(self):
        source = (BASE / "src" / "app.py").read_text(encoding="utf-8")
        self.assertIn('mode in ("data", "uninstall", "disable")', source)
        self.assertIn("default=messagebox.NO", source)
        self.assertIn('self.bulk("uninstall")', source)
        self.assertIn('self.bulk("data")', source)
        self.assertNotIn('simpledialog.askstring("Подтверждение владельца"', source)
        self.assertIn("ПРИЧИНЫ ОШИБОК", source)

if __name__ == "__main__":
    unittest.main()
