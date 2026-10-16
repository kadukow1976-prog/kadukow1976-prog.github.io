package ru.kadukov.androidcontrolhub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AppAuditActivity extends Activity {
    private static final int BG=0xff09101d, CARD=0xff142234, BORDER=0xff30475b;
    private static final int LIGHT=0xffeef7ff, MUTED=0xff9fb6c9, GREEN=0xff4ae6b1;
    private static final String[] FILTERS={"Требуют проверки", "Наложение окон", "Автозапуск", "Планировщик/таймеры", "Игры", "Все пользовательские", "Выбранные", "Все включая системные"};
    private final ArrayList<AppItem> inventory=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private LinearLayout root, rows;
    private TextView scanInfo, listInfo;
    private Spinner filter;
    private String query="";
    private int currentFilter=0;
    private boolean loading=false, scanned=false;
    private int generation=0;
    private final ArrayList<AppItem> batch=new ArrayList<>();
    private int batchIndex=0,batchMode=0;
    private boolean batchWaiting=false;

    private static class AppItem {
        String pkg, title, version;
        boolean system, overlayDeclared, bootReceiver, jobService, alarmDeclared, foregroundService, game;
        int recentScreenEvents, recentServiceEvents;
        long lastActivity;
        int flags(){
            return (overlayDeclared?1:0)+(bootReceiver?1:0)+(recentServiceEvents>0?1:0);
        }
    }
    private int dp(float a){return Math.round(a*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int fill){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(13));d.setStroke(dp(1),BORDER);return d;
    }
    private TextView text(String v,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(v);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        t.setLineSpacing(dp(2),1);return t;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout.LayoutParams space(int n){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(n);return p;
    }
    private void add(LinearLayout v,String s,int size,int color,boolean bold,int bottom){v.addView(text(s,size,color,bold),space(bottom));}
    private Button button(String s,Runnable action,boolean primary){
        Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextSize(12);
        b.setTextColor(primary?0xff092e25:LIGHT);
        GradientDrawable d=shape(primary?GREEN:0xff1d3549);b.setBackground(d);
        b.setOnClickListener(v->action.run());return b;
    }
    private void action(LinearLayout l,String s,Runnable run,boolean primary){
        LinearLayout.LayoutParams p=space(9);p.height=dp(47);
        l.addView(button(s,run,primary),p);
    }
    private LinearLayout card(String heading,String sub){
        LinearLayout c=column();c.setPadding(dp(15),dp(14),dp(15),dp(14));
        c.setBackground(shape(CARD));root.addView(c,space(13));
        add(c,heading,16,LIGHT,true,5);
        if(sub!=null)add(c,sub,12,MUTED,false,10);
        return c;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        root=column();root.setPadding(dp(14),dp(19),dp(14),dp(30));
        scroll.addView(root);setContentView(scroll);
        add(root,"Контроль приложений",24,GREEN,true,7);
        add(root,"Сканирование запускается при открытии экрана. Никакие приложения не удаляются, не отключаются и не очищаются автоматически.",12,MUTED,false,15);
        LinearLayout intro=card("01  Автосканирование",
            "Проверка установленных пакетов, разрешения наложения окон, объявления автозапуска, категории игр и доступных событий использования.");
        scanInfo=text("Подготовка…",12,MUTED,false);intro.addView(scanInfo,space(10));
        action(intro,"Повторить сканирование",this::scan,true);
        action(intro,"Журнал активности Android (Usage Access)",()->startActivity(new Intent(this,ActivityMonitorActivity.class)),false);
        LinearLayout controls=card("02  Отдельные группы","Признаки, а не диагноз вируса. Игры и программы с наложением окон могут быть безопасными.");
        filter=new Spinner(this);
        ArrayAdapter<String> choices=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,FILTERS);
        filter.setAdapter(choices);controls.addView(filter,space(10));
        EditText search=new EditText(this);search.setTextSize(13);
        search.setTextColor(LIGHT);search.setHintTextColor(MUTED);search.setSingleLine(true);
        search.setHint("Поиск названия или ID пакета");search.setPadding(dp(12),dp(9),dp(12),dp(9));
        search.setBackground(shape(0xff0c1c2b));controls.addView(search,space(10));
        listInfo=text("",12,GREEN,true);controls.addView(listInfo,space(9));
        action(controls,"Действия с выбранными приложениями",this::manageSelected,true);
        action(controls,"Удалить выбранные через Android",()->startBatch(1),false);
        action(controls,"Отключить выбранные через Android",()->startBatch(2),false);
        action(controls,"Очистить кэш выбранных через Android",()->startBatch(3),false);
        action(controls,"Очистить данные выбранных через Android",()->startBatch(4),false);
        action(controls,"Резервная копия / удалить личные файлы",()->startActivity(new Intent(this,PersonalFilesActivity.class)),false);

        LinearLayout listing=card("03  Приложения","Отметьте программы, которые хотите проверить или обслужить.");
        rows=column();listing.addView(rows);
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View v,int position,long id){
                currentFilter=position;renderRows();
            }
        });
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                query=s.toString().trim().toLowerCase(Locale.ROOT);renderRows();
            }
            public void afterTextChanged(Editable e){}
        });
        LinearLayout note=card("Что именно проверяется","Осторожно с системными и банковскими приложениями.");
        add(note,"• Наложение: приложение запросило SYSTEM_ALERT_WINDOW; реально включённый статус чужого приложения не гарантированно доступен.",11,MUTED,false,5);
        add(note,"• Автозапуск: право RECEIVE_BOOT_COMPLETED или получатель загрузки. Это не доказывает реальный запуск.",11,MUTED,false,5);
        add(note,"• Планировщик: объявлена служба JobScheduler или запрос точных таймеров. Расписание и код заданий скрыты. Фон: Usage Events при выдаче доступа.",11,MUTED,false,5);
        add(note,"• Сведения о рекламе — признаки для ручной проверки. Автоматически назвать приложение вредоносным по этим признакам нельзя.",11,MUTED,false,2);
        scan();
    }
    @Override protected void onDestroy(){generation++;batch.clear();super.onDestroy();}
    @Override protected void onResume(){
        super.onResume();
        if(batchWaiting){
            batchWaiting=false;
            new AlertDialog.Builder(this)
               .setTitle("Продолжить обработку")
               .setMessage("Android вернул управление. Выполнение операции в настройках не подтверждено этим приложением. Продолжить к следующему выбранному пакету?")
               .setNegativeButton("Остановить",(d,w)->stopBatch())
               .setPositiveButton("Следующий",(d,w)->{batchIndex++;showBatchStep();})
               .show();
        }
    }
    private boolean usageAllowed(){
        try{
            AppOpsManager a=(AppOpsManager)getSystemService(APP_OPS_SERVICE);
            return a!=null && a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;
        }catch(Exception e){return false;}
    }
    private static boolean permissionDeclared(PackageInfo info,String permission){
        if(info.requestedPermissions==null)return false;
        for(String p:info.requestedPermissions)if(permission.equals(p))return true;
        return false;
    }
    private void scan(){
        if(loading)return;
        loading=true;scanned=false;
        final int token=++generation;
        scanInfo.setText("Идёт проверка. Не закрывайте экран…");
        rows.removeAllViews();
        PackageManager pm=getPackageManager();
        new Thread(()->{
            ArrayList<AppItem> result=new ArrayList<>();
            String failure=null;boolean history=usageAllowed();
            try{
                int flags=PackageManager.GET_PERMISSIONS | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES;
                List<PackageInfo> packages=pm.getInstalledPackages(flags);
                HashMap<String,int[]> events=new HashMap<>();
                HashSet<String> bootReceivers=new HashSet<>();
                try{
                    for(android.content.pm.ResolveInfo receiver:pm.queryBroadcastReceivers(new Intent(Intent.ACTION_BOOT_COMPLETED),0)){
                        if(receiver.activityInfo!=null)bootReceivers.add(receiver.activityInfo.packageName);
                    }
                }catch(Exception ignored){}
                HashMap<String,Long> mostRecent=new HashMap<>();
                if(history){
                    try{
                        UsageStatsManager mgr=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);
                        UsageEvents historyEvents=mgr==null?null:mgr.queryEvents(System.currentTimeMillis()-86400000L,System.currentTimeMillis());
                        if(historyEvents!=null){
                            UsageEvents.Event e=new UsageEvents.Event();
                            while(historyEvents.hasNextEvent()){
                                historyEvents.getNextEvent(e);
                                String pkg=e.getPackageName();
                                if(pkg==null)continue;
                                int type=e.getEventType();
                                if(type==1||type==19){
                                    int[] counters=events.get(pkg);
                                    if(counters==null){counters=new int[2];events.put(pkg,counters);}
                                    if(type==1)counters[0]++;
                                    if(type==19)counters[1]++;
                                    mostRecent.put(pkg,e.getTimeStamp());
                                }
                            }
                        }
                    }catch(Exception ignored){}
                }
                for(PackageInfo info:packages){
                    ApplicationInfo app=info.applicationInfo;
                    if(app==null)continue;
                    AppItem x=new AppItem();
                    x.pkg=info.packageName;
                    x.title=String.valueOf(pm.getApplicationLabel(app));
                    x.system=(app.flags & ApplicationInfo.FLAG_SYSTEM)!=0;
                    x.game=app.category==ApplicationInfo.CATEGORY_GAME||(app.flags&ApplicationInfo.FLAG_IS_GAME)!=0;
                    x.version=info.versionName==null?"—":info.versionName;
                    x.overlayDeclared=permissionDeclared(info,Manifest.permission.SYSTEM_ALERT_WINDOW);
                    x.foregroundService=permissionDeclared(info,"android.permission.FOREGROUND_SERVICE");
                    x.bootReceiver=bootReceivers.contains(x.pkg)||permissionDeclared(info,"android.permission.RECEIVE_BOOT_COMPLETED");
                    // Only declared capabilities: no access to another app's actual job queue or scripts.
                    x.alarmDeclared=permissionDeclared(info,"android.permission.SCHEDULE_EXACT_ALARM")||
                        permissionDeclared(info,"android.permission.USE_EXACT_ALARM");
                    if(info.services!=null)for(ServiceInfo service:info.services){
                        if("android.permission.BIND_JOB_SERVICE".equals(service.permission)){x.jobService=true;break;}
                    }
                    int[] counters=events.get(x.pkg);
                    if(counters!=null){x.recentScreenEvents=counters[0];x.recentServiceEvents=counters[1];}
                    Long last=mostRecent.get(x.pkg);if(last!=null)x.lastActivity=last;
                    result.add(x);
                }
                result.sort(Comparator.comparing(x->x.title.toLowerCase(Locale.ROOT)));
            }catch(Exception ex){failure=ex.getClass().getSimpleName()+": "+ex.getMessage();}
            String error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||token!=generation)return;
                inventory.clear();inventory.addAll(result);
                loading=false;scanned=error==null;
                int overlays=0,boot=0,jobs=0,games=0,recent=0;
                for(AppItem a:inventory){if(a.overlayDeclared)overlays++;if(a.bootReceiver)boot++;if(a.jobService||a.alarmDeclared)jobs++;if(a.game)games++;if(a.recentServiceEvents>0)recent++;}
                scanInfo.setText(error!=null?"Не удалось завершить скан: "+error:
                    "Найдено пакетов: "+inventory.size()+"\nС запросом наложения: "+overlays+
                    "\nВозможный автозапуск: "+boot+
                    "\nЗадания/таймеры (объявлены): "+jobs+"\nИгры: "+games+"\nС событиями foreground-служб за 24 ч: "+recent+
                    "\nИстория активности: "+(history?"разрешена":"нет Usage Access"));
                renderRows();
            });
        },"ach-package-audit").start();
    }
    private boolean matches(AppItem x){
        if(!query.isEmpty()&&!x.pkg.toLowerCase(Locale.ROOT).contains(query)
          &&!x.title.toLowerCase(Locale.ROOT).contains(query))return false;
        switch(currentFilter){
            case 0:return !x.system&&(x.overlayDeclared||x.bootReceiver||x.jobService||x.alarmDeclared||x.recentServiceEvents>0);
            case 1:return x.overlayDeclared;
            case 2:return x.bootReceiver;
            case 3:return x.jobService||x.alarmDeclared;
            case 4:return x.game;
            case 5:return !x.system;
            case 6:return selected.contains(x.pkg);
            default:return true;
        }
    }
    private void renderRows(){
        if(rows==null||listInfo==null)return;
        rows.removeAllViews();
        int count=0,rendered=0;
        for(AppItem app:inventory){
            if(!matches(app))continue;
            count++;
            if(rendered++>=120)continue;
            LinearLayout item=column();item.setPadding(dp(12),dp(9),dp(12),dp(10));
            item.setBackground(shape(0xff0d1e2c));rows.addView(item,space(8));
            CheckBox pick=new CheckBox(this);
            pick.setText(app.title);pick.setTextSize(14);pick.setTextColor(LIGHT);
            pick.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
            pick.setChecked(selected.contains(app.pkg));
            pick.setOnCheckedChangeListener((b,checked)->{
                if(checked)selected.add(app.pkg);else selected.remove(app.pkg);
                updateCount();
                if(currentFilter==6)renderRows();
            });
            item.addView(pick);
            add(item,app.pkg,10,MUTED,false,3);
            StringBuilder labels=new StringBuilder();
            if(app.overlayDeclared)labels.append("Наложения заявлены · ");
            if(app.bootReceiver)labels.append("Может стартовать при загрузке · ");
            if(app.jobService||app.alarmDeclared)labels.append("Планировщик/таймеры · ");
            if(app.game)labels.append("Игра · ");
            if(app.recentServiceEvents>0)labels.append("Foreground: "+app.recentServiceEvents+" · ");
            if(app.recentScreenEvents>0)labels.append("На экране: "+app.recentScreenEvents+" · ");
            if(labels.length()==0)labels.append("Подозрительных признаков не выявлено");
            add(item,labels.toString(),11,(app.overlayDeclared||app.bootReceiver||app.jobService||app.alarmDeclared)?0xffffcc83:MUTED,false,5);
            action(item,"Действия: "+app.title,()->manageOne(app),false);
        }
        if(count==0)add(rows,"Нет приложений по выбранным условиям.",13,MUTED,false,10);
        if(count>120)add(rows,"Показано 120 из "+count+". Используйте поиск для остальных.",12,MUTED,false,10);
        updateCount();
    }
    private void updateCount(){
        listInfo.setText("Отмечено: "+selected.size()+"   ·   Всего в скане: "+inventory.size());
    }
    private ArrayList<AppItem> getSelected(){
        ArrayList<AppItem> matches=new ArrayList<>();
        for(AppItem app:inventory)if(selected.contains(app.pkg))matches.add(app);
        return matches;
    }
    private void stopBatch(){
        batch.clear();batchIndex=0;batchMode=0;batchWaiting=false;
    }
    private String batchName(){
        switch(batchMode){
            case 1:return "Удаление";
            case 2:return "Отключение";
            case 3:return "Очистка кэша";
            case 4:return "Очистка данных";
            default:return "Операция";
        }
    }
    private void startBatch(int mode){
        ArrayList<AppItem> chosen=getSelected();
        if(chosen.isEmpty()){
            Toast.makeText(this,"Сначала отметьте приложения в списке.",Toast.LENGTH_LONG).show();return;
        }
        if(mode==1){
            chosen.removeIf(item->item.system||item.pkg.equals(getPackageName()));
            if(chosen.isEmpty()){
                Toast.makeText(this,"Системные приложения нельзя удалять пакетным сценарием.",Toast.LENGTH_LONG).show();return;
            }
        }
        stopBatch();batch.addAll(chosen);batchMode=mode;
        new AlertDialog.Builder(this)
            .setTitle(batchName()+" · "+batch.size()+" приложений")
            .setMessage("Это последовательная процедура: для КАЖДОГО приложения Android потребует ручное подтверждение. Ничего не очищается скрытно.\n\n"+
                (mode==4||mode==1?"ВНИМАНИЕ: данные приложений могут быть безвозвратно удалены. Сначала создайте резервные копии в самих приложениях и сохраните доступные личные документы.":"Проверяйте назначение программы перед действием."))
            .setNeutralButton("Сначала сохранить файлы",(d,w)->{stopBatch();startActivity(new Intent(this,PersonalFilesActivity.class));})
            .setNegativeButton("Отмена",(d,w)->stopBatch())
            .setPositiveButton("Начать",(d,w)->showBatchStep()).show();
    }
    private void showBatchStep(){
        if(batchIndex>=batch.size()){
            int total=batch.size();String label=batchName();stopBatch();
            new AlertDialog.Builder(this)
                .setTitle("Список обработан")
                .setMessage("Системные экраны открывались последовательно для "+total+" приложений.\n\nЭто НЕ подтверждение удаления или очистки: проверяйте состояние в Android.\n\n"+label+" завершено только для тех программ, где вы подтвердили действие в системе.")
                .setPositiveButton("Обновить скан",(d,w)->scan())
                .setNegativeButton("Закрыть",null).show();
            return;
        }
        AppItem current=batch.get(batchIndex);
        final String desc=(batchMode==1?"Android откроет окно удаления. Приложение может потерять свои приватные данные.":
            batchMode==4?"На системной карточке найдите «Память / Хранилище» → «Очистить данные». Это может удалить аккаунты, настройки и файлы.":
            batchMode==3?"На системной карточке найдите «Память / Хранилище» → «Очистить кэш». Android не разрешает обычной утилите очищать его напрямую.":
            "В системной карточке найдите «Отключить» (если доступно). Нельзя отключать критические службы.");
        new AlertDialog.Builder(this).setTitle(batchName()+" · "+(batchIndex+1)+"/"+batch.size())
          .setMessage(current.title+"\n"+current.pkg+"\n\n"+desc)
          .setNeutralButton("Пропустить",(d,w)->{batchIndex++;showBatchStep();})
          .setNegativeButton("Остановить",(d,w)->stopBatch())
          .setPositiveButton("Открыть Android",(d,w)->{
              batchWaiting=true;
              if(batchMode==1)launch(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+current.pkg)));
              else openDetails(current);
          }).show();
    }
    private void manageSelected(){
        ArrayList<AppItem> matches=getSelected();
        if(matches.isEmpty()){
            Toast.makeText(this,"Сначала отметьте приложения галочками.",Toast.LENGTH_LONG).show();return;
        }
        String[] titles=new String[matches.size()];
        for(int i=0;i<matches.size();i++)titles[i]=matches.get(i).title+" ("+matches.get(i).pkg+")";
        new AlertDialog.Builder(this)
            .setTitle("Выбрано "+matches.size()+" приложений")
            .setMessage("Для безопасности действия открываются ПО ОДНОМУ приложению. Системные окна Android требуют вашего подтверждения. Массовой скрытой очистки или удаления нет.")
            .setPositiveButton("Выбрать приложение",(d,w)->new AlertDialog.Builder(this).setTitle("Управление выбранными")
               .setItems(titles,(dialog,which)->manageOne(matches.get(which)))
               .setNegativeButton("Закрыть",null).show())
            .setNegativeButton("Отмена",null).show();
    }
    private void manageOne(AppItem app){
        final String[] choices={
            "Открыть системные сведения / очистку кэша",
            "Очистка всех данных через Android",
            "Отключить / приостановить через Android",
            "Разрешение показывать поверх окон",
            "Обновить в Google Play",
            "Удалить через системное окно",
            "Сохранить / удалить выбранные личные файлы",
            "История активности"
        };
        new AlertDialog.Builder(this)
            .setTitle(app.title+"\n"+app.pkg)
            .setMessage("Версия "+app.version+(app.system?" · системный пакет":"")+
                "\nНаложения: "+(app.overlayDeclared?"заявлено разрешение":"не заявлено")+
                "\nЗапуск после загрузки: "+(app.bootReceiver?"заявлена возможность":"не обнаружен")+
                "\nJobService/точные таймеры: "+(app.jobService||app.alarmDeclared?"заявлена возможность":"не обнаружено")+"\nКонкретные отложенные задачи, команды и скрипты других приложений: недоступны обычному API.")
            .setItems(choices,(d,which)->{
                switch(which){
                    case 0:openDetails(app);break;
                    case 1:confirmSettings(app,"Полная очистка данных",
                            "Android может удалить аккаунты, настройки, внутренние файлы и данные приложения. Сначала сохраните доступные личные файлы.",()->openDetails(app));break;
                    case 2:confirmSettings(app,"Отключение приложения",
                            "Android может не позволить отключить системные службы. Для неизвестного приложения сначала проверьте его назначение.",()->openDetails(app));break;
                    case 3:openOverlay(app);break;
                    case 4:openStore(app);break;
                    case 5:confirmSettings(app,"Удалить приложение",
                            "Сохранить выбранные документы можно в разделе резервного копирования. Удаление приложения может стереть его приватные данные.",
                            ()->{
                                Intent intent=new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+app.pkg));
                                launch(intent);
                            });break;
                    case 6:startActivity(new Intent(this,PersonalFilesActivity.class));break;
                    case 7:startActivity(new Intent(this,ActivityMonitorActivity.class));break;
                }
            }).setNegativeButton("Закрыть",null).show();
    }
    private void confirmSettings(AppItem app,String title,String message,Runnable op){
        new AlertDialog.Builder(this)
            .setTitle(title+": "+app.title)
            .setMessage(message+"\n\nФайлы в защищённом хранилище этого приложения обычным APK скопировать невозможно.")
            .setNeutralButton("Сохранить мои файлы",(d,w)->startActivity(new Intent(this,PersonalFilesActivity.class)))
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Продолжить в Android",(d,w)->op.run()).show();
    }
    private void launch(Intent intent){
        try{startActivity(intent);}
        catch(Exception e){Toast.makeText(this,"Android не поддерживает это действие.",Toast.LENGTH_LONG).show();}
    }
    private void openDetails(AppItem a){
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+a.pkg)));
    }
    private void openOverlay(AppItem a){
        Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+a.pkg));
        launch(i);
    }
    private void openStore(AppItem a){
        Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id="+Uri.encode(a.pkg)));
        try{startActivity(i);}
        catch(Exception e){
            launch(new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id="+Uri.encode(a.pkg))));
        }
    }
}
