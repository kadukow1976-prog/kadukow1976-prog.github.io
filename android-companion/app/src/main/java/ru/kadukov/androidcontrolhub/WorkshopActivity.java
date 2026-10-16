package ru.kadukov.androidcontrolhub;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class WorkshopActivity extends Activity {
    private final int BG=Color.rgb(9,15,27);
    private final int PANEL=Color.rgb(19,31,47);
    private final int LINE=Color.rgb(45,64,82);
    private final int GREEN=Color.rgb(74,230,177);
    private final int WHITE=Color.rgb(235,247,251);
    private final int MUTE=Color.rgb(152,175,194);
    private FrameLayout contentFrame;
    private LinearLayout navBar;
    private final Button[] tabs=new Button[4];
    private int activeTab=0;
    private final ArrayList<AppEntry> appEntries=new ArrayList<>();
    private final ArrayList<AppEntry> filtered=new ArrayList<>();
    private LinearLayout appRows;
    private TextView appCount;
    private Button moreButton;
    private int appLimit=35;
    private String appSearch="";
    private int appSort=0;
    private SensorManager sensorManager;
    private SensorEventListener currentSensorListener;
    private final Handler handler=new Handler(Looper.getMainLooper());

    private static class AppEntry {
        String title,pkg,version;
        boolean system;
        long firstInstall;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if(state!=null)activeTab=state.getInt("tab",0);
        buildShell();
        showTab(activeTab);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab",activeTab);
        super.onSaveInstanceState(out);
    }
    @Override protected void onPause(){
        if(sensorManager!=null && currentSensorListener!=null){
            sensorManager.unregisterListener(currentSensorListener);
            currentSensorListener=null;
        }
        super.onPause();
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int fill,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);
        return d;
    }
    private LinearLayout vertical(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private TextView text(String s,int sp,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        if(bold)t.setTypeface(null,1);
        t.setLineSpacing(dp(2),1);
        return t;
    }
    private void pad(View v,int h,int vpad){v.setPadding(dp(h),dp(vpad),dp(h),dp(vpad));}
    private LinearLayout.LayoutParams margin(int bottom){
        LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(-1,-2);
        l.bottomMargin=dp(bottom);
        return l;
    }
    private Button button(String label,boolean primary,Runnable act){
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(primary?Color.rgb(7,44,37):WHITE);
        b.setBackground(shape(primary?GREEN:Color.rgb(30,48,66),primary?0:LINE,11));
        b.setMinHeight(dp(46));
        b.setOnClickListener(v->act.run());
        return b;
    }
    private void addBtn(LinearLayout box,String label,boolean primary,Runnable act){
        LinearLayout.LayoutParams lp=margin(8);
        lp.height=dp(48);
        box.addView(button(label,primary,act),lp);
    }
    private void addText(LinearLayout box,String s,int sp,int color,boolean bold,int bottom){
        box.addView(text(s,sp,color,bold),margin(bottom));
    }
    private LinearLayout card(LinearLayout parent,String title,String summary){
        LinearLayout c=vertical();pad(c,17,16);c.setBackground(shape(PANEL,LINE,16));
        parent.addView(c,margin(13));
        addText(c,title,16,WHITE,true,5);
        if(summary!=null && !summary.isEmpty())addText(c,summary,12,MUTE,false,12);
        return c;
    }
    private void buildShell(){
        LinearLayout shell=vertical();shell.setBackgroundColor(BG);
        setContentView(shell);
        LinearLayout top=vertical();top.setBackgroundColor(Color.rgb(12,22,35));pad(top,18,11);
        addText(top,"ANDROID  CONTROL  HUB",11,GREEN,true,0);
        addText(top,"Сервисная панель  ·  0.8.1 Beta",20,WHITE,true,0);
        shell.addView(top,new LinearLayout.LayoutParams(-1,dp(83)));
        contentFrame=new FrameLayout(this);
        shell.addView(contentFrame,new LinearLayout.LayoutParams(-1,0,1));
        navBar=new LinearLayout(this);
        navBar.setBackgroundColor(Color.rgb(13,26,41));
        navBar.setPadding(dp(5),dp(6),dp(5),dp(6));
        String[] labels={"Обзор","Приложения","Тесты","Данные"};
        for(int i=0;i<4;i++){
            final int idx=i;
            Button b=button(labels[i],false,()->showTab(idx));
            b.setMinHeight(dp(48));b.setTextSize(11);
            tabs[i]=b;
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);
            lp.setMargins(dp(2),0,dp(2),0);
            navBar.addView(b,lp);
        }
        shell.addView(navBar,new LinearLayout.LayoutParams(-1,dp(63)));
    }
    private LinearLayout page(String heading,String sub){
        contentFrame.removeAllViews();
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page=vertical();pad(page,16,18);
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        contentFrame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        addText(page,heading,23,WHITE,true,4);
        addText(page,sub,12,MUTE,false,17);
        return page;
    }
    private void showTab(int tab){
        activeTab=tab;
        stopSensor();
        for(int i=0;i<4;i++){
            boolean a=i==tab;
            tabs[i].setTextColor(a?Color.rgb(7,44,37):MUTE);
            tabs[i].setBackground(shape(a?GREEN:Color.rgb(20,36,52),a?0:LINE,10));
        }
        if(tab==0)showOverview();
        else if(tab==1)showApps();
        else if(tab==2)showTests();
        else showData();
    }
    private String size(long bytes){
        if(bytes<0)return "нет данных";
        return String.format(Locale.getDefault(),"%.2f ГБ",bytes/(1024.0*1024*1024));
    }
    private void addValue(LinearLayout box,String key,String val){
        LinearLayout line=new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView k=text(key,12,MUTE,false),v=text(val,12,WHITE,true);
        k.setPadding(0,dp(5),dp(6),dp(5));
        v.setGravity(Gravity.END);v.setPadding(0,dp(5),0,dp(5));
        line.addView(k,new LinearLayout.LayoutParams(0,-2,1));
        line.addView(v,new LinearLayout.LayoutParams(0,-2,1.6f));
        box.addView(line);
    }
    private void showOverview(){
        LinearLayout page=page("Обзор устройства","Только настоящие параметры Android. Сканирование выполняется локально.");
        LinearLayout device=card(page,"📱 "+Build.MANUFACTURER+" "+Build.MODEL,"Android "+Build.VERSION.RELEASE+" · API "+Build.VERSION.SDK_INT);
        addValue(device,"Устройство",Build.DEVICE);
        addValue(device,"Процессор",Build.VERSION.SDK_INT>=31 && Build.SOC_MODEL!=null?Build.SOC_MODEL:"См. данные устройства");
        addValue(device,"Архитектура",Build.SUPPORTED_ABIS.length>0?Build.SUPPORTED_ABIS[0]:"Нет данных");
        addValue(device,"Логические ядра",String.valueOf(Runtime.getRuntime().availableProcessors()));
        android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
        addValue(device,"Экран (окно)",metrics.widthPixels+" × "+metrics.heightPixels+" px");
        addValue(device,"Плотность",String.format(Locale.getDefault(),"%.1f dpi",metrics.densityDpi*1.0));

        LinearLayout batt=card(page,"🔋 Аккумулятор и память","Температура — датчик батареи, не температура CPU.");
        Intent battery=registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery!=null){
            int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
            int scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            int temp=battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1);
            addValue(batt,"Заряд",level>=0 && scale>0?Math.round(level*100f/scale)+"%":"Недоступно");
            addValue(batt,"Температура",temp>=0?String.format(Locale.getDefault(),"%.1f °C",temp/10f):"Недоступно");
            addValue(batt,"Напряжение",battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1)+" мВ");
            addValue(batt,"Статус",batteryStatus(battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1)));
        }
        ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
        if(am!=null){
            ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            addValue(batt,"ОЗУ доступно",size(mi.availMem)+" / "+size(mi.totalMem));
        }
        try{
            StatFs fs=new StatFs(Environment.getDataDirectory().getAbsolutePath());
            addValue(batt,"Раздел /data",size(fs.getAvailableBytes())+" / "+size(fs.getTotalBytes()));
        }catch(Exception e){addValue(batt,"Раздел /data","Недоступно");}
        addValue(batt,"Подключение",networkType());
        SensorManager sm=(SensorManager)getSystemService(SENSOR_SERVICE);
        addValue(batt,"Датчиков",sm==null?"Недоступно":String.valueOf(sm.getSensorList(Sensor.TYPE_ALL).size()));
        addBtn(batt,"Обновить параметры",true,this::showOverview);

        LinearLayout quick=card(page,"Быстрые действия","Выбери раздел сервисного обслуживания.");
        addBtn(quick,"PRO: Shizuku и ADB · обслуживание приложений",true,()->startActivity(new Intent(this,ProActivity.class)));
        addBtn(quick,"Компьютер, второй телефон и резервные копии",false,()->startActivity(new Intent(this,TransferHubActivity.class)));
        addBtn(quick,"Автоскан: реклама, игры, наложения и автозапуск",false,()->startActivity(new Intent(this,AppAuditActivity.class)));
        addBtn(quick,"Центр разрешений Android",false,()->startActivity(new Intent(this,PermissionsActivity.class)));
        addBtn(quick,"Журнал активности: экран и фоновые службы",false,()->startActivity(new Intent(this,ActivityMonitorActivity.class)));
        addBtn(quick,"Установленные приложения и разрешения",false,()->showTab(1));
        addBtn(quick,"Тесты экрана, вибрации, звука, датчиков",false,()->showTab(2));
        addBtn(quick,"Контакты, файлы, JSON-отчёт",false,()->showTab(3));
        addText(page,"Ограничения Android: некоторые защищённые параметры недоступны без привилегий системы. Система не сканируется на вирусы и не очищается скрытно.",11,MUTE,false,8);
    }
    private String batteryStatus(int code){
        switch(code){
            case BatteryManager.BATTERY_STATUS_CHARGING:return "Заряжается";
            case BatteryManager.BATTERY_STATUS_FULL:return "Полный заряд";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:return "Разряжается";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:return "Не заряжается";
            default:return "Неизвестно";
        }
    }
    private String networkType(){
        try{
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities n=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
            if(n==null)return "Нет подключения";
            if(n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Wi-Fi";
            if(n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "Мобильная сеть";
            if(n.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet";
            return "Другое";
        }catch(Exception e){return "Недоступно";}
    }

    private void scanApps(){
        appEntries.clear();
        PackageManager pm=getPackageManager();
        Intent query=new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        LinkedHashMap<String,AppEntry> found=new LinkedHashMap<>();
        for(ResolveInfo res:pm.queryIntentActivities(query,0)){
            if(res.activityInfo==null)continue;
            String pkg=res.activityInfo.packageName;
            if(found.containsKey(pkg))continue;
            AppEntry en=new AppEntry();
            en.pkg=pkg;en.title=String.valueOf(res.loadLabel(pm));
            try{
                PackageInfo info=pm.getPackageInfo(pkg,0);
                en.version=info.versionName==null?"нет версии":info.versionName;
                en.firstInstall=info.firstInstallTime;
                en.system=info.applicationInfo!=null && (info.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0;
            }catch(Exception ex){en.version="Недоступно";en.system=false;}
            found.put(pkg,en);
        }
        appEntries.addAll(found.values());
    }
    private void showApps(){
        LinearLayout page=page("Приложения","Поиск, сортировка, разрешения и системное управление. Без скрытого удаления.");
        scanApps();
        LinearLayout auditCard=card(page,"Автоматический аудит приложений",
            "Все пакеты, возможные наложения, автозапуск, планировщик, игры; выбор программ для системных действий.");
        addBtn(auditCard,"Автосканирование и управление выбранными",true,()->startActivity(new Intent(this,AppAuditActivity.class)));
        LinearLayout permissionAccess=card(page,"Центр разрешений","Проверка доступов, SIM, микрофона, камеры, GPS, Bluetooth и уведомлений.");
        addBtn(permissionAccess,"Открыть центр разрешений",true,()->startActivity(new Intent(this,PermissionsActivity.class)));
        LinearLayout activityAccess=card(page,"История использования Android","События открытия приложений и foreground-служб. Доступ выдаётся в системных настройках.");
        addBtn(activityAccess,"Показать журнал активности",true,()->startActivity(new Intent(this,ActivityMonitorActivity.class)));
        LinearLayout controls=card(page,"Приложения с иконкой запуска",appEntries.size()+" обнаружено. Полный список скрытых системных пакетов Android не предоставляет.");
        EditText search=new EditText(this);
        search.setSingleLine(true);search.setHint("Поиск по названию или package");
        search.setTextColor(WHITE);search.setHintTextColor(MUTE);
        search.setTextSize(13);search.setPadding(dp(13),dp(10),dp(13),dp(10));
        search.setBackground(shape(Color.rgb(11,24,37),LINE,10));
        controls.addView(search,margin(10));
        Spinner spinner=new Spinner(this);
        String[] sorts={"Название А–Я","Недавно установлены","Системные в начале","Пользовательские в начале"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sorts);
        spinner.setAdapter(adapter);spinner.setSelection(appSort);
        controls.addView(spinner,margin(8));
        appCount=text("",12,GREEN,true);
        controls.addView(appCount,margin(4));
        appRows=vertical();
        page.addView(appRows,margin(3));
        moreButton=button("Показать ещё",false,()->{appLimit+=35;renderAppRows();});
        page.addView(moreButton,margin(15));
        search.setText(appSearch);
        search.setSelection(search.getText().length());
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){
                appSearch=s.toString();appLimit=35;renderAppRows();
            }
            public void afterTextChanged(Editable e){}
        });
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> a){}
            public void onItemSelected(AdapterView<?> a,View v,int pos,long id){
                appSort=pos;appLimit=35;renderAppRows();
            }
        });
        renderAppRows();
    }
    private void renderAppRows(){
        if(appRows==null)return;
        appRows.removeAllViews();
        filtered.clear();
        String q=appSearch.trim().toLowerCase(Locale.ROOT);
        for(AppEntry e:appEntries){
            if(e.title.toLowerCase(Locale.ROOT).contains(q)||e.pkg.toLowerCase(Locale.ROOT).contains(q))filtered.add(e);
        }
        if(appSort==0)filtered.sort(Comparator.comparing(x->x.title.toLowerCase(Locale.ROOT)));
        else if(appSort==1)filtered.sort((a,b)->Long.compare(b.firstInstall,a.firstInstall));
        else if(appSort==2)filtered.sort((a,b)->Boolean.compare(b.system,a.system));
        else filtered.sort((a,b)->Boolean.compare(a.system,b.system));
        appCount.setText("Найдено: "+filtered.size()+" · показано: "+Math.min(appLimit,filtered.size()));
        int n=Math.min(appLimit,filtered.size());
        PackageManager pm=getPackageManager();
        for(int i=0;i<n;i++){
            AppEntry e=filtered.get(i);
            LinearLayout row=new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12),dp(10),dp(12),dp(10));
            row.setBackground(shape(PANEL,LINE,12));
            LinearLayout.LayoutParams lp=margin(7);
            appRows.addView(row,lp);
            ImageView icon=new ImageView(this);
            try{icon.setImageDrawable(pm.getApplicationIcon(e.pkg));}
            catch(Exception x){icon.setImageResource(android.R.drawable.sym_def_app_icon);}
            row.addView(icon,new LinearLayout.LayoutParams(dp(39),dp(39)));
            LinearLayout labels=vertical();
            labels.setPadding(dp(12),0,dp(5),0);
            TextView title=text(e.title,14,WHITE,true);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title);
            TextView description=text(e.pkg,10,MUTE,false);description.setSingleLine(true);description.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            labels.addView(description);
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            TextView chevron=text("›",24,GREEN,true);
            row.addView(chevron);
            row.setOnClickListener(v->showAppDetail(e));
        }
        moreButton.setVisibility(filtered.size()>appLimit?View.VISIBLE:View.GONE);
        if(filtered.isEmpty())addText(appRows,"Совпадений нет. Попробуйте другое название.",13,MUTE,false,14);
    }
    private void showAppDetail(AppEntry entry){
        PackageManager pm=getPackageManager();
        LinearLayout layout=vertical();pad(layout,12,8);
        addText(layout,entry.pkg,11,MUTE,false,5);
        addText(layout,"Версия: "+entry.version,12,WHITE,false,4);
        addText(layout,"Тип: "+(entry.system?"Системное приложение":"Пользовательское приложение"),12,WHITE,false,4);
        if(entry.firstInstall>0)addText(layout,"Установлено: "+DateFormat.getDateInstance().format(new Date(entry.firstInstall)),12,WHITE,false,10);
        addText(layout,"Запрашиваемые опасные разрешения:",12,GREEN,true,6);
        int listed=0;
        try{
            PackageInfo info=pm.getPackageInfo(entry.pkg,PackageManager.GET_PERMISSIONS);
            if(info.requestedPermissions!=null){
                for(String perm:info.requestedPermissions){
                    try{
                        PermissionInfo pi=pm.getPermissionInfo(perm,0);
                        if(pi.getProtection()!=PermissionInfo.PROTECTION_DANGEROUS)continue;
                        String name=perm.replace("android.permission.","");
                        boolean granted=pm.checkPermission(perm,entry.pkg)==PackageManager.PERMISSION_GRANTED;
                        addText(layout,(granted?"✓ ":"○ ")+name+(granted?" — выдано":" — не выдано"),11,granted?Color.rgb(150,230,199):MUTE,false,5);
                        if(++listed>=18)break;
                    }catch(Exception ignored){}
                }
            }
        }catch(Exception ignored){}
        if(listed==0)addText(layout,"Нет доступных сведений о запрашиваемых опасных разрешениях.",11,MUTE,false,9);
        AlertDialog dlg=new AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setView(layout)
            .setNeutralButton("Запустить",(d,w)->{
                Intent launch=pm.getLaunchIntentForPackage(entry.pkg);
                if(launch==null)toast("Нет доступного действия запуска");
                else safelyLaunch(launch);
            })
            .setNegativeButton("Назад",null)
            .setPositiveButton("Настройки",(d,w)->safelyLaunch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+entry.pkg))))
            .create();
        dlg.setOnShowListener(d->{
            Button delete=button("Удалить через Android",false,()->{
                dlg.dismiss();
                new AlertDialog.Builder(this).setTitle("Системное удаление")
                    .setMessage("Удаление выполняет Android только после вашего подтверждения. Системные приложения могут не удаляться.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Открыть",(a,b)->safelyLaunch(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+entry.pkg))))
                    .show();
            });
            layout.addView(delete,margin(8));
        });
        dlg.show();
    }
    private void showTests(){
        LinearLayout page=page("Тесты оборудования","Ручные тесты компонентов. Результат оцениваете вы.");
        LinearLayout permissionTests=card(page,"Технические разрешения","Камера, микрофон, GPS, Bluetooth, SIM и уведомления — только по запросу.");
        addBtn(permissionTests,"Открыть центр разрешений",true,()->startActivity(new Intent(this,PermissionsActivity.class)));
        LinearLayout screen=card(page,"01 · Дисплей и сенсор","Цветовые заливки для поиска дефектов матрицы; рисование для проверки касаний.");
        addBtn(screen,"Цветовой тест дисплея",true,this::testDisplay);
        addBtn(screen,"Проверка сенсорной панели",false,this::testTouch);
        LinearLayout media=card(page,"02 · Звук и вибрация","Короткие тесты. Перед проверкой звука уменьшите громкость.");
        addBtn(media,"Воспроизвести короткий сигнал",true,this::testSound);
        addBtn(media,"Вибрация 350 мс",false,this::testVibration);
        LinearLayout sensors=card(page,"03 · Датчики","Список датчиков и живые значения выбранных устройств.");
        sensorManager=(SensorManager)getSystemService(SENSOR_SERVICE);
        List<Sensor> available=sensorManager==null?new ArrayList<>():sensorManager.getSensorList(Sensor.TYPE_ALL);
        addText(sensors,"Обнаружено сенсоров: "+available.size(),12,GREEN,true,9);
        Sensor[] selected=new Sensor[]{
            sensorManager==null?null:sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            sensorManager==null?null:sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
            sensorManager==null?null:sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT),
            sensorManager==null?null:sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        };
        String[] names={"Акселерометр","Гироскоп","Освещённость","Приближение"};
        for(int i=0;i<names.length;i++){
            final Sensor sensor=selected[i];
            final String name=names[i];
            addBtn(sensors,name+(sensor==null?" — недоступен":" · проверить"),false,()->sampleSensor(name,sensor));
        }
        addText(page,"Тесты не проверяют качество радиомодема, работу SIM или наличие вредоносных программ. Для этих задач необходимы отдельные диагностические инструменты.",11,MUTE,false,6);
    }
    private void testVibration(){
        try{
            Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            if(v==null || !v.hasVibrator()){toast("Вибромотор недоступен");return;}
            v.vibrate(VibrationEffect.createOneShot(350,VibrationEffect.DEFAULT_AMPLITUDE));
            toast("Проверка вибрации запущена");
        }catch(Exception e){toast("Вибрация заблокирована: "+e.getClass().getSimpleName());}
    }
    private void testSound(){
        try{
            final ToneGenerator generator=new ToneGenerator(AudioManager.STREAM_MUSIC,65);
            generator.startTone(ToneGenerator.TONE_PROP_BEEP,650);
            handler.postDelayed(()->{
                try{generator.release();}catch(Exception ignored){}
            },900);
            toast("Короткий сигнал воспроизведён");
        }catch(Exception e){toast("Не удалось воспроизвести звук");}
    }
    private void testDisplay(){
        final int[] colors={Color.WHITE,Color.BLACK,Color.RED,Color.GREEN,Color.BLUE,Color.GRAY};
        final String[] names={"Белый","Чёрный","Красный","Зелёный","Синий","Серый"};
        final int[] state={0};
        Dialog d=new Dialog(this);
        LinearLayout l=vertical();
        l.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(16),dp(20),dp(16),dp(30));
        d.setContentView(l);
        Runnable paint=()->{
            l.setBackgroundColor(colors[state[0]]);
            l.removeAllViews();
            TextView name=text(names[state[0]]+" · "+(state[0]+1)+"/"+colors.length,18,(state[0]==1||state[0]==2||state[0]==4)?Color.WHITE:Color.BLACK,true);
            name.setGravity(Gravity.CENTER);l.addView(name,margin(10));
            Button next=button(state[0]==colors.length-1?"Завершить":"Следующий цвет",true,()->{
                if(state[0]==colors.length-1){d.dismiss();return;}
                state[0]++; // rebuilt by stored click listener in view
                renderDisplayStep(l,d,colors,names,state);
            });
            l.addView(next,margin(5));
            Button close=button("Закрыть тест",false,d::dismiss);
            l.addView(close,margin(5));
        };
        paint.run();
        d.show();
        if(d.getWindow()!=null){
            d.getWindow().setLayout(-1,-1);
            d.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }
    private void renderDisplayStep(LinearLayout l,Dialog d,int[] colors,String[] names,int[] state){
        l.removeAllViews();
        l.setBackgroundColor(colors[state[0]]);
        TextView name=text(names[state[0]]+" · "+(state[0]+1)+"/"+colors.length,19,(state[0]==1||state[0]==2||state[0]==4)?Color.WHITE:Color.BLACK,true);
        name.setGravity(Gravity.CENTER);l.addView(name,margin(12));
        addBtn(l,state[0]==colors.length-1?"Завершить":"Следующий цвет",true,()->{
            if(state[0]==colors.length-1)d.dismiss();
            else{state[0]++;renderDisplayStep(l,d,colors,names,state);}
        });
        addBtn(l,"Закрыть",false,d::dismiss);
    }
    private static class TouchPad extends View {
        private final ArrayList<float[]> points=new ArrayList<>();
        private final Paint paint=new Paint(3);
        TouchPad(Context c){super(c);setBackgroundColor(Color.rgb(12,23,35));}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)getParent().requestDisallowInterceptTouchEvent(true);
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE||e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){
                for(int i=0;i<e.getPointerCount();i++){
                    points.add(new float[]{e.getX(i),e.getY(i)});
                }
                while(points.size()>1800)points.remove(0);
                invalidate();
            }
            return true;
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            paint.setColor(Color.rgb(45,65,82));paint.setStrokeWidth(1);
            for(int x=0;x<getWidth();x+=60)c.drawLine(x,0,x,getHeight(),paint);
            for(int y=0;y<getHeight();y+=60)c.drawLine(0,y,getWidth(),y,paint);
            paint.setColor(Color.rgb(74,230,177));paint.setStyle(Paint.Style.FILL);
            for(float[] p:points)c.drawCircle(p[0],p[1],dpLocal(5),paint);
        }
        private float dpLocal(int px){return px*getResources().getDisplayMetrics().density;}
        void reset(){points.clear();invalidate();}
    }
    private void testTouch(){
        Dialog d=new Dialog(this);
        LinearLayout l=vertical();l.setBackgroundColor(BG);pad(l,12,16);
        l.addView(text("Водите пальцем по экрану и проверяйте пропуски касаний.",13,WHITE,true),margin(13));
        TouchPad pad=new TouchPad(this);
        l.addView(pad,new LinearLayout.LayoutParams(-1,0,1));
        addBtn(l,"Очистить след",false,pad::reset);
        addBtn(l,"Закрыть тест",true,d::dismiss);
        d.setContentView(l);d.show();
        if(d.getWindow()!=null)d.getWindow().setLayout(-1,-1);
    }
    private void stopSensor(){
        if(sensorManager!=null && currentSensorListener!=null){
            sensorManager.unregisterListener(currentSensorListener);
            currentSensorListener=null;
        }
    }
    private void sampleSensor(String label,Sensor sensor){
        stopSensor();
        if(sensor==null){toast(label+": аппаратно недоступен");return;}
        TextView result=text("Ожидание данных…",14,WHITE,false);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("Датчик: "+label)
            .setView(result)
            .setPositiveButton("Закрыть",(d,w)->stopSensor())
            .create();
        result.setPadding(dp(22),dp(12),dp(22),dp(12));
        dialog.setOnDismissListener(d->stopSensor());
        dialog.show();
        final SensorEventListener listener=new SensorEventListener(){
            public void onAccuracyChanged(Sensor s,int accuracy){}
            public void onSensorChanged(SensorEvent e){
                if(currentSensorListener!=this)return;
                StringBuilder sb=new StringBuilder();
                sb.append(sensor.getName()).append("\n\n");
                for(int i=0;i<Math.min(4,e.values.length);i++){
                    sb.append("Ось ").append(i+1).append(": ")
                        .append(String.format(Locale.getDefault(),"%.3f",e.values[i])).append("\n");
                }
                result.setText(sb.toString());
            }
        };
        currentSensorListener=listener;
        try{
            if(!sensorManager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_UI)){
                result.setText("Датчик не предоставил поток данных");
                stopSensor();
            }
        }catch(Exception e){result.setText("Не удалось открыть поток датчика");stopSensor();}
        handler.postDelayed(()->{
            if(currentSensorListener==listener){stopSensor();}
        },6500);
    }
    private void showData(){
        LinearLayout page=page("Данные и инструменты","Расширенные операции открываются в проверенной версии 0.1.");
        LinearLayout proCard=card(page,"PRO · Shizuku и Windows ADB",
            "Проверка Shizuku, выбор приложений, очистка кэша и данных, отключение с отдельным подтверждением, журнал и ADB-сценарий.");
        addBtn(proCard,"Открыть сервисный режим PRO",true,()->startActivity(new Intent(this,ProActivity.class)));
        LinearLayout transfer=card(page,"Перенос на ПК и второй телефон",
            "Quick Share, экспорт APK/ссылки на приложения, создание ZIP выбранных файлов и перечня программ, последующее восстановление.");
        addBtn(transfer,"Открыть перенос и резервное копирование",true,()->startActivity(new Intent(this,TransferHubActivity.class)));
        LinearLayout scanner=card(page,"Проверка рекламы и управление приложениями",
            "Сканирование при открытии, выбор программ, системная очистка кэша / данных, удаление, резервное копирование.");
        addBtn(scanner,"Открыть контроль приложений",true,()->startActivity(new Intent(this,AppAuditActivity.class)));
        addBtn(scanner,"Резервная копия / удалить выбранные файлы",false,()->startActivity(new Intent(this,PersonalFilesActivity.class)));
        LinearLayout rights=card(page,"Центр разрешений","Все доступные диагностические разрешения, специальные доступы и проверки.");
        addBtn(rights,"Открыть центр разрешений",true,()->startActivity(new Intent(this,PermissionsActivity.class)));
        LinearLayout audit=card(page,"Активность приложений · AppUsage","История запусков на экране и зарегистрированных foreground-служб, поиск, фильтры, CSV.");
        addBtn(audit,"Открыть журнал активности",true,()->startActivity(new Intent(this,ActivityMonitorActivity.class)));
        LinearLayout ext=card(page,"Контакты, файлы и отчёты","Резервное копирование VCF, системный выбор файлов, экспорт JSON и статистика использования.");
        addBtn(ext,"Открыть инструменты и экспорт",true,()->startActivity(new Intent(this,MainActivity.class)));
        LinearLayout android=card(page,"Переходы в настройки","Каждая операция выполняется в стандартном интерфейсе Android.");
        addBtn(android,"Настройки приложений",false,()->safelyLaunch(new Intent(Settings.ACTION_APPLICATION_SETTINGS)));
        addBtn(android,"Сведения об устройстве",false,()->safelyLaunch(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        addBtn(android,"Настройки Wi-Fi",false,()->safelyLaunch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        LinearLayout about=card(page,"О приложении","Android Control Hub 0.8.1 Beta · локальная сервисная утилита.");
        addText(about,"Доступ к файлам предоставляется только через системный выбор. Контакты экспортируются только по отдельному запросу. Ни пароли, ни SMS, ни содержимое других приложений не собираются.",12,MUTE,false,12);
        addBtn(about,"Открыть веб-сайт",false,()->safelyLaunch(new Intent(Intent.ACTION_VIEW,Uri.parse("https://kadukow1976-prog.github.io/android-control-hub/"))));
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void safelyLaunch(Intent i){
        try{startActivity(i);}catch(Exception ex){toast("Системное действие недоступно");}
    }
}
