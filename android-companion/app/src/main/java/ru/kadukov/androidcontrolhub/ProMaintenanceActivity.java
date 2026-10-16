package ru.kadukov.androidcontrolhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class ProMaintenanceActivity extends Activity {
    private static final int CREDENTIAL_CHECK=970, SAVE_SCRIPT=971;
    private static final int BG=0xff0a101c,PANEL=0xff16273a,BORDER=0xff30465a;
    private static final int FG=0xffeaf6fd,MUTED=0xffa5bdcd,GREEN=0xff4ae7b2;
    private static final String ACTION_CACHE="CACHE",ACTION_DATA="DATA",ACTION_DISABLE="DISABLE";
    private final ArrayList<Item> inventory=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private LinearLayout root, list;
    private TextView chosenSummary, privilegeStatus;
    private String filter="";
    private String plannedAction=ACTION_CACHE;
    private String exportScript;
    private boolean windows=true;
    private boolean pendingCredential=false;

    private static final class Item {
        String pkg,label;
        Item(String p,String l){pkg=p;label=l;}
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);
        d.setCornerRadius(dp(13));d.setStroke(dp(1),BORDER);return d;
    }
    private TextView txt(String s,int size,int color,boolean strong){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(strong)t.setTypeface(null,Typeface.BOLD);t.setLineSpacing(dp(2),1f);return t;
    }
    private LinearLayout stack(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout.LayoutParams margin(int n){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(n);return p;
    }
    private void line(LinearLayout p,String s,int size,int color,boolean strong,int bottom){
        p.addView(txt(s,size,color,strong),margin(bottom));
    }
    private LinearLayout block(String title,String summary){
        LinearLayout b=stack();b.setPadding(dp(15),dp(14),dp(15),dp(15));
        b.setBackground(bg(PANEL));root.addView(b,margin(14));
        line(b,title,16,FG,true,6);
        if(summary!=null)line(b,summary,12,MUTED,false,10);
        return b;
    }
    private void action(LinearLayout box,String title,boolean primary,Runnable click){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextSize(12);
        b.setTextColor(primary?0xff083729:FG);
        b.setBackground(bg(primary?GREEN:0xff243c51));
        b.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams p=margin(8);p.height=dp(48);box.addView(b,p);
    }
    private void info(String title,String body){
        new AlertDialog.Builder(this).setTitle(title).setMessage(body)
            .setPositiveButton("Закрыть",null).show();
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        root=stack();root.setPadding(dp(15),dp(18),dp(15),dp(32));scroll.addView(root);
        setContentView(scroll);
        line(root,"PRO · сервисный режим",23,GREEN,true,7);
        line(root,"Android Control Hub 0.8 · действия только на устройстве владельца. Подтверждение и журнал обязательны.",12,MUTED,false,16);

        LinearLayout connection=block("01 · Режимы доступа",
            "ПК/ADB: готовый командный файл для Windows. Shizuku: проверка связи и разрешения; запуск команд внутри APK пока не реализован.");
        privilegeStatus=txt("Статус Shizuku: проверка…",12,FG,false);
        connection.addView(privilegeStatus,margin(10));
        action(connection,"Проверить подключение Shizuku",true,this::checkShizuku);
        action(connection,"Запросить разрешение Shizuku",false,this::requestShizukuPermission);
        action(connection,"Открыть инструкции Shizuku",false,()->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")));}
            catch(Exception e){toast("Браузер недоступен");}
        });
        line(connection,"ПК/ADB работает после подтверждения USB-отладки на телефоне. Скрипт не запускается самим APK; его выполняет владелец на своём ПК.",11,MUTED,false,4);

        LinearLayout targets=block("02 · Приложения",
            "Выберите пользовательские программы. Системные пакеты и сам Android Control Hub исключены из списка.");
        EditText query=new EditText(this);
        query.setTextColor(FG);query.setHintTextColor(MUTED);query.setSingleLine(true);
        query.setHint("Название или идентификатор пакета");query.setTextSize(13);
        query.setPadding(dp(12),dp(9),dp(12),dp(9));query.setBackground(bg(0xff0c1c2b));
        targets.addView(query,margin(12));
        chosenSummary=txt("Ничего не выбрано",12,GREEN,true);targets.addView(chosenSummary,margin(9));
        list=stack();targets.addView(list);
        query.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){filter=s.toString().toLowerCase(Locale.ROOT).trim();drawList();}
            public void afterTextChanged(Editable e){}
        });

        LinearLayout command=block("03 · План обслуживания",
            "Выбирайте тип операции. Кэш и пользовательские данные — разные действия. Полная очистка удаляет авторизацию и личные данные внутри приложения.");
        Spinner choices=new Spinner(this);
        String[] names={"Только кэш (при поддержке --cache-only)","Все данные приложения — необратимо","Отключить выбранные приложения"};
        choices.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        command.addView(choices,margin(10));
        choices.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                plannedAction=pos==0?ACTION_CACHE:pos==1?ACTION_DATA:ACTION_DISABLE;
            }
        });
        action(command,"Предварительный просмотр команд",true,this::previewPlan);
        action(command,"Создать ADB-скрипт для Windows",false,()->prepareExport(true));
        action(command,"Создать сценарий для Shizuku rish",false,()->prepareExport(false));
        action(command,"Сохранить личные файлы до очистки",false,()->startActivity(new Intent(this,PersonalFilesActivity.class)));

        LinearLayout notes=block("04 · Ограничения и безопасность",
            "Ни один скрипт не запускается тайно. PIN-код проверяет Android, а не наша программа.");
        line(notes,"• Очистка только кэша возможна лишь при подтверждённой поддержке --cache-only.",12,MUTED,false,7);
        line(notes,"• Данные приложений удаляются только после отдельного предупреждения, проверки PIN/пароля устройства и подтверждений в сценарии.",12,MUTED,false,7);
        line(notes,"• Код не принимает произвольные команды и исключает системные пакеты. Сценарий требует вашего ручного запуска через ADB или Shizuku rish.",12,MUTED,false,7);
        line(notes,"• Биометрия/PIN не выдаёт root и не снимает ограничения Android. Перед удалением обязательно проверьте резервные копии.",12,MUTED,false,3);
        loadApps();
        checkShizuku();
    }

    private static boolean validPackage(String p){
        return p!=null && p.length()<180&&p.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }
    private boolean safeTarget(String pkg){
        return validPackage(pkg)&&!pkg.equals(getPackageName())&&
            !pkg.startsWith("com.android.")&&!pkg.startsWith("com.google.android.")&&
            !pkg.startsWith("com.oplus.")&&!pkg.startsWith("com.oneplus.")&&
            !pkg.startsWith("com.qualcomm.")&&!pkg.startsWith("com.mediatek.");
    }
    private void loadApps(){
        PackageManager pm=getPackageManager();
        inventory.clear();
        for(PackageInfo info:pm.getInstalledPackages(0)){
            ApplicationInfo app=info.applicationInfo;
            if(app==null||((app.flags&ApplicationInfo.FLAG_SYSTEM)!=0)||!safeTarget(info.packageName))continue;
            inventory.add(new Item(info.packageName,String.valueOf(pm.getApplicationLabel(app))));
        }
        inventory.sort(Comparator.comparing(v->v.label.toLowerCase(Locale.ROOT)));
        drawList();
    }
    private void drawList(){
        if(list==null)return;list.removeAllViews();
        int n=0,total=0;
        for(Item item:inventory){
            if(!item.label.toLowerCase(Locale.ROOT).contains(filter)&&!item.pkg.toLowerCase(Locale.ROOT).contains(filter))continue;
            total++;if(++n>110)continue;
            CheckBox box=new CheckBox(this);
            box.setText(item.label+"\n"+item.pkg);box.setTextColor(FG);box.setTextSize(12);
            box.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
            box.setChecked(selected.contains(item.pkg));
            box.setPadding(dp(2),dp(4),dp(2),dp(6));
            box.setOnCheckedChangeListener((b,checked)->{
                if(checked)selected.add(item.pkg);else selected.remove(item.pkg);
                updateSummary();
            });
            list.addView(box,margin(4));
        }
        if(total>110)line(list,"Показано 110 из "+total+". Для остальных используйте поиск.",12,MUTED,false,4);
        updateSummary();
    }
    private void updateSummary(){
        if(chosenSummary!=null)chosenSummary.setText("Отмечено: "+selected.size()+" · доступно пользовательских: "+inventory.size());
    }
    private ArrayList<String> chosen(){
        ArrayList<String> items=new ArrayList<>();
        for(Item x:inventory)if(selected.contains(x.pkg)&&safeTarget(x.pkg))items.add(x.pkg);
        Collections.sort(items);
        return items;
    }
    private String plan(ArrayList<String> pkgs){
        StringBuilder s=new StringBuilder();
        s.append("Режим: ").append(plannedAction).append("\nВыбрано пакетов: ").append(pkgs.size())
            .append("\nПользователь Android определяется при запуске сценария.\n\n");
        for(String p:pkgs){
            s.append("adb shell pm ");
            if(ACTION_DISABLE.equals(plannedAction))s.append("disable-user --user [CURRENT] ");
            else if(ACTION_CACHE.equals(plannedAction))s.append("clear --user [CURRENT] --cache-only ");
            else s.append("clear --user [CURRENT] ");
            s.append(p).append('\n');
        }
        return s.toString();
    }
    private void previewPlan(){
        ArrayList<String> targets=chosen();
        if(targets.isEmpty()){toast("Сначала отметьте хотя бы одно приложение");return;}
        new AlertDialog.Builder(this).setTitle("Предпросмотр без выполнения")
            .setMessage(plan(targets)+"\nНи одна команда не выполняется при просмотре. ")
            .setPositiveButton("Закрыть",null).show();
    }
    private void prepareExport(boolean forWindows){
        ArrayList<String> list=chosen();
        if(list.isEmpty()){toast("Выберите приложения для обслуживания");return;}
        windows=forWindows;
        String caption=forWindows?"ADB-скрипт Windows":"Сценарий для Shizuku rish";
        new AlertDialog.Builder(this)
            .setTitle("Экспорт: "+caption)
            .setMessage(plan(list)+(ACTION_DATA.equals(plannedAction)?
                "\nВНИМАНИЕ! Эта операция удаляет данные приложений, включая локальные аккаунты, настройки и файлы. Восстановление не гарантируется.":
                "\nПеред запуском убедитесь, что выбраны нужные приложения.")+
                "\nФайл будет создан только после подтверждения. Команды не запускаются из APK.")
            .setNeutralButton("Сначала резервная копия",(d,w)->startActivity(new Intent(this,PersonalFilesActivity.class)))
            .setNegativeButton("Отмена",null)
            .setPositiveButton(ACTION_DATA.equals(plannedAction)?"Подтвердить личность":"Создать файл",(d,w)->{
                if(ACTION_DATA.equals(plannedAction))confirmCredential();
                else chooseFile();
            }).show();
    }
    private void confirmCredential(){
        KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(keyguard==null||!keyguard.isDeviceSecure()){
            info("Нужна защита экрана","Для генерации сценария полной очистки сначала настройте PIN-код/пароль или графический ключ Android.");return;
        }
        try{
            Intent confirm=keyguard.createConfirmDeviceCredentialIntent(
                "Полная очистка данных","Подтвердите личность, чтобы создать сценарий удаления данных выбранных приложений.");
            if(confirm==null){info("Защита недоступна","Android не предоставил системный экран подтверждения личности.");return;}
            pendingCredential=true;
            startActivityForResult(confirm,CREDENTIAL_CHECK);
        }catch(Exception e){toast("Ошибка проверки личности: "+e.getClass().getSimpleName());}
    }
    private void chooseFile(){
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("text/plain");
        save.putExtra(Intent.EXTRA_TITLE,windows?"AndroidHub-ADB-0.8.cmd":"AndroidHub-rish-0.8.sh");
        try{startActivityForResult(save,SAVE_SCRIPT);}
        catch(Exception e){toast("Сохранение файла не поддерживается");}
    }
    private String windowsScript(ArrayList<String> items) {
        StringBuilder b=new StringBuilder();
        b.append("@echo off\r\nsetlocal EnableExtensions DisableDelayedExpansion\r\n");
        b.append("title Android Control Hub PRO - 0.8\r\n");
        b.append("echo Android Control Hub PRO - control selected packages only.\r\n");
        b.append("echo Ensure USB debugging was approved on the phone.\r\n");
        b.append("where adb >nul 2>&1 || (echo ERROR: adb is not installed. & pause & exit /b 1)\r\n");
        b.append("adb get-state 2>nul | findstr /R /C:\"^device$\" >nul || (echo ERROR: connect ONE authorized Android device. & pause & exit /b 1)\r\n");
        b.append("set \"U=\"\r\n");
        b.append("for /f \"delims=\" %%A in ('adb shell am get-current-user 2^>nul') do set \"U=%%A\"\r\n");
        b.append("if not defined U (echo ERROR: user not detected. & pause & exit /b 1)\r\n");
        b.append("echo %U% | findstr /R /C:\"^[0-9][0-9]*$\" >nul || (echo ERROR: invalid user. & pause & exit /b 1)\r\n");
        if(ACTION_CACHE.equals(plannedAction)){
            b.append("adb shell pm help 2>&1 | findstr /C:\"--cache-only\" >nul || (echo STOP: --cache-only NOT supported. No action taken. & pause & exit /b 1)\r\n");
        }
        b.append("echo TARGET USER: %U%\r\n");
        b.append("echo Selected packages: "+items.size()+"\r\n");
        b.append("choice /C YN /N /M \"Run reviewed actions on this phone? Y/N \"\r\n");
        b.append("if errorlevel 2 exit /b 0\r\n");
        for(String pkg:items){
            b.append("echo.\r\necho Package: "+pkg+"\r\n");
            if(!ACTION_CACHE.equals(plannedAction)){
                b.append("choice /C YN /N /M \"Confirm ").append(ACTION_DATA.equals(plannedAction)?"DELETE DATA":"DISABLE")
                  .append(" for this package? Y/N \"\r\n");
                b.append("if errorlevel 2 (echo SKIPPED: "+pkg+") else (\r\n");
            }
            b.append("adb shell pm ");
            if(ACTION_DISABLE.equals(plannedAction))b.append("disable-user --user %U% ");
            else if(ACTION_CACHE.equals(plannedAction))b.append("clear --user %U% --cache-only ");
            else b.append("clear --user %U% ");
            b.append(pkg).append("\r\n");
            b.append("echo Above is Android output; verify before continuing.\r\n");
            if(!ACTION_CACHE.equals(plannedAction))b.append(")\r\n");
        }
        b.append("echo.\r\necho Done. Check Android output for each package; no automated success claim.\r\npause\r\n");
        return b.toString();
    }
    private String rishScript(ArrayList<String> items){
        StringBuilder b=new StringBuilder();
        b.append("#!/system/bin/sh\n");
        b.append("# Android Control Hub 0.8: execute ONLY in an authorized Shizuku rish shell.\n");
        b.append("# Running with normal Termux sh does not grant shell privileges.\n");
        b.append("U=\"$(am get-current-user 2>/dev/null)\"\n");
        b.append("case \"$U\" in ''|*[!0-9]*) echo 'ERROR: unable to determine user'; exit 1;; esac\n");
        if(ACTION_CACHE.equals(plannedAction)){
            b.append("pm help 2>&1 | grep -F -- '--cache-only' >/dev/null || { echo 'STOP: cache-only unsupported'; exit 1; }\n");
        }
        b.append("echo 'Selected packages: "+items.size()+"'\n");
        b.append("printf 'Run reviewed maintenance? y/N: '; read reply\n");
        b.append("case \"$reply\" in y|Y) ;; *) exit 0;; esac\n");
        for(String pkg:items){
            b.append("echo '\\nPackage: "+pkg+"'\n");
            if(!ACTION_CACHE.equals(plannedAction)){
                b.append("printf 'Confirm "+(ACTION_DATA.equals(plannedAction)?"DELETE DATA":"DISABLE")+" "+pkg+"? y/N: '; read reply\n");
                b.append("case \"$reply\" in y|Y) ");
            }
            b.append("pm ");
            if(ACTION_DISABLE.equals(plannedAction))b.append("disable-user --user \"$U\" ");
            else if(ACTION_CACHE.equals(plannedAction))b.append("clear --user \"$U\" --cache-only ");
            else b.append("clear --user \"$U\" ");
            b.append(pkg);
            if(!ACTION_CACHE.equals(plannedAction))b.append(" ;; *) echo 'SKIPPED' ;; esac");
            b.append("\n");
        }
        b.append("echo 'Finished. Check each command output.'\n");
        return b.toString();
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code==CREDENTIAL_CHECK){
            boolean ok=result==RESULT_OK&&pendingCredential;
            pendingCredential=false;
            if(ok)chooseFile();
            else toast("Подтверждение личности отменено. Файл не создавался.");
            return;
        }
        if(code!=SAVE_SCRIPT||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        ArrayList<String> packages=chosen();
        if(packages.isEmpty()){toast("Нет выбранных приложений");return;}
        String source=windows?windowsScript(packages):rishScript(packages);
        try(OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null)throw new IllegalStateException("Нет доступа к файлу");
            out.write(source.getBytes(StandardCharsets.UTF_8));out.flush();
            info("Файл успешно создан",
                "Сценарий содержит "+packages.size()+" проверенных пакетов.\n\n"+
                (windows?"Запустите его на личном ПК с установленным ADB, после подтверждения отладки на телефоне.":
                "Запускайте только внутри авторизованной Shizuku rish оболочки; обычный терминал не имеет нужных полномочий.")+
                "\n\nОперации не выполнены этим приложением.");
        }catch(Exception e){toast("Ошибка записи: "+e.getMessage());}
    }
    private void checkShizuku(){
        try{
            boolean active=rikka.shizuku.Shizuku.pingBinder();
            String val=active?(rikka.shizuku.Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED?
                "Shizuku доступен, разрешение выдано. Прямое выполнение из APK ещё не реализовано.":
                "Служба Shizuku есть, но разрешение не выдано."):
                "Нет соединения с Shizuku. Установите и запустите его.";
            if(privilegeStatus!=null)privilegeStatus.setText(val);
        }catch(Throwable e){
            if(privilegeStatus!=null)privilegeStatus.setText("Shizuku не подключён или сервис ещё не запущен.");
        }
    }
    private void requestShizukuPermission(){
        try{
            if(!rikka.shizuku.Shizuku.pingBinder()){
                info("Shizuku не запущен","Установите Shizuku и запустите службу согласно официальной инструкции. Затем вернитесь и запросите разрешение.");return;
            }
            if(rikka.shizuku.Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){
                info("Доступ Shizuku","Разрешение уже выдано. В 0.8 доступны экспорт сценариев для ADB и rish; прямой исполнитель в APK пока не подключён.");return;
            }
            rikka.shizuku.Shizuku.requestPermission(998);
            toast("Подтвердите разрешение в системном окне Shizuku.");
        }catch(Throwable e){toast("Shizuku: "+e.getClass().getSimpleName());}
    }
}
