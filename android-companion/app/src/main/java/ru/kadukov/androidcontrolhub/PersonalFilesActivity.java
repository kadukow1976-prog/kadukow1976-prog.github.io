package ru.kadukov.androidcontrolhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PersonalFilesActivity extends Activity {
    private static final int PICK_FILES=701, PICK_BACKUP_FOLDER=702;
    private static final int BG=0xff09101d, PANEL=0xff152334, BORDER=0xff31485e;
    private static final int GREEN=0xff4ae6b1, WHITE=0xffeef7ff, MUTE=0xffa3b8ca;
    private final ArrayList<Uri> files=new ArrayList<>();
    private LinearLayout root;
    private TextView summary;
    private boolean busy=false;
    private int backupCount=0;
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams gap(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(bottom);return p;}
    private GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));d.setStroke(dp(1),BORDER);return d;}
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        t.setLineSpacing(dp(2),1);return t;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void add(LinearLayout parent,String s,int size,int color,boolean bold,int bottom){
        parent.addView(text(s,size,color,bold),gap(bottom));
    }
    private Button button(String name,boolean primary,Runnable click){
        Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setTextSize(13);
        b.setTextColor(primary?0xff08382e:WHITE);
        b.setBackground(bg(primary?GREEN:0xff20364a));
        b.setOnClickListener(v->click.run());return b;
    }
    private void action(LinearLayout box,String name,boolean primary,Runnable click){
        LinearLayout.LayoutParams lp=gap(11);lp.height=dp(47);box.addView(button(name,primary,click),lp);
    }
    private LinearLayout card(String title,String explanation){
        LinearLayout block=column();block.setPadding(dp(16),dp(15),dp(16),dp(15));
        block.setBackground(bg(PANEL));root.addView(block,gap(13));
        add(block,title,17,WHITE,true,7);
        if(explanation!=null)add(block,explanation,12,MUTE,false,11);
        return block;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        root=column();root.setPadding(dp(14),dp(20),dp(14),dp(30));scroll.addView(root);
        setContentView(scroll);
        add(root,"Личные файлы и резервная копия",23,GREEN,true,8);
        add(root,"Действия возможны только с документами, которые вы сами выберете через системный файловый менеджер.",12,MUTE,false,16);
        LinearLayout select=card("01  Выбор документов","Выберите фото, видео, загрузки или документы. Закрытые данные и кэш чужих приложений Android не раскрывает.");
        action(select,"Выбрать личные файлы",true,this::pickFiles);
        summary=text("Файлы не выбраны",12,WHITE,false);
        select.addView(summary,gap(6));
        LinearLayout backup=card("02  Сохранить файлы","Android предложит выбрать папку. В ней будут созданы копии выбранных файлов. Исходные документы не удаляются.");
        action(backup,"Создать резервную копию",true,this::chooseBackupFolder);
        LinearLayout delete=card("03  Удалить выбранные документы","Необратимая операция, если поставщик документов не поддерживает корзину. Перед удалением сохраните копии. Нет доступа к приватным файлам установленных программ.");
        action(delete,"Удалить выбранные личные файлы",false,this::confirmDelete);
        LinearLayout prefs=card("04  Данные приложений","Кнопка «Очистить данные» на системной карточке приложения может удалить всё локальное содержимое. Резервировать закрытые данные приложения через эту утилиту нельзя.");
        action(prefs,"Открыть настройки приложений",false,()->{
            try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS));}
            catch(Exception ex){Toast.makeText(this,"Настройки недоступны",Toast.LENGTH_LONG).show();}
        });
        add(prefs,"Нельзя гарантировать сохранность сообщений, баз данных игр, авторизаций, настроек, кэша и профилей приложений. Для резервного копирования используйте встроенные функции самих приложений и облачные копии.",11,MUTE,false,3);
    }
    private String name(Uri uri){
        String display=null;
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null && c.moveToFirst())display=c.getString(0);
        }catch(Exception ignored){}
        if(display==null||display.trim().isEmpty())display="Файл";
        return display.replace("/","_").replace("\\","_");
    }
    private void updateSummary(){
        StringBuilder sb=new StringBuilder();
        sb.append("Выбрано: ").append(files.size());
        int preview=Math.min(10,files.size());
        for(int i=0;i<preview;i++)sb.append("\n• ").append(name(files.get(i)));
        if(files.size()>preview)sb.append("\n… ещё ").append(files.size()-preview);
        if(backupCount>0)sb.append("\nУспешно скопировано в последнем сохранении: ").append(backupCount);
        summary.setText(sb.toString());
    }
    private void pickFiles(){
        if(busy)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{startActivityForResult(i,PICK_FILES);}
        catch(Exception ex){Toast.makeText(this,"Android не поддерживает выбор файлов",Toast.LENGTH_LONG).show();}
    }
    private void chooseBackupFolder(){
        if(busy)return;
        if(files.isEmpty()){Toast.makeText(this,"Сначала выберите файлы",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("Создание копии")
          .setMessage("Копирование выполняется локально в выбранную вами папку. Содержимое файлов не отправляется на сервер. Убедитесь, что накопитель имеет достаточно свободного места.")
          .setNegativeButton("Отмена",null)
          .setPositiveButton("Выбрать папку",(d,w)->{
              Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
              intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
              try{startActivityForResult(intent,PICK_BACKUP_FOLDER);}
              catch(Exception e){Toast.makeText(this,"Выбор папки недоступен",Toast.LENGTH_LONG).show();}
          }).show();
    }
    private void backup(Uri tree){
        if(busy)return;
        busy=true;backupCount=0;
        summary.setText("Идёт резервное копирование…");
        ArrayList<Uri> list=new ArrayList<>(files);
        new Thread(()->{
            int copied=0,errors=0;
            try{
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                byte[] buffer=new byte[32768];
                for(Uri source:list){
                    Uri out=null;
                    try{
                        String mime=getContentResolver().getType(source);
                        if(mime==null)mime="application/octet-stream";
                        out=DocumentsContract.createDocument(getContentResolver(),parent,mime,name(source));
                        if(out==null)throw new IllegalStateException("Cannot create target");
                        try(InputStream in=getContentResolver().openInputStream(source);
                            OutputStream os=getContentResolver().openOutputStream(out)){
                            if(in==null||os==null)throw new IllegalStateException("Document stream unavailable");
                            int read;
                            while((read=in.read(buffer))!=-1)os.write(buffer,0,read);
                            os.flush();
                        }
                        copied++;
                    }catch(Exception e){
                        errors++;
                        if(out!=null){try{DocumentsContract.deleteDocument(getContentResolver(),out);}catch(Exception ignored){}}
                    }
                }
            }catch(Exception e){errors+=list.size();}
            final int completed=copied,failed=errors;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                busy=false;backupCount=completed;updateSummary();
                new AlertDialog.Builder(this).setTitle("Результат резервной копии")
                  .setMessage("Скопировано: "+completed+"\nОшибок: "+failed+
                      "\n\nИсходные файлы оставлены на месте. Перед очисткой данных приложений проверьте созданные копии.")
                  .setPositiveButton("Закрыть",null).show();
            });
        },"ach-file-backup").start();
    }
    private void confirmDelete(){
        if(busy)return;
        if(files.isEmpty()){Toast.makeText(this,"Сначала выберите файлы",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this)
          .setTitle("Удаление личных файлов")
          .setMessage("Вы выбрали "+files.size()+" документ(ов). Удаление может быть необратимым.\n\nЕсли нужно сохранить файлы, нажмите «Отмена» и сначала создайте резервную копию. Приватные данные приложений не затрагиваются.")
          .setNegativeButton("Отмена",null)
          .setPositiveButton("Продолжить к подтверждению",(d,w)->new AlertDialog.Builder(this)
             .setTitle("Подтвердить удаление")
             .setMessage("Вы действительно хотите удалить именно эти "+files.size()+" выбранных файла(ов)? Восстановление не гарантируется.")
             .setNegativeButton("Не удалять",null)
             .setPositiveButton("Удалить выбранные",(dialog,which)->deleteSelected()).show()).show();
    }
    private void deleteSelected(){
        if(busy)return;
        busy=true;summary.setText("Удаление выбранных документов…");
        ArrayList<Uri> list=new ArrayList<>(files);
        new Thread(()->{
            int removed=0,failed=0;
            Set<Uri> success=new HashSet<>();
            for(Uri uri:list){
                try{
                    if(DocumentsContract.deleteDocument(getContentResolver(),uri)){
                        removed++;success.add(uri);
                    }else failed++;
                }catch(Exception e){failed++;}
            }
            final int done=removed,errors=failed;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                files.removeAll(success);
                busy=false;updateSummary();
                new AlertDialog.Builder(this).setTitle("Удаление завершено")
                  .setMessage("Удалено: "+done+"\nНе удалось удалить: "+errors+
                      "\n\nНекоторые провайдеры не разрешают удаление файлов через этот интерфейс.")
                  .setPositiveButton("OK",null).show();
            });
        },"ach-user-file-delete").start();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(request==PICK_FILES){
            files.clear();backupCount=0;
            if(data.getClipData()!=null){
                for(int i=0;i<data.getClipData().getItemCount();i++){
                    Uri uri=data.getClipData().getItemAt(i).getUri();
                    if(!files.contains(uri))files.add(uri);
                }
            }else if(data.getData()!=null)files.add(data.getData());
            updateSummary();
        }
        if(request==PICK_BACKUP_FOLDER && data.getData()!=null)backup(data.getData());
    }
}
