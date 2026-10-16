package ru.kadukov.androidcontrolhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
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
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Local, user-driven transfer and backup.
 * No app private data or credentials are read; no server component, auto-install,
 * network discovery, background transfer or broad external-storage permission.
 */
public class TransferHubActivity extends Activity {
    private static final int PICK_FILES=820, SAVE_ZIP=821, OPEN_ZIP=822, RESTORE_FOLDER=823, SAVE_APK=824;
    private static final int BG=0xff0a101c, PANEL=0xff152438, BORDER=0xff2b485e, GREEN=0xff49e6b2;
    private static final int FG=0xfff1f8fe, DIM=0xffa1bbcd;
    private final ArrayList<Uri> selectedFiles=new ArrayList<>();
    private final ArrayList<String[]> restoredApps=new ArrayList<>();
    private LinearLayout root;
    private TextView summary;
    private Uri lastZip, importedZip;
    private String exportApkPackage;
    private boolean working=false;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int fill){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(14));d.setStroke(dp(1),BORDER);return d;
    }
    private LinearLayout stack(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout.LayoutParams gap(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(n);return p;}
    private TextView label(String t,int sp,int color,boolean strong){
        TextView v=new TextView(this);v.setText(t);v.setTextSize(sp);v.setTextColor(color);
        if(strong)v.setTypeface(null,Typeface.BOLD);v.setLineSpacing(dp(2),1);return v;
    }
    private void line(LinearLayout v,String t,int sp,int color,boolean strong,int b){v.addView(label(t,sp,color,strong),gap(b));}
    private LinearLayout card(String title,String subtitle){
        LinearLayout l=stack();l.setPadding(dp(16),dp(15),dp(16),dp(15));l.setBackground(bg(PANEL));
        root.addView(l,gap(13));line(l,title,16,FG,true,5);line(l,subtitle,12,DIM,false,10);return l;
    }
    private void action(LinearLayout parent,String title,boolean primary,Runnable click){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(12);
        b.setTextColor(primary?0xff09362d:FG);b.setBackground(bg(primary?GREEN:0xff243c51));
        b.setOnClickListener(v->click.run());LinearLayout.LayoutParams p=gap(9);p.height=dp(46);parent.addView(b,p);
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void start(Intent i){try{startActivity(i);}catch(Exception e){toast("Нет приложения для этого действия");}}
    private void browse(String link){start(new Intent(Intent.ACTION_VIEW,Uri.parse(link)));}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);root=stack();
        root.setPadding(dp(16),dp(19),dp(16),dp(35));scroll.addView(root);setContentView(scroll);
        line(root,"Перенос и резервное копирование",22,GREEN,true,8);
        line(root,"Android Control Hub 0.7 · данные остаются на телефоне, пока вы сами не выберете сохранение или передачу.",12,DIM,false,16);
        LinearLayout transport=card("01  Компьютер и второй телефон",
            "Через Quick Share в системном меню Android: передача на соседний телефон или ПК с установленным Quick Share. USB-кабель работает через режим «Передача файлов» Android.");
        action(transport,"Выбрать файлы для передачи",true,this::chooseFiles);
        summary=label("Файлы не выбраны",12,DIM,false);transport.addView(summary,gap(10));
        action(transport,"Поделиться выбранными файлами",true,this::shareSelected);
        action(transport,"Отправить последний ZIP-архив",false,()->{
            if(lastZip==null){toast("Сначала создайте ZIP-архив");return;}
            shareUri(lastZip,"application/zip","Передача резервной копии");
        });
        action(transport,"Quick Share для Windows — инструкция",false,()->browse("https://support.google.com/android/answer/13801258?hl=ru"));
        line(transport,"USB: подключите телефон кабелем → раскройте уведомление USB → выберите «Передача файлов» (MTP) → откройте память телефона на ПК. Прямое управление файлами ПК из обычного APK не выполняется.",11,DIM,false,2);

        LinearLayout archive=card("02  Архив выбранных данных",
            "Создаёт настоящий ZIP: выбранные фотографии, документы и другие доступные файлы + список установленных пользовательских приложений (имена, пакеты, версии).");
        action(archive,"Создать ZIP для переноса / восстановления",true,this::confirmZip);
        line(archive,"Внимание: архив НЕ зашифрован. Он не содержит пароли, SMS, приватные базы и настройки других приложений. Не отправляйте конфиденциальные файлы незнакомым получателям.",11,0xffffc997,false,5);

        LinearLayout importCard=card("03  Восстановление из ZIP",
            "Выберите созданный Android Control Hub архив. Файлы можно вернуть в выбранную папку; список приложений откроется для переустановки через Google Play.");
        action(importCard,"Открыть ZIP-архив",true,this::openArchive);
        action(importCard,"Восстановить документы в папку",false,this::chooseRestoreFolder);
        action(importCard,"Посмотреть приложения для переустановки",false,this::showRestoredApps);
        line(importCard,"Ни одно приложение не устанавливается автоматически. Восстанавливаются только выбранные файлы, а не защищённые данные /data/data.",11,DIM,false,4);

        LinearLayout apps=card("04  Поделиться приложением",
            "Отправьте ссылку на приложение из Google Play. Для приложений без split APK можно попытаться экспортировать базовый APK, если Android разрешит чтение файла.");
        action(apps,"Поделиться ссылкой на приложение",true,()->chooseApp(false));
        action(apps,"Экспортировать APK установленного приложения",false,()->chooseApp(true));
        line(apps,"APK не содержит личные данные и пароли. Для split APKs отдельного base.apk часто недостаточно для установки; для таких программ используйте Play Store / системный перенос.",11,DIM,false,3);

        LinearLayout backup=card("05  Перенос с паролями и данными",
            "Полную копию всех приложений, их паролей, токенов и закрытых файлов сторонняя утилита не может сделать. Для полного переноса используйте штатные резервные копии Android и инструменты производителя.");
        action(backup,"Настройка резервной копии Android (справка)",true,()->browse("https://support.google.com/android/answer/2819582?hl=ru"));
        action(backup,"Менеджер паролей Google",false,()->browse("https://passwords.google.com/"));
        action(backup,"Открыть настройки Android",false,()->start(new Intent(android.provider.Settings.ACTION_SETTINGS)));
        line(backup,"Google может восстановить часть приложений, данных, настроек и сохранённых паролей после входа в ваш аккаунт; это зависит от каждой программы и версии Android. Android Control Hub не читает пароли и не экспортирует их.",11,DIM,false,3);
    }
    private String fileName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()){String n=c.getString(0);if(n!=null&&!n.isEmpty())return n;}
        }catch(Exception ignored){}
        return "document";
    }
    private String safeName(String src){
        String x=src==null?"file":src.replaceAll("[^A-Za-z0-9А-Яа-яЁё_.() -]","_");
        while(x.startsWith("."))x=x.substring(1);
        if(x.isEmpty())x="file";
        return x.length()>100?x.substring(0,100):x;
    }
    private void chooseFiles(){
        if(working)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivityForResult(i,PICK_FILES);}catch(Exception e){toast("Файловый менеджер недоступен");}
    }
    private void showFiles(){
        StringBuilder s=new StringBuilder("Выбрано файлов: "+selectedFiles.size());
        for(int i=0;i<Math.min(5,selectedFiles.size());i++)s.append("\n• ").append(fileName(selectedFiles.get(i)));
        if(selectedFiles.size()>5)s.append("\n…ещё ").append(selectedFiles.size()-5);
        summary.setText(s);
    }
    private void shareSelected(){
        if(selectedFiles.isEmpty()){toast("Сначала выберите файлы");return;}
        Intent i;
        if(selectedFiles.size()==1){
            i=new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM,selectedFiles.get(0));
        }else{
            i=new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM,new ArrayList<>(selectedFiles));
        }
        i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip=null;
        for(Uri file:selectedFiles){
            if(clip==null)clip=ClipData.newUri(getContentResolver(),"Файлы",file);
            else clip.addItem(new ClipData.Item(file));
        }
        i.setClipData(clip);
        start(Intent.createChooser(i,"Quick Share, Bluetooth и другие способы"));
    }
    private void shareUri(Uri uri,String type,String title){
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType(type);
        send.putExtra(Intent.EXTRA_STREAM,uri);
        send.setClipData(ClipData.newUri(getContentResolver(),title,uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        start(Intent.createChooser(send,title));
    }
    private void confirmZip(){
        if(working)return;
        new AlertDialog.Builder(this)
            .setTitle("Создать резервный ZIP")
            .setMessage("В архив войдут "+selectedFiles.size()+" выбранных файлов и перечень установленных пользовательских приложений.\n\nЭто НЕ полный образ Android: аккаунты, пароли и закрытые данные чужих приложений недоступны.\n\nАрхив создаётся без шифрования. Выберите безопасное место хранения.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Выбрать место сохранения",(d,w)->{
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                String date=new SimpleDateFormat("yyyy-MM-dd_HHmm",Locale.getDefault()).format(new Date());
                i.putExtra(Intent.EXTRA_TITLE,"AndroidHub-backup-"+date+".zip");
                try{startActivityForResult(i,SAVE_ZIP);}catch(Exception e){toast("Создание файла недоступно");}
            }).show();
    }
    private JSONObject inventoryJson()throws Exception {
        JSONObject root=new JSONObject();
        root.put("format","android-control-hub-portable-v1");
        root.put("createdAt",new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).format(new Date()));
        root.put("androidVersion",Build.VERSION.RELEASE);
        root.put("deviceModel",Build.MODEL);
        root.put("limitations","Installed app list and manually selected files only. No app-private data, passwords, secrets or credentials.");
        JSONArray apps=new JSONArray();
        PackageManager pm=getPackageManager();
        for(PackageInfo p:pm.getInstalledPackages(0)){
            if(p.applicationInfo==null||(p.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0)continue;
            if(p.packageName.equals(getPackageName()))continue;
            JSONObject a=new JSONObject();
            a.put("package",p.packageName);
            a.put("label",String.valueOf(pm.getApplicationLabel(p.applicationInfo)));
            a.put("versionName",p.versionName==null?"":p.versionName);
            apps.put(a);
        }
        root.put("apps",apps);
        return root;
    }
    private void saveZip(Uri dest){
        if(working)return;
        working=true;
        ArrayList<Uri> source=new ArrayList<>(selectedFiles);
        toast("Создаётся ZIP. Дождитесь окончания операции.");
        new Thread(()->{
            int saved=0;String error=null;
            try(OutputStream out=getContentResolver().openOutputStream(dest);
                ZipOutputStream zip=new ZipOutputStream(out)){
                if(out==null)throw new IllegalStateException("Нет доступа к файлу");
                zip.putNextEntry(new ZipEntry("apps.json"));
                zip.write(inventoryJson().toString(2).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                byte[] buffer=new byte[32768];
                int serial=0;
                for(Uri uri:source){
                    serial++;
                    String name=String.format(Locale.US,"%04d_",serial)+safeName(fileName(uri));
                    try(InputStream in=getContentResolver().openInputStream(uri)){
                        if(in==null)throw new IllegalStateException("Не удалось открыть файл: "+name);
                        zip.putNextEntry(new ZipEntry("files/"+name));
                        int n;
                        while((n=in.read(buffer))!=-1)zip.write(buffer,0,n);
                        zip.closeEntry();saved++;
                    }
                }
            }catch(Exception e){error=e.getClass().getSimpleName()+": "+e.getMessage();}
            final int num=saved;final String msg=error;
            runOnUiThread(()->{
                working=false;
                if(isFinishing()||isDestroyed())return;
                if(msg!=null){
                    lastZip=null;
                    new AlertDialog.Builder(this).setTitle("ZIP создан с ошибкой")
                        .setMessage(msg+"\n\nПолученный файл может быть неполным и не должен использоваться для восстановления.")
                        .setPositiveButton("Закрыть",null).show();
                }else{
                    lastZip=dest;
                    new AlertDialog.Builder(this).setTitle("Архив сохранён")
                        .setMessage("Сохранено документов: "+num+"\nСписок установленных приложений добавлен.\n\nПароли и закрытые данные программ НЕ включены. Проверьте файл перед удалением данных с телефона.")
                        .setPositiveButton("Поделиться",(d,w)->shareUri(dest,"application/zip","Передача резервной копии"))
                        .setNegativeButton("Закрыть",null).show();
                }
            });
        },"ach-zip-backup").start();
    }
    private void openArchive(){
        if(working)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivityForResult(i,OPEN_ZIP);}catch(Exception e){toast("Выбор ZIP недоступен");}
    }
    private void parseArchive(Uri uri){
        if(working)return;
        working=true;
        new Thread(()->{
            final ArrayList<String[]> parsed=new ArrayList<>();
            String problem=null;boolean markerFound=false;
            try(InputStream in=getContentResolver().openInputStream(uri);
                ZipInputStream zip=new ZipInputStream(in)){
                if(in==null)throw new IllegalStateException("Нет доступа к ZIP");
                ZipEntry e;
                while((e=zip.getNextEntry())!=null){
                    if("apps.json".equals(e.getName())){
                        markerFound=true;
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        byte[] buf=new byte[8192];int n;
                        while((n=zip.read(buf))!=-1){
                            if(bytes.size()+n>1048576)throw new IllegalArgumentException("Файл apps.json превышает 1 МБ");
                            bytes.write(buf,0,n);
                        }
                        JSONObject data=new JSONObject(bytes.toString("UTF-8"));
                        if(!"android-control-hub-portable-v1".equals(data.optString("format")))
                            throw new IllegalArgumentException("Неподдерживаемый формат архивной копии");
                        JSONArray apps=data.optJSONArray("apps");
                        if(apps!=null)for(int i=0;i<apps.length()&&i<600;i++){
                            JSONObject a=apps.optJSONObject(i);if(a==null)continue;
                            String pkg=a.optString("package");
                            if(pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
                                parsed.add(new String[]{a.optString("label",pkg),pkg});
                        }
                        break;
                    }
                    zip.closeEntry();
                }
                if(!markerFound)throw new IllegalArgumentException("В ZIP нет apps.json формата Android Control Hub");
            }catch(Exception ex){problem=ex.getMessage();}
            final String error=problem;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                working=false;
                if(error!=null){
                    importedZip=null;restoredApps.clear();toast("Ошибка архива: "+error);
                }else{
                    importedZip=uri;restoredApps.clear();restoredApps.addAll(parsed);
                    new AlertDialog.Builder(this).setTitle("Резервная копия открыта")
                        .setMessage("Записей приложений: "+restoredApps.size()+
                            "\n\nМожно отдельно восстановить доступные документы или открыть список приложений для переустановки. Закрытые данные и пароли в архиве отсутствуют.")
                        .setPositiveButton("Список приложений",(d,w)->showRestoredApps())
                        .setNegativeButton("Закрыть",null).show();
                }
            });
        },"ach-zip-parse").start();
    }
    private void chooseRestoreFolder(){
        if(importedZip==null){toast("Сначала откройте ZIP-архив");return;}
        if(working)return;
        new AlertDialog.Builder(this).setTitle("Восстановление документов")
            .setMessage("Выберите отдельную папку. Из архива будут сохранены только файлы в разделе files/. Не будут устанавливаться приложения и не будут перезаписываться закрытые данные.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Выбрать папку",(d,w)->{
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try{startActivityForResult(i,RESTORE_FOLDER);}catch(Exception e){toast("Выбор папки недоступен");}
            }).show();
    }
    private void restore(Uri tree){
        if(working)return;
        working=true;
        Uri archive=importedZip;
        new Thread(()->{
            int saved=0,fail=0;String problem=null;long totalBytes=0;
            try(InputStream in=getContentResolver().openInputStream(archive);
                ZipInputStream zip=new ZipInputStream(in)){
                if(in==null)throw new IllegalStateException("Невозможно открыть ZIP");
                Uri dest=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                byte[] b=new byte[32768];ZipEntry e;int entries=0;
                while((e=zip.getNextEntry())!=null){
                    entries++;if(entries>2000)throw new IllegalArgumentException("Слишком много файлов");
                    String entry=e.getName();
                    if(e.isDirectory()||!entry.startsWith("files/")){zip.closeEntry();continue;}
                    String leaf=entry.substring("files/".length());
                    if(leaf.isEmpty()||leaf.contains("/")||leaf.contains("\\")||leaf.equals(".")||leaf.equals("..")){
                        fail++;zip.closeEntry();continue;
                    }
                    Uri created=null;
                    try{
                        created=DocumentsContract.createDocument(getContentResolver(),dest,"application/octet-stream",safeName(leaf));
                        if(created==null)throw new IllegalStateException("Невозможно создать файл");
                        try(OutputStream os=getContentResolver().openOutputStream(created)){
                            if(os==null)throw new IllegalStateException("Нет потока записи");
                            int n;long oneFile=0;
                            while((n=zip.read(b))!=-1){
                                oneFile+=n;totalBytes+=n;
                                if(oneFile>2147483648L||totalBytes>8589934592L)
                                    throw new IllegalArgumentException("Превышен безопасный объём восстановления");
                                os.write(b,0,n);
                            }
                            os.flush();
                        }
                        saved++;
                    }catch(Exception ex){
                        fail++;
                        if(created!=null)try{DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}
                        if(totalBytes>8589934592L)throw ex;
                    }
                    zip.closeEntry();
                }
            }catch(Exception ex){problem=ex.getMessage();}
            final int count=saved,errors=fail;final String failed=problem;
            runOnUiThread(()->{
                working=false;
                if(isFinishing()||isDestroyed())return;
                new AlertDialog.Builder(this).setTitle("Восстановление документов")
                    .setMessage("Восстановлено: "+count+"\nОшибок отдельных файлов: "+errors+
                        (failed!=null?"\nОшибка: "+failed:"")+
                        "\n\nПриложения и их личные данные не устанавливались. Проверьте документы перед удалением оригиналов.")
                    .setPositiveButton("Закрыть",null).show();
            });
        },"ach-zip-restore").start();
    }
    private void showRestoredApps(){
        if(restoredApps.isEmpty()){toast("Сначала откройте ZIP с перечнем программ");return;}
        ArrayList<String[]> apps=new ArrayList<>(restoredApps);
        apps.sort(Comparator.comparing(a->a[0].toLowerCase(Locale.ROOT)));
        String[] items=new String[Math.min(180,apps.size())];
        for(int i=0;i<items.length;i++)items[i]=apps.get(i)[0]+" ("+apps.get(i)[1]+")";
        new AlertDialog.Builder(this).setTitle("Приложения из архива ("+apps.size()+")")
            .setMessage("Откройте любую запись, чтобы установить соответствующее приложение из Google Play с подтверждением. Данные и пароли программ в архиве отсутствуют.")
            .setPositiveButton("Выбрать",(d,w)->new AlertDialog.Builder(this)
                .setTitle("Выберите программу")
                .setItems(items,(dialog,pos)->openAppStore(apps.get(pos)[1]))
                .setNegativeButton("Закрыть",null).show())
            .setNegativeButton("Закрыть",null).show();
    }
    private void openAppStore(String pkg){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id="+Uri.encode(pkg))));}
        catch(Exception e){browse("https://play.google.com/store/apps/details?id="+Uri.encode(pkg));}
    }
    private void chooseApp(boolean apk){
        if(working)return;
        PackageManager pm=getPackageManager();
        ArrayList<String[]> apps=new ArrayList<>();
        Intent launcher=new Intent(Intent.ACTION_MAIN);launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        HashSet<String> seen=new HashSet<>();
        for(android.content.pm.ResolveInfo info:pm.queryIntentActivities(launcher,0)){
            if(info.activityInfo==null)continue;
            String pkg=info.activityInfo.packageName;
            if(pkg==null||!seen.add(pkg))continue;
            String title=String.valueOf(info.loadLabel(pm));
            apps.add(new String[]{title,pkg});
        }
        Collections.sort(apps,(a,b)->a[0].compareToIgnoreCase(b[0]));
        if(apps.isEmpty()){toast("Нет доступных приложений");return;}
        String[] choices=new String[apps.size()];
        for(int i=0;i<choices.length;i++)choices[i]=apps.get(i)[0]+" ("+apps.get(i)[1]+")";
        new AlertDialog.Builder(this).setTitle(apk?"Экспорт APK":"Поделиться приложением")
            .setItems(choices,(d,pos)->{
                String pkg=apps.get(pos)[1],name=apps.get(pos)[0];
                if(!apk){
                    Intent send=new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT,name);
                    send.putExtra(Intent.EXTRA_TEXT,name+"\nhttps://play.google.com/store/apps/details?id="+pkg);
                    start(Intent.createChooser(send,"Отправить ссылку на "+name));
                }else{prepareApk(pkg,name);}
            }).setNegativeButton("Закрыть",null).show();
    }
    private void prepareApk(String pkg,String name){
        try{
            ApplicationInfo app=getPackageManager().getApplicationInfo(pkg,0);
            if(app.splitSourceDirs!=null&&app.splitSourceDirs.length>0){
                new AlertDialog.Builder(this).setTitle("Split APK")
                    .setMessage("Приложение "+name+" использует несколько APK-модулей. Один base.apk нельзя надёжно перенести и установить. Поделитесь ссылкой на Google Play или используйте системный перенос.")
                    .setPositiveButton("Открыть Google Play",(d,w)->openAppStore(pkg))
                    .setNegativeButton("Закрыть",null).show();return;
            }
            if(app.sourceDir==null)throw new IllegalStateException("APK недоступен");
            exportApkPackage=pkg;
            new AlertDialog.Builder(this).setTitle("Экспорт APK без данных")
                .setMessage("Будет сохранён только установочный APK "+name+", если Android разрешит его чтение. Пароли, настройки и внутренние данные приложения НЕ входят. Не делитесь APK из недоверенного источника.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Выбрать место",(d,w)->{
                    Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);save.setType("application/vnd.android.package-archive");
                    save.putExtra(Intent.EXTRA_TITLE,safeName(name)+".apk");
                    try{startActivityForResult(save,SAVE_APK);}catch(Exception e){toast("Сохранение APK недоступно");}
                }).show();
        }catch(Exception e){toast("Экспорт не поддерживается: "+e.getMessage());}
    }
    private void exportApk(Uri destination){
        if(working||exportApkPackage==null)return;
        working=true;final String pkg=exportApkPackage;
        new Thread(()->{
            String error=null;
            try{
                ApplicationInfo info=getPackageManager().getApplicationInfo(pkg,0);
                if(info.splitSourceDirs!=null&&info.splitSourceDirs.length>0)throw new IllegalStateException("Обнаружен split APK");
                try(InputStream in=new java.io.FileInputStream(info.sourceDir);
                    OutputStream out=getContentResolver().openOutputStream(destination)){
                    if(out==null)throw new IllegalStateException("Нет доступа к целевому файлу");
                    byte[] b=new byte[32768];int n;
                    while((n=in.read(b))!=-1)out.write(b,0,n);
                    out.flush();
                }
            }catch(Exception e){error=e.getClass().getSimpleName()+": "+e.getMessage();}
            final String issue=error;
            runOnUiThread(()->{
                working=false;
                if(issue==null){
                    new AlertDialog.Builder(this).setTitle("APK сохранён")
                        .setMessage("Сохранён файл APK без паролей и данных приложения. Можно передать его выбранному получателю.")
                        .setPositiveButton("Поделиться",(d,w)->shareUri(destination,"application/vnd.android.package-archive","Передать APK"))
                        .setNegativeButton("Закрыть",null).show();
                }else toast("Не удалось сохранить APK: "+issue);
            });
        },"ach-apk-export").start();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(request==PICK_FILES){
            selectedFiles.clear();
            if(data.getClipData()!=null){
                for(int i=0;i<data.getClipData().getItemCount();i++){
                    Uri file=data.getClipData().getItemAt(i).getUri();
                    if(!selectedFiles.contains(file))selectedFiles.add(file);
                }
            }else if(data.getData()!=null)selectedFiles.add(data.getData());
            showFiles();return;
        }
        Uri uri=data.getData();if(uri==null)return;
        if(request==SAVE_ZIP)saveZip(uri);
        else if(request==OPEN_ZIP)parseArchive(uri);
        else if(request==RESTORE_FOLDER)restore(uri);
        else if(request==SAVE_APK)exportApk(uri);
    }
}
