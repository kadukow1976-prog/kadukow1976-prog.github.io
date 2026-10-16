package ru.kadukov.androidcontrolhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-device, opt-in history viewer backed by UsageStatsManager.
 * Android does NOT expose every ordinary background process, JobScheduler task,
 * service, alarm or hidden execution event through this API.
 */
public final class ActivityMonitorActivity extends Activity {
    private static final int BG=0xff09101d, SURFACE=0xff142234, BORDER=0xff30455b;
    private static final int TEXT=0xffedf8ff, MUTED=0xff9bb2c5, ACCENT=0xff4ae6b1;
    private static final int MAX_TIMELINE=1500, MAX_VISIBLE=70, CSV_REQUEST=301;
    private static final String[] PERIOD_LABELS={"За 1 час","За 24 часа","За 3 дня"};
    private static final long[] PERIOD_MS={3600000L,86400000L,259200000L};
    private static final String[] FILTER_LABELS={"Все события","На экране","Фоновые службы"};
    private final AtomicInteger requestId=new AtomicInteger();
    private final ArrayList<EventRow> events=new ArrayList<>();
    private final ArrayList<AppRow> apps=new ArrayList<>();
    private LinearLayout appArea, eventArea, body;
    private TextView status, counts;
    private Button grantBtn, exportBtn;
    private int periodIndex=1,filterIndex=0;
    private String search="";
    private boolean ready=false, scanned=false, truncated=false;

    private static final class EventRow {
        long time;
        String pkg, cls;
        int type;
        EventRow(long time,String pkg,String cls,int type){
            this.time=time;this.pkg=pkg;this.cls=cls;this.type=type;
        }
    }
    private static final class AppRow {
        String pkg,label;
        int showCount,hideCount,fgServiceStarts,fgServiceStops;
        long lastTime;
        int lastType;
        int total(){return showCount+hideCount+fgServiceStarts+fgServiceStops;}
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable background(int color,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);
        return d;
    }
    private LinearLayout column(){
        LinearLayout v=new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(s);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(null, Typeface.BOLD);
        t.setLineSpacing(dp(2),1);
        return t;
    }
    private LinearLayout.LayoutParams space(int bottom){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(bottom);
        return lp;
    }
    private void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private Button button(String s,boolean primary,Runnable callback){
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(12);
        b.setTextColor(primary?0xff0d342b:TEXT);
        b.setBackground(background(primary?ACCENT:0xff1f3549,primary?0:BORDER,11));
        b.setOnClickListener(v->callback.run());
        return b;
    }
    private void addButton(LinearLayout parent,String s,boolean primary,Runnable click){
        LinearLayout.LayoutParams lp=space(8);
        lp.height=dp(46);
        parent.addView(button(s,primary,click),lp);
    }
    private LinearLayout card(String heading,String description){
        LinearLayout c=column();
        c.setBackground(background(SURFACE,BORDER,15));
        c.setPadding(dp(16),dp(16),dp(16),dp(14));
        body.addView(c,space(13));
        c.addView(label(heading,16,TEXT,true),space(5));
        if(description!=null)c.addView(label(description,12,MUTED,false),space(12));
        return c;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(state!=null){
            periodIndex=state.getInt("period",1);
            filterIndex=state.getInt("filter",0);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(BG);
        body=column();
        body.setPadding(dp(16),dp(20),dp(16),dp(35));
        scroll.addView(body);
        setContentView(scroll);

        TextView heading=label("Активность приложений",24,ACCENT,true);
        body.addView(heading,space(7));
        body.addView(label("Журнал Android Usage Events. Локальная проверка без передачи данных.",12,MUTED,false),space(18));

        LinearLayout access=card("01  Доступ к истории","Выдаётся владельцем телефона вручную в настройках Android. Доступ можно отозвать.");
        status=label("Проверка разрешения…",12,TEXT,false);
        access.addView(status,space(12));
        grantBtn=button("Выдать доступ к статистике",true,this::requestUsageAccess);
        LinearLayout.LayoutParams gp=space(0);gp.height=dp(46);
        access.addView(grantBtn,gp);

        LinearLayout control=card("02  Период и фильтры","Экранная активность и зарегистрированные системой запуски foreground-служб.");
        Spinner period=new Spinner(this);
        ArrayAdapter<String> periods=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,PERIOD_LABELS);
        period.setAdapter(periods);period.setSelection(periodIndex);
        control.addView(period,space(7));
        Spinner filter=new Spinner(this);
        ArrayAdapter<String> filters=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,FILTER_LABELS);
        filter.setAdapter(filters);filter.setSelection(filterIndex);
        control.addView(filter,space(8));
        EditText searchField=new EditText(this);
        searchField.setSingleLine(true);
        searchField.setHint("Название или ID пакета");
        searchField.setHintTextColor(MUTED);searchField.setTextColor(TEXT);
        searchField.setTextSize(13);
        searchField.setPadding(dp(12),dp(9),dp(12),dp(9));
        searchField.setBackground(background(0xff0d1a2a,BORDER,10));
        control.addView(searchField,space(10));
        addButton(control,"Обновить активность",true,this::refresh);
        exportBtn=button("Сохранить журнал CSV",false,this::startExport);
        LinearLayout.LayoutParams ep=space(3);ep.height=dp(46);
        control.addView(exportBtn,ep);

        counts=label("После разрешения нажмите «Обновить активность».",12,MUTED,false);
        body.addView(counts,space(16));
        LinearLayout summary=card("03  Активные в истории приложения","Сводка по системным событиям в выбранном периоде. Это НЕ список работающих процессов сейчас.");
        appArea=column();summary.addView(appArea);
        LinearLayout journal=card("04  Последние события","Время, приложение, тип и компонент, если Android его предоставил.");
        eventArea=column();journal.addView(eventArea);
        LinearLayout note=card("Границы системной диагностики","Foreground-служба — не обязательно вредоносная или скрытая активность.");
        note.addView(label("Usage Access не показывает все обычные фоновые службы, JobScheduler, AlarmManager, сетевые запросы, таймеры и скрытые процессы. Android хранит события ограниченное время — обычно несколько дней. Отсутствие записи не доказывает отсутствие активности.",12,MUTED,false));

        period.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){
                if(periodIndex!=position){periodIndex=position;if(hasAccess())refresh();}
            }
        });
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){
                filterIndex=position;render();
            }
        });
        searchField.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){search=s.toString().trim().toLowerCase(Locale.ROOT);render();}
            public void afterTextChanged(Editable editable){}
        });
        ready=true;
        updatePermission();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        out.putInt("period",periodIndex);
        out.putInt("filter",filterIndex);
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume(){
        super.onResume();
        if(ready)updatePermission();
    }
    @Override protected void onDestroy(){
        requestId.incrementAndGet();
        super.onDestroy();
    }
    private boolean hasAccess(){
        try{
            AppOpsManager op=(AppOpsManager)getSystemService(APP_OPS_SERVICE);
            if(op==null)return false;
            return op.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;
        }catch(Exception e){return false;}
    }
    private void updatePermission(){
        boolean granted=hasAccess();
        status.setText(granted?"Доступ к статистике: РАЗРЕШЁН":"Доступ к статистике: НЕ ВЫДАН");
        status.setTextColor(granted?ACCENT:0xffffb77a);
        grantBtn.setVisibility(granted?View.GONE:View.VISIBLE);
        exportBtn.setEnabled(granted && scanned);
        if(!granted){
            requestId.incrementAndGet();
            scanned=false;events.clear();apps.clear();truncated=false;
            counts.setText("Для просмотра событий включите Usage Access.");
            render();
        }else if(!scanned)refresh();
    }
    private void requestUsageAccess(){
        new AlertDialog.Builder(this)
            .setTitle("Разрешение на историю приложений")
            .setMessage("Android покажет историю использования и зарегистрированные события foreground-служб. Чтение выполняется только в приложении. Никаких скрытых фоновых записей или отправки на сервер.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Открыть настройки",(d,w)->{
                Intent i=new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                try{startActivity(i);}catch(Exception e){message("Экран разрешений недоступен");}
            }).show();
    }
    private static boolean isScreen(int type){
        return type==UsageEvents.Event.MOVE_TO_FOREGROUND || type==UsageEvents.Event.MOVE_TO_BACKGROUND;
    }
    private static boolean isForegroundService(int type){
        // Foreground service events were introduced on Android 10 (API 29).
        return type==19 || type==20;
    }
    private static boolean isRelevant(int type){return isScreen(type)||isForegroundService(type);}
    private static String typeName(int type){
        if(type==1)return "Открыто на экране";
        if(type==2)return "Ушло в фон";
        if(type==19)return "Запуск foreground-службы";
        if(type==20)return "Остановка foreground-службы";
        return "Событие "+type;
    }
    private void refresh(){
        if(!hasAccess()){updatePermission();return;}
        final int token=requestId.incrementAndGet();
        final long end=System.currentTimeMillis();
        final long begin=end-PERIOD_MS[periodIndex];
        status.setText("Чтение событий…");
        counts.setText("Получаем доступные события Android за выбранный период…");
        exportBtn.setEnabled(false);
        new Thread(()->{
            final ArrayDeque<EventRow> tail=new ArrayDeque<>();
            final LinkedHashMap<String,AppRow> aggregate=new LinkedHashMap<>();
            final boolean[] trimmed={false};
            String failure=null;
            try{
                UsageStatsManager mgr=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);
                if(mgr==null)throw new IllegalStateException("Служба UsageStats недоступна");
                UsageEvents result=mgr.queryEvents(begin,end);
                if(result==null)throw new IllegalStateException("Android не предоставил историю: проверьте разблокировку телефона");
                UsageEvents.Event scratch=new UsageEvents.Event();
                while(result.hasNextEvent()){
                    result.getNextEvent(scratch);
                    int type=scratch.getEventType();
                    if(!isRelevant(type))continue;
                    String pkg=scratch.getPackageName();
                    if(pkg==null||pkg.isEmpty())continue;
                    EventRow event=new EventRow(scratch.getTimeStamp(),pkg,scratch.getClassName(),type);
                    AppRow summary=aggregate.get(pkg);
                    if(summary==null){summary=new AppRow();summary.pkg=pkg;aggregate.put(pkg,summary);}
                    if(type==1)summary.showCount++;
                    else if(type==2)summary.hideCount++;
                    else if(type==19)summary.fgServiceStarts++;
                    else if(type==20)summary.fgServiceStops++;
                    if(event.time>=summary.lastTime){summary.lastTime=event.time;summary.lastType=type;}
                    tail.addLast(event);
                    if(tail.size()>MAX_TIMELINE){tail.removeFirst();trimmed[0]=true;}
                }
            }catch(Exception e){failure=e.getClass().getSimpleName()+": "+e.getMessage();}
            final String error=failure;
            ArrayList<EventRow> reverse=new ArrayList<>(tail);
            Collections.reverse(reverse);
            ArrayList<AppRow> sorted=new ArrayList<>(aggregate.values());
            sorted.sort((a,b)->Long.compare(b.lastTime,a.lastTime));
            runOnUiThread(()->{
                if(token!=requestId.get()||isFinishing()||isDestroyed())return;
                if(!hasAccess()){updatePermission();return;}
                status.setText("Доступ к статистике: РАЗРЕШЁН");
                if(error!=null){
                    counts.setText("Ошибка чтения: "+error);
                    scanned=false;events.clear();apps.clear();
                    render();return;
                }
                PackageManager pm=getPackageManager();
                for(AppRow item:sorted){
                    item.label=item.pkg;
                    try{
                        ApplicationInfo info=pm.getApplicationInfo(item.pkg,0);
                        CharSequence name=pm.getApplicationLabel(info);
                        if(name!=null&&!name.toString().trim().isEmpty())item.label=name.toString();
                    }catch(Exception ignored){}
                }
                apps.clear();apps.addAll(sorted);
                events.clear();events.addAll(reverse);
                truncated=trimmed[0];
                scanned=true;exportBtn.setEnabled(true);
                render();
            });
        },"ach-usage-events").start();
    }
    private String fmt(long time){
        return new SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new Date(time));
    }
    private boolean filterApp(AppRow row){
        if(!search.isEmpty()&&!row.pkg.toLowerCase(Locale.ROOT).contains(search)
            &&!(row.label!=null && row.label.toLowerCase(Locale.ROOT).contains(search)))return false;
        if(filterIndex==1 && row.showCount+row.hideCount==0)return false;
        return filterIndex!=2 || row.fgServiceStarts+row.fgServiceStops>0;
    }
    private boolean filterEvent(EventRow e){
        if(filterIndex==1&&!isScreen(e.type))return false;
        if(filterIndex==2&&!isForegroundService(e.type))return false;
        if(search.isEmpty())return true;
        for(AppRow a:apps)if(a.pkg.equals(e.pkg)){
            return a.pkg.toLowerCase(Locale.ROOT).contains(search)||(a.label!=null&&a.label.toLowerCase(Locale.ROOT).contains(search));
        }
        return e.pkg.toLowerCase(Locale.ROOT).contains(search);
    }
    private void render(){
        if(appArea==null||eventArea==null)return;
        appArea.removeAllViews();eventArea.removeAllViews();
        if(!scanned){
            addMuted(appArea,"Пока нет прочитанных событий.");
            addMuted(eventArea,"Запросите доступ и обновите журнал.");
            return;
        }
        int appTotal=0,shown=0,evTotal=0;
        for(AppRow a:apps)if(filterApp(a)){
            appTotal++;
            if(shown++>=MAX_VISIBLE)continue;
            LinearLayout row=column();
            row.setPadding(dp(10),dp(11),dp(10),dp(11));
            row.setBackground(background(0xff0d1d2c,BORDER,10));
            appArea.addView(row,space(7));
            row.addView(label(a.label==null?a.pkg:a.label,13,TEXT,true));
            row.addView(label(a.pkg,10,MUTED,false));
            row.addView(label("Экран: "+a.showCount+" открытий · Фоновые службы: "+a.fgServiceStarts+" запусков",11,ACCENT,false));
            row.addView(label("Последнее: "+fmt(a.lastTime)+" · "+typeName(a.lastType),10,MUTED,false));
            row.setOnClickListener(v->detail(a));
        }
        if(appTotal==0)addMuted(appArea,"Приложений по выбранному фильтру нет.");
        if(appTotal>MAX_VISIBLE)addMuted(appArea,"Показано "+MAX_VISIBLE+" из "+appTotal+" приложений. Используйте поиск.");
        int evRendered=0;
        for(EventRow e:events)if(filterEvent(e)){
            evTotal++;
            if(evRendered++>=MAX_VISIBLE)continue;
            LinearLayout box=column();
            box.setPadding(dp(9),dp(9),dp(9),dp(10));
            box.setBackground(background(0xff0e1c2b,BORDER,9));
            eventArea.addView(box,space(6));
            box.addView(label(fmt(e.time)+" · "+typeName(e.type),12,evIsScreenStart(e.type)?ACCENT:TEXT,true));
            box.addView(label(e.pkg,11,MUTED,false));
            if(e.cls!=null&&!e.cls.isEmpty())box.addView(label(e.cls,10,MUTED,false));
        }
        if(evTotal==0)addMuted(eventArea,"Событий по выбранному фильтру нет.");
        if(evTotal>MAX_VISIBLE)addMuted(eventArea,"Показаны первые "+MAX_VISIBLE+" записей. Полный локальный буфер: до "+MAX_TIMELINE+" последних событий.");
        counts.setText("Приложений: "+appTotal+" · событий в буфере: "+events.size()
            +(truncated?" · старые события не в списке":"")
            +"\nОбновлено: "+fmt(System.currentTimeMillis()));
    }
    private boolean evIsScreenStart(int type){return type==1||type==19;}
    private void addMuted(LinearLayout parent,String message){
        parent.addView(label(message,12,MUTED,false),space(8));
    }
    private void detail(AppRow app){
        StringBuilder sb=new StringBuilder();
        sb.append("Пакет: ").append(app.pkg)
          .append("\nОткрытий экрана: ").append(app.showCount)
          .append("\nПереходов в фон: ").append(app.hideCount)
          .append("\nЗапусков foreground-служб: ").append(app.fgServiceStarts)
          .append("\nОстановок foreground-служб: ").append(app.fgServiceStops)
          .append("\nПоследнее событие: ").append(fmt(app.lastTime)).append("\n\n")
          .append("Запуск foreground-службы не доказывает, что приложение сейчас работает или ведёт слежку.");
        new AlertDialog.Builder(this)
            .setTitle(app.label)
            .setMessage(sb.toString())
            .setNegativeButton("Назад",null)
            .setPositiveButton("Настройки приложения",(dialog,which)->{
                try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+app.pkg)));}
                catch(Exception e){message("Системные настройки приложения недоступны");}
            }).show();
    }
    private void startExport(){
        if(!scanned||!hasAccess()){message("Сначала разрешите доступ и обновите журнал");return;}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/csv");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE,"android-activity-events.csv");
        try{startActivityForResult(intent,CSV_REQUEST);}
        catch(Exception e){message("Не удалось открыть сохранение документа");}
    }
    private String csv(String s){
        return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";
    }
    @Override protected void onActivityResult(int req,int resultCode,Intent data){
        super.onActivityResult(req,resultCode,data);
        if(req!=CSV_REQUEST||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        if(!hasAccess()){message("Доступ к статистике отозван");return;}
        StringBuilder out=new StringBuilder("\uFEFFВремя,Пакет,Тип события,Компонент\n");
        int exported=0;
        for(EventRow e:events){
            if(!filterEvent(e))continue;
            out.append(csv(fmt(e.time))).append(',')
                .append(csv(e.pkg)).append(',')
                .append(csv(typeName(e.type))).append(',')
                .append(csv(e.cls)).append('\n');
            exported++;
        }
        try(OutputStream stream=getContentResolver().openOutputStream(data.getData())){
            if(stream==null)throw new IllegalStateException("Поток документа недоступен");
            stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
            message("CSV сохранён: "+exported+" событий (не более "+MAX_TIMELINE+" последних)");
        }catch(Exception e){message("Ошибка сохранения CSV: "+e.getMessage());}
    }
}
