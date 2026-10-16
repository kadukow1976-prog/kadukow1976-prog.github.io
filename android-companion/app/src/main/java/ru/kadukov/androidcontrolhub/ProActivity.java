package ru.kadukov.androidcontrolhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import rikka.shizuku.Shizuku;

public final class ProActivity extends Activity {
    private static final int BG=0xff0b1524, CARD=0xff18283d, BORDER=0xff33465b, LIGHT=0xffedf7ff, MUTED=0xffa8bdcf, GREEN=0xff4be8b7;
    private static final int REQ_CREDENTIAL=904, REQ_SAVE_CMD=905, REQ_SHIZUKU=906;
    private static final String CACHE="CACHE", DATA="DATA", DISABLE="DISABLE";
    private final ArrayList<App> installed=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private LinearLayout root, rows;
    private TextView connectionText, countText, logText;
    private String search="", action=CACHE;
    private volatile IBinder remote;
    private boolean busy=false, pendingCredential=false, bound=false;
    private int nextIndex=0;
    private ArrayList<App> queue=new ArrayList<>();
    private boolean exportPrepared=false;
    private Shizuku.UserServiceArgs serviceArgs;

    private final Shizuku.OnRequestPermissionResultListener permissionListener=(request, result)->runOnUiThread(()->{
        if(request!=REQ_SHIZUKU)return;
        if(result==PackageManager.PERMISSION_GRANTED)connectShizuku();
        else alert("Shizuku","Разрешение не выдано. Системное обслуживание недоступно.");
    });
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            remote=binder;bound=true;
            runOnUiThread(()->{
                if(connectionText!=null)connectionText.setText("Shizuku UserService подключён · shell UID 2000");
                toast("Подключение к Shizuku установлено");
            });
        }
        @Override public void onServiceDisconnected(ComponentName name){
            remote=null;bound=false;
            runOnUiThread(()->{if(connectionText!=null)connectionText.setText("Shizuku отключён");});
        }
    };
    private static final class App {
        final String name, pkg;
        App(String n,String p){name=n;pkg=p;}
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout stack(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout.LayoutParams gap(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);return p;}
    private GradientDrawable shape(int bg){
        GradientDrawable d=new GradientDrawable();d.setColor(bg);d.setStroke(dp(1),BORDER);d.setCornerRadius(dp(14));return d;
    }
    private TextView label(String s,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(2),1);
        if(bold)v.setTypeface(null,Typeface.BOLD);return v;
    }
    private void line(LinearLayout l,String s,int size,int color,boolean bold,int bottom){l.addView(label(s,size,color,bold),gap(bottom));}
    private LinearLayout block(String title,String description){
        LinearLayout l=stack();l.setPadding(dp(15),dp(14),dp(15),dp(14));l.setBackground(shape(CARD));
        root.addView(l,gap(12));line(l,title,16,LIGHT,true,5);line(l,description,12,MUTED,false,12);
        return l;
    }
    private void button(LinearLayout parent,String title,boolean primary,Runnable callback){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextSize(12);b.setTextColor(primary?0xff12392e:LIGHT);
        b.setBackground(shape(primary?GREEN:0xff25394e));b.setOnClickListener(v->callback.run());
        LinearLayout.LayoutParams p=gap(8);p.height=dp(46);parent.addView(b,p);
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void alert(String heading,String message){
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle(heading).setMessage(message).setPositiveButton("Закрыть",null).show();
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        root=stack();root.setPadding(dp(14),dp(18),dp(14),dp(30));scroll.addView(root);
        setContentView(scroll);
        line(root,"PRO · Shizuku / ADB",24,GREEN,true,8);
        line(root,"Сервисное обслуживание выбранных приложений с явным согласием владельца и журналом выполнения.",12,MUTED,false,15);
        LinearLayout auth=block("01 · Подключение Shizuku",
            "Установите Shizuku и запустите службу через беспроводную или USB-отладку. Для этой сборки поддерживается только shell UID 2000.");
        connectionText=label("Проверка подключения…",12,GREEN,true);auth.addView(connectionText,gap(12));
        button(auth,"Проверить / авторизовать Shizuku",true,this::connectShizuku);
        button(auth,"Как настроить Shizuku",false,()->openUrl("https://shizuku.rikka.app/guide/setup/"));
        LinearLayout choose=block("02 · Выбор приложений",
            "Показываются только пользовательские программы. Android Control Hub, системные и важные компоненты исключены.");
        EditText filter=new EditText(this);filter.setTextColor(LIGHT);filter.setHintTextColor(MUTED);
        filter.setHint("Поиск по названию или пакету");filter.setSingleLine(true);filter.setTextSize(13);
        filter.setPadding(dp(10),dp(8),dp(10),dp(8));filter.setBackground(shape(0xff101d2b));
        choose.addView(filter,gap(12));
        countText=label("",12,GREEN,true);choose.addView(countText,gap(9));
        rows=stack();choose.addView(rows);
        filter.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){search=s.toString().trim().toLowerCase(Locale.ROOT);renderApps();}
            public void afterTextChanged(Editable e){}
        });
        LinearLayout actions=block("03 · Обслуживание",
            "Очистка только кэша проверяет поддержку --cache-only. Очистка данных удаляет настройки и авторизации.");
        Spinner spinner=new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Только кэш", "Все данные — необратимо", "Отключить приложение"}));
        actions.addView(spinner,gap(10));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View v,int pos,long id){
                action=pos==0?CACHE:pos==1?DATA:DISABLE;
            }
        });
        button(actions,"Просмотреть операции",false,this::preview);
        button(actions,"Выполнить выбранное через Shizuku",true,this::begin);
        button(actions,"Создать сценарий для Windows + ADB",false,this::prepareExport);
        button(actions,"Резервная копия перед очисткой",false,()->startActivity(new Intent(this,TransferHubActivity.class)));
        LinearLayout logs=block("04 · Протокол",
            "Вывод операций появляется после ответа системы. Ошибки и отказы показываются отдельно.");
        logText=label("Нет выполненных операций",11,LIGHT,false);logs.addView(logText,gap(3));
        line(root,"Внимание: PIN/пароль подтверждается в штатном окне Android и не передаётся программе. Операции могут быть отклонены прошивкой. После очистки данных восстановление не гарантируется.",11,MUTED,false,8);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        loadApps();
        refreshStatus();
    }
    @Override protected void onDestroy(){
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        if(bound && serviceArgs!=null){
            try{Shizuku.unbindUserService(serviceArgs,connection,true);}catch(Throwable ignored){}
        }
        remote=null;
        super.onDestroy();
    }
    private boolean safe(String pkg){
        return pkg!=null&&pkg.length()<180&&pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
            &&!pkg.equals(getPackageName())&&!pkg.startsWith("com.android.")
            &&!pkg.startsWith("com.google.android.")&&!pkg.startsWith("com.oplus.")
            &&!pkg.startsWith("com.oneplus.")&&!pkg.startsWith("com.qualcomm.")
            &&!pkg.startsWith("com.mediatek.");
    }
    private void loadApps(){
        PackageManager pm=getPackageManager();
        installed.clear();
        for(PackageInfo pkg:pm.getInstalledPackages(0)){
            ApplicationInfo info=pkg.applicationInfo;
            if(info==null||(info.flags&ApplicationInfo.FLAG_SYSTEM)!=0||!safe(pkg.packageName))continue;
            installed.add(new App(String.valueOf(pm.getApplicationLabel(info)),pkg.packageName));
        }
        installed.sort(Comparator.comparing(x->x.name.toLowerCase(Locale.ROOT)));
        renderApps();
    }
    private void renderApps(){
        if(rows==null)return;
        rows.removeAllViews();int total=0,visible=0;
        for(App app:installed){
            if(!app.name.toLowerCase(Locale.ROOT).contains(search)&&!app.pkg.toLowerCase(Locale.ROOT).contains(search))continue;
            total++;if(visible++>=85)continue;
            CheckBox box=new CheckBox(this);box.setText(app.name+"\n"+app.pkg);
            box.setTextSize(12);box.setTextColor(LIGHT);box.setChecked(selected.contains(app.pkg));
            box.setOnCheckedChangeListener((b,checked)->{
                if(checked)selected.add(app.pkg);else selected.remove(app.pkg);
                countText.setText("Выбрано: "+selected.size()+" / "+installed.size());
            });
            rows.addView(box,gap(4));
        }
        if(total>85)line(rows,"Показано 85 из "+total+". Для остальных используйте поиск.",11,MUTED,false,4);
        countText.setText("Выбрано: "+selected.size()+" / "+installed.size());
    }
    private ArrayList<App> chosen(){
        ArrayList<App> out=new ArrayList<>();
        for(App app:installed)if(selected.contains(app.pkg)&&safe(app.pkg))out.add(app);
        return out;
    }
    private void refreshStatus(){
        try{
            if(!Shizuku.pingBinder()){
                connectionText.setText("Служба Shizuku не запущена или недоступна.");return;
            }
            if(Shizuku.getUid()!=2000){
                connectionText.setText("UID Shizuku не shell (2000). PRO-режим заблокирован.");return;
            }
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){
                connectionText.setText("Shizuku запущен. Разрешение приложения не предоставлено.");return;
            }
            connectionText.setText(remote==null?"Shizuku авторизован. Нажмите «Проверить / авторизовать» для подключения.":"Подключено к Shizuku · готов к обслуживанию");
        }catch(Throwable e){connectionText.setText("Shizuku ещё не подключён: "+e.getClass().getSimpleName());}
    }
    private void connectShizuku(){
        try{
            if(!Shizuku.pingBinder()){alert("Shizuku","Запустите службу Shizuku на телефоне. После перезагрузки может потребоваться повторный запуск.");return;}
            if(Shizuku.getUid()!=2000){alert("Режим доступа","Разрешается только UID shell 2000. Запуск в root-режиме этим релизом не поддерживается.");return;}
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){
                if(Shizuku.shouldShowRequestPermissionRationale()){
                    alert("Shizuku","Доступ был запрещён. Откройте Shizuku и разрешите использование для Android Control Hub.");return;
                }
                Shizuku.requestPermission(REQ_SHIZUKU);
                return;
            }
            if(remote!=null&&remote.isBinderAlive()){refreshStatus();return;}
            serviceArgs=new Shizuku.UserServiceArgs(new ComponentName(this,ProUserService.class))
                .daemon(false).processNameSuffix("pro").tag("ach-pro").version(1);
            Shizuku.bindUserService(serviceArgs,connection);
            connectionText.setText("Запуск Shizuku UserService…");
        }catch(Throwable e){alert("Ошибка Shizuku",e.getClass().getSimpleName()+": "+e.getMessage());}
    }
    private String operationName(){
        return CACHE.equals(action)?"Очистка кэша":DATA.equals(action)?"Полная очистка данных":"Отключение";
    }
    private void preview(){
        ArrayList<App> apps=chosen();
        if(apps.isEmpty()){toast("Выберите хотя бы одно приложение");return;}
        StringBuilder s=new StringBuilder(operationName()+"\n");
        for(App app:apps)s.append("• ").append(app.name).append(" (").append(app.pkg).append(")\n");
        s.append("\nВсе операции выполняются с явным подтверждением. Системные приложения исключены.");
        alert("Предварительный просмотр",s.toString());
    }
    private void begin(){
        if(busy){toast("Дождитесь завершения предыдущей операции");return;}
        ArrayList<App> apps=chosen();
        if(apps.isEmpty()){toast("Не выбраны приложения");return;}
        if(remote==null||!remote.isBinderAlive()){
            alert("Нет Shizuku","Сначала авторизуйте и подключите Shizuku. Автоматическое выполнение без службы невозможно.");return;
        }
        new AlertDialog.Builder(this)
            .setTitle(operationName()+" · "+apps.size()+" приложений")
            .setMessage((DATA.equals(action)?"ПОЛНАЯ ОЧИСТКА НЕОБРАТИМА. Будут потеряны аккаунты, локальные файлы и настройки выбранных программ. Сначала сохраните резервные копии.\n\n":"")+
                "Для каждой программы будет отдельное подтверждение. Операции выполняются только в текущем профиле Android.")
            .setNeutralButton("Сначала создать копию",(d,w)->startActivity(new Intent(this,TransferHubActivity.class)))
            .setNegativeButton("Отмена",null)
            .setPositiveButton(DATA.equals(action)?"Подтвердить личность":"Продолжить",(d,w)->{
                queue=apps;nextIndex=0;
                if(DATA.equals(action))confirmOwner(false);
                else next();
            }).show();
    }
    private void confirmOwner(boolean forExport){
        KeyguardManager guard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(guard==null||!guard.isDeviceSecure()){
            alert("Нужен PIN/пароль","Для полной очистки данных сначала включите блокировку экрана в настройках Android.");return;
        }
        Intent confirm=guard.createConfirmDeviceCredentialIntent(
            "Подтверждение владельца", "Разрешить создание команды полной очистки выбранных приложений");
        if(confirm==null){alert("Ошибка","Система не предоставила экран подтверждения личности.");return;}
        pendingCredential=true;exportPrepared=forExport;
        startActivityForResult(confirm,REQ_CREDENTIAL);
    }
    private void next(){
        if(nextIndex>=queue.size()){toast("Очередь завершена");return;}
        App app=queue.get(nextIndex);
        new AlertDialog.Builder(this)
            .setTitle(operationName()+" · "+(nextIndex+1)+"/"+queue.size())
            .setMessage(app.name+"\n"+app.pkg+"\n\n"+(DATA.equals(action)?
                "ВНИМАНИЕ: удалятся все данные этого приложения.":"Проверяйте результат после выполнения."))
            .setNegativeButton("Остановить",(d,w)->{queue.clear();busy=false;})
            .setNeutralButton("Пропустить",(d,w)->{nextIndex++;next();})
            .setPositiveButton("Выполнить",(d,w)->executeSingle(app))
            .show();
    }
    private void executeSingle(App app){
        IBinder binder=remote;
        if(binder==null||!binder.isBinderAlive()){alert("Shizuku","Соединение потеряно. Очередь остановлена.");queue.clear();return;}
        busy=true;
        String mode=action;
        logText.setText("Выполняется: "+app.pkg+"…");
        new Thread(()->{
            String out;
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{
                data.writeInterfaceToken(ProUserService.DESCRIPTOR);
                data.writeString(mode);data.writeString(app.pkg);
                boolean ok=binder.transact(ProUserService.EXECUTE,data,reply,0);
                if(!ok)throw new IllegalStateException("Нет ответа от Shizuku UserService");
                reply.readException();
                out=reply.readString();
            }catch(Throwable e){out="ОШИБКА: "+e.getClass().getSimpleName()+": "+e.getMessage();}
            finally{data.recycle();reply.recycle();}
            final String result=out==null?"Нет данных от системной службы":out;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                busy=false;
                logText.append("\n"+result+"\n");
                new AlertDialog.Builder(this).setTitle("Результат: "+app.name)
                    .setMessage(result)
                    .setNegativeButton("Остановить",(d,w)->queue.clear())
                    .setPositiveButton("Следующее",(d,w)->{nextIndex++;next();})
                    .show();
            });
        },"ach-pro-single-operation").start();
    }
    private void prepareExport(){
        ArrayList<App> apps=chosen();
        if(apps.isEmpty()){toast("Выберите приложения");return;}
        StringBuilder names=new StringBuilder();
        for(App app:apps)names.append("• ").append(app.pkg).append("\n");
        new AlertDialog.Builder(this)
            .setTitle("Сценарий ADB для Windows")
            .setMessage(operationName()+"\n"+names+"\nСценарий требует USB-отладки, доступного ADB и подтверждения на ПК.\n"+
                (DATA.equals(action)?"ДАННЫЕ ПРИЛОЖЕНИЙ БУДУТ УДАЛЕНЫ БЕЗ ВОЗМОЖНОСТИ ОТМЕНЫ.":""))
            .setNegativeButton("Отмена",null)
            .setPositiveButton(DATA.equals(action)?"Подтвердить владельца":"Сохранить файл",(d,w)->{
                if(DATA.equals(action))confirmOwner(true);
                else chooseExportFile();
            }).show();
    }
    private void chooseExportFile(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TITLE,"AndroidHub-PRO-0.8.cmd");
        try{startActivityForResult(intent,REQ_SAVE_CMD);}
        catch(Exception e){toast("Сохранение сценария недоступно");}
    }
    private String batchScript(){
        ArrayList<App> apps=chosen();
        StringBuilder cmd=new StringBuilder("@echo off\r\nsetlocal EnableExtensions DisableDelayedExpansion\r\n");
        cmd.append("echo Android Control Hub PRO 0.8 - manual approval required\r\n");
        cmd.append("where adb >nul 2>&1 || (echo ERROR: Install Android Platform Tools first & pause & exit /b 1)\r\n");
        cmd.append("adb get-state 2>nul | findstr /C:\"device\" >nul || (echo ERROR: One authorized device required & pause & exit /b 1)\r\n");
        cmd.append("set \"U=\"\r\nfor /f \"delims=\" %%A in ('adb shell am get-current-user 2^>nul') do set \"U=%%A\"\r\n");
        cmd.append("echo %U% | findstr /R /C:\"^[0-9][0-9]*$\" >nul || (echo ERROR: Invalid current Android user & pause & exit /b 1)\r\n");
        if(CACHE.equals(action))
            cmd.append("adb shell pm help | findstr /C:\"--cache-only\" >nul || (echo STOP: Cache-only unsupported. No operation executed & pause & exit /b 1)\r\n");
        cmd.append("echo Current Android user: %U%\r\n");
        cmd.append("choice /C YN /N /M \"Proceed with displayed app list? [Y/N] \"\r\nif errorlevel 2 exit /b 0\r\n");
        for(App app:apps){
            cmd.append("echo.\r\necho Package: "+app.pkg+"\r\n");
            cmd.append("choice /C YN /N /M \"Confirm this application? [Y/N] \"\r\n");
            cmd.append("if errorlevel 2 (echo SKIPPED) else (\r\n");
            cmd.append("adb shell pm ");
            if(CACHE.equals(action))cmd.append("clear --user %U% --cache-only ");
            else if(DATA.equals(action))cmd.append("clear --user %U% ");
            else cmd.append("disable-user --user %U% ");
            cmd.append(app.pkg+"\r\n)\r\n");
        }
        cmd.append("echo.\r\necho Verify every Android command result. Done.\r\npause\r\n");
        return cmd.toString();
    }
    private void openUrl(String url){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception e){toast("Не удалось открыть браузер");}
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code==REQ_CREDENTIAL){
            boolean permitted=pendingCredential && result==RESULT_OK;pendingCredential=false;
            if(!permitted){toast("Подтверждение отменено");queue.clear();return;}
            if(exportPrepared)chooseExportFile();else next();
            return;
        }
        if(code!=REQ_SAVE_CMD||result!=RESULT_OK||data==null||data.getData()==null)return;
        try(OutputStream stream=getContentResolver().openOutputStream(data.getData())){
            if(stream==null)throw new IllegalStateException("Нет файла для записи");
            stream.write(batchScript().getBytes(StandardCharsets.UTF_8));stream.flush();
            alert("ADB-сценарий сохранён","Перенесите .cmd на компьютер с Android Platform Tools и запустите самостоятельно. Сценарий запрашивает подтверждение для каждого приложения. Приложение не выполняло команды.");
        }catch(Exception e){alert("Ошибка записи",e.toString());}
    }
}
