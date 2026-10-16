package ru.kadukov.androidcontrolhub;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_CONTACT_PERMISSION = 201;
    private static final int REQ_REPORT_FILE = 202;
    private static final int REQ_CONTACTS_FILE = 203;
    private static final int REQ_PICK_FILES = 204;
    private static final String SITE = "https://kadukow1976-prog.github.io/android-control-hub/";
    private TextView diagnosticText;
    private TextView permissionText;
    private LinearLayout container;
    private String reportJson = "";
    private final ArrayList<Uri> chosenFiles = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 15, 27));
        getWindow().setNavigationBarColor(Color.rgb(9, 15, 27));
        render();
        if (getIntent() != null && getIntent().getData() != null) {
            Uri uri = getIntent().getData();
            if ("androidhub".equals(uri.getScheme()) && "diagnostics".equals(uri.getLastPathSegment())) {
                refreshReport();
            }
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setPadding(0, dp(5), 0, dp(5));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private void addBlock(String title, String description, String... hints) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(19, 31, 47));
        bg.setCornerRadius(dp(17));
        bg.setStroke(dp(1), Color.rgb(43, 63, 78));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        container.addView(card, lp);
        card.addView(text(title, 17, Color.WHITE, true));
        card.addView(text(description, 12, Color.rgb(160, 185, 201), false));
        for (String h : hints) card.addView(text(h, 11, Color.rgb(123, 162, 173), false));
        currentCard = card;
    }

    private LinearLayout currentCard;
    private void action(String label, Runnable click) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(Color.rgb(6, 52, 42));
        b.setTextSize(13);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.rgb(76, 229, 179));
        d.setCornerRadius(dp(11));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        currentCard.addView(b, lp);
        b.setOnClickListener(v -> click.run());
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9, 15, 27));
        container = new LinearLayout(this);
        container.setPadding(dp(16), dp(24), dp(16), dp(35));
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container);
        setContentView(scroll);

        TextView title = text("Android Control Hub", 26, Color.rgb(77, 238, 185), true);
        container.addView(title);
        TextView subtitle = text("Сервисный клиент • Android • бета 0.1\nРаботает на этом устройстве. Данные никуда автоматически не отправляются.", 12, Color.rgb(176, 199, 212), false);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1,-2);
        subtitleLp.bottomMargin = dp(20);
        container.addView(subtitle, subtitleLp);

        addBlock("01  Диагностика устройства", "Модель, Android, батарея, температура, память и сеть. Отчёт формируется локально.");
        diagnosticText = text("Нажмите «Сканировать» для получения реальных параметров.", 12, Color.rgb(187, 216, 223), false);
        currentCard.addView(diagnosticText);
        action("Сканировать телефон", this::refreshReport);
        action("Сохранить отчёт JSON", this::saveReport);
        action("Поделиться отчётом", this::shareReport);

        addBlock("02  Контакты", "Экспорт адресной книги в .vcf. Разрешение READ_CONTACTS запрашивается только при экспорте.", "Передача контактов без вашего выбора и сохранения не выполняется.");
        action("Разрешить и экспортировать VCF", this::confirmContacts);

        addBlock("03  Работа с файлами", "Выбрать несколько файлов средствами Android и поделиться через системное меню.", "Доступ ко всему накопителю не требуется.");
        action("Выбрать файлы", this::chooseFiles);
        action("Поделиться выбранными файлами", this::shareChosenFiles);

        addBlock("04  Установленные приложения", "Просмотр приложений с иконкой запуска и переход в их системные настройки.", "Android может показывать лишь часть приложений. Удаление выполняет сам пользователь.");
        action("Показать приложения", this::showLauncherApps);
        action("Открыть настройки приложений", () -> openSettings(Settings.ACTION_APPLICATION_SETTINGS));

        addBlock("05  Использование приложений", "По вашему желанию: статистика времени работы приложений за 24 часа.", "Специальный доступ Usage Access пользователь включает и выключает в системных настройках.");
        permissionText = text("Доступ: проверяется при запросе", 12, Color.rgb(176, 199, 212), false);
        currentCard.addView(permissionText);
        action("Управление разрешением статистики", () -> openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        action("Посмотреть статистику за 24 часа", this::showUsage);

        addBlock("06  Системные настройки", "Только штатные меню Android — приложение не меняет системные настройки скрытно.");
        action("Открыть настройки Android", () -> openSettings(Settings.ACTION_SETTINGS));
        action("Настройки сети и Wi-Fi", () -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        action("Настройки безопасности", () -> openSettings(Settings.ACTION_SECURITY_SETTINGS));
        action("Информация о телефоне", () -> openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS));

        addBlock("07  Сайт Android Control Hub", "Справочные ссылки, диагностика браузера и инструкции.");
        action("Открыть сайт", () -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(SITE));
            launch(intent);
        });

        addBlock("Границы полномочий", "Установка APK не даёт root, полного доступа к чужим приложениям, скрытого управления, чтения чужих паролей или доступа к защищённым системным разделам.", "Режим Device Owner — отдельный корпоративный сценарий, не обычное разрешение.");
    }

    private void message(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            message("Действие не поддерживается этой версией Android: " + e.getClass().getSimpleName());
        }
    }

    private void openSettings(String action) {
        launch(new Intent(action));
    }

    private JSONObject deviceReport() throws Exception {
        JSONObject report = new JSONObject();
        report.put("generatedAt", DateFormat.getDateTimeInstance().format(new Date()));
        report.put("manufacturer", Build.MANUFACTURER);
        report.put("model", Build.MODEL);
        report.put("device", Build.DEVICE);
        report.put("androidVersion", Build.VERSION.RELEASE);
        report.put("apiLevel", Build.VERSION.SDK_INT);

        Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject b = new JSONObject();
        if (battery != null) {
            int lvl = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            b.put("chargePercent", lvl >= 0 && scale > 0 ? (100 * lvl / scale) : JSONObject.NULL);
            int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            b.put("temperatureCelsius", temp >= 0 ? temp / 10.0 : JSONObject.NULL);
            b.put("voltageMillivolts", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
            b.put("healthCode", battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1));
            b.put("chargingStatusCode", battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
        }
        report.put("battery", b);

        StatFs fs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        JSONObject storage = new JSONObject();
        storage.put("availableInternalBytes", fs.getAvailableBytes());
        storage.put("totalInternalBytes", fs.getTotalBytes());
        report.put("internalDataPartition", storage);
        ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        JSONObject mem = new JSONObject();
        mem.put("availableRamBytes", info.availMem);
        mem.put("totalRamBytes", info.totalMem);
        mem.put("lowMemory", info.lowMemory);
        report.put("ram", mem);

        String net = "Недоступно";
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps == null) net = "Нет активной сети";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) net = "Wi-Fi";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) net = "Мобильная сеть";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) net = "Ethernet";
                else net = "Другая сеть";
            }
        } catch (Exception ignored) { }
        report.put("networkType", net);
        return report;
    }

    private static String size(long n) {
        return String.format(Locale.US, "%.2f ГБ", n / (1024.0 * 1024 * 1024));
    }

    private void refreshReport() {
        try {
            JSONObject data = deviceReport();
            reportJson = data.toString(2);
            JSONObject batt = data.getJSONObject("battery");
            JSONObject disk = data.getJSONObject("internalDataPartition");
            JSONObject mem = data.getJSONObject("ram");
            String s = "Производитель: " + data.optString("manufacturer") + "\n"
                     + "Модель: " + data.optString("model") + "\n"
                     + "Android: " + data.optString("androidVersion") + " (API " + data.optInt("apiLevel") + ")\n"
                     + "Заряд: " + batt.optString("chargePercent", "Н/Д") + "%\n"
                     + "Температура: " + batt.optString("temperatureCelsius","Н/Д") + " °C\n"
                     + "Память раздела /data: " + size(disk.optLong("availableInternalBytes")) + " доступно из " + size(disk.optLong("totalInternalBytes")) + "\n"
                     + "ОЗУ: " + size(mem.optLong("availableRamBytes")) + " доступно из " + size(mem.optLong("totalRamBytes")) + "\n"
                     + "Подключение: " + data.optString("networkType") + "\n"
                     + "Обновлено: " + data.optString("generatedAt");
            diagnosticText.setText(s);
            message("Параметры считаны локально");
        } catch (Exception e) {
            diagnosticText.setText("Не удалось получить часть данных: " + e.getMessage());
        }
    }

    private void requestDocument(String mime, String filename, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            message("Выбор места сохранения недоступен");
        }
    }

    private void saveReport() {
        refreshReport();
        if (reportJson.length() > 0) requestDocument("application/json","android-diagnostics.json", REQ_REPORT_FILE);
    }

    private void shareReport() {
        refreshReport();
        if (reportJson.length() == 0) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, reportJson);
        launch(Intent.createChooser(i,"Передать диагностический отчёт"));
    }

    private void confirmContacts() {
        new AlertDialog.Builder(this)
          .setTitle("Экспорт контактов")
          .setMessage("При подтверждении приложение запросит доступ к контактам. После выбора места сохранения создаст локальный VCF. Данные никуда не загружаются автоматически.")
          .setNegativeButton("Отмена",null)
          .setPositiveButton("Продолжить",(dialog,which)->startContactsExport())
          .show();
    }

    private void startContactsExport() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},REQ_CONTACT_PERMISSION);
        } else {
            requestDocument("text/x-vcard","android-contacts.vcf",REQ_CONTACTS_FILE);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if (requestCode==REQ_CONTACT_PERMISSION) {
            if (results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) {
                requestDocument("text/x-vcard","android-contacts.vcf",REQ_CONTACTS_FILE);
            } else message("Разрешение не выдано. Экспорт отменён.");
        }
    }

    private String escapeVcf(String value) {
        if (value==null) return "";
        return value.replace("\\","\\\\").replace("\r\n","\n").replace("\r","\n").replace("\n","\\n").replace(";","\\;").replace(",","\\,");
    }

    private String createVcf() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Нет разрешения READ_CONTACTS");
        class Card {
            String name = "";
            ArrayList<String> phones = new ArrayList<>();
            ArrayList<String> emails = new ArrayList<>();
        }
        LinkedHashMap<Long,Card> cards = new LinkedHashMap<>();
        ContentResolver resolver=getContentResolver();
        String[] columns={ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER};
        try(Cursor cursor=resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,columns,null,null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC")) {
            if (cursor!=null) while(cursor.moveToNext()) {
                long id=cursor.getLong(0);
                Card card=cards.get(id);
                if(card==null){card=new Card();cards.put(id,card);}
                String name=cursor.getString(1),phone=cursor.getString(2);
                if (name!=null)card.name=name;
                if (phone!=null && !card.phones.contains(phone))card.phones.add(phone);
            }
        }
        String[] emailCols={ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME};
        try(Cursor cursor=resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,emailCols,null,null,null)) {
            if(cursor!=null)while(cursor.moveToNext()){
                long id=cursor.getLong(0);
                Card card=cards.get(id);
                if(card==null){card=new Card();cards.put(id,card);}
                String email=cursor.getString(1),name=cursor.getString(2);
                if(card.name.isEmpty() && name!=null)card.name=name;
                if(email!=null && !card.emails.contains(email))card.emails.add(email);
            }
        }
        StringBuilder output=new StringBuilder();
        for (Card c:cards.values()) {
            output.append("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:")
                  .append(escapeVcf(c.name.isEmpty()?"Контакт":c.name)).append("\r\n");
            for(String ph:c.phones)output.append("TEL:").append(escapeVcf(ph)).append("\r\n");
            for(String em:c.emails)output.append("EMAIL:").append(escapeVcf(em)).append("\r\n");
            output.append("END:VCARD\r\n");
        }
        if(cards.isEmpty())throw new IllegalStateException("В адресной книге нет контактов с телефоном или почтой");
        return output.toString();
    }

    private void chooseFiles() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(i,REQ_PICK_FILES); }
        catch(Exception e){message("Выбор файлов недоступен");}
    }

    private void shareChosenFiles() {
        if(chosenFiles.isEmpty()){message("Сначала выберите файлы");return;}
        Intent i;
        if(chosenFiles.size()==1) {
            i=new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, chosenFiles.get(0));
        } else {
            i=new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM,new ArrayList<>(chosenFiles));
        }
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip=null;
        for(Uri uri:chosenFiles) {
            if(clip==null)clip=ClipData.newUri(getContentResolver(),"Выбранные файлы",uri);
            else clip.addItem(new ClipData.Item(uri));
        }
        if(clip!=null)i.setClipData(clip);
        launch(Intent.createChooser(i,"Передача файлов"));
    }

    private void showLauncherApps() {
        Intent query=new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm=getPackageManager();
        List<ResolveInfo> items=pm.queryIntentActivities(query,0);
        items.sort(Comparator.comparing(v -> String.valueOf(v.loadLabel(pm)).toLowerCase(Locale.ROOT)));
        LinkedHashMap<String,String> names=new LinkedHashMap<>();
        for(ResolveInfo info:items) {
            if(info.activityInfo!=null) names.put(info.activityInfo.packageName,String.valueOf(info.loadLabel(pm)));
        }
        if(names.isEmpty()){message("Android не разрешил получить список приложений");return;}
        ArrayList<String> keys=new ArrayList<>(names.keySet());
        String[] labels=new String[keys.size()];
        for(int i=0;i<keys.size();i++) labels[i]=names.get(keys.get(i))+" ("+keys.get(i)+")";
        new AlertDialog.Builder(this).setTitle("Приложения с экраном запуска ("+labels.length+")")
          .setItems(labels,(d,n)->{
              Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+keys.get(n)));
              launch(i);
          }).setNegativeButton("Закрыть",null).show();
    }

    private boolean hasUsageAccess() {
        AppOpsManager appops=(AppOpsManager)getSystemService(APP_OPS_SERVICE);
        if(appops==null)return false;
        return appops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;
    }

    private void showUsage() {
        if(!hasUsageAccess()){
            permissionText.setText("Доступ: не выдан");
            new AlertDialog.Builder(this).setTitle("Доступ к статистике")
                .setMessage("Разрешите Android Control Hub доступ к статистике использования в системных настройках. Это отдельное разрешение. Его можно отменить в любой момент.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Открыть настройки",(d,w)->openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)).show();
            return;
        }
        permissionText.setText("Доступ: разрешён");
        UsageStatsManager mgr=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);
        if(mgr==null){message("Сервис статистики недоступен");return;}
        long end=System.currentTimeMillis(), start=end-24*60*60*1000L;
        List<UsageStats> stats=mgr.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,start,end);
        if(stats==null || stats.isEmpty()){message("Данных статистики пока нет");return;}
        stats.sort((a,b)->Long.compare(b.getTotalTimeInForeground(),a.getTotalTimeInForeground()));
        StringBuilder sb=new StringBuilder("По данным Android, за последние ~24 часа:\n\n");
        int count=0;
        for(UsageStats s:stats){
            long minutes=s.getTotalTimeInForeground()/60000;
            if(minutes<=0)continue;
            sb.append("• ").append(s.getPackageName()).append(": ").append(minutes).append(" мин\n");
            if(++count>=18)break;
        }
        new AlertDialog.Builder(this).setTitle("Использование приложений").setMessage(sb.toString()).setPositiveButton("Закрыть",null).show();
    }

    private void writeUri(Uri uri,String text) {
        try(OutputStream out=getContentResolver().openOutputStream(uri)) {
            if(out==null)throw new Exception("Не удалось открыть файл");
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            message("Файл сохранён");
        } catch(Exception e) {
            new AlertDialog.Builder(this).setTitle("Ошибка сохранения").setMessage(e.getMessage()).setPositiveButton("OK",null).show();
        }
    }

    @Override protected void onActivityResult(int req,int code,Intent data) {
        super.onActivityResult(req,code,data);
        if(code!=RESULT_OK || data==null)return;
        Uri uri=data.getData();
        if(req==REQ_REPORT_FILE && uri!=null)writeUri(uri,reportJson);
        if(req==REQ_CONTACTS_FILE && uri!=null) {
            try{writeUri(uri,createVcf());}
            catch(Exception e){message("Экспорт не выполнен: "+e.getMessage());}
        }
        if(req==REQ_PICK_FILES) {
            chosenFiles.clear();
            if(data.getClipData()!=null){
                ClipData clip=data.getClipData();
                for(int i=0;i<clip.getItemCount();i++)chosenFiles.add(clip.getItemAt(i).getUri());
            }else if(uri!=null)chosenFiles.add(uri);
            message("Выбрано файлов: "+chosenFiles.size());
        }
    }
}
