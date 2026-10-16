package ru.kadukov.androidcontrolhub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transparent, feature-scoped permissions: every grant is user-triggered.
 * No covert collection, automatic background tracking, remote control,
 * device-owner enrollment, or privilege-escalation.
 */
public final class PermissionsActivity extends Activity {
    private static final int BACKGROUND = 0xff09101d;
    private static final int PANEL = 0xff142235;
    private static final int OUTLINE = 0xff30485f;
    private static final int GREEN = 0xff4ae6b1;
    private static final int MUTED = 0xffa2b9cf;
    private static final int WHITE = 0xffedf8ff;
    private static final int REQUEST_CAMERA=401;
    private static final int REQUEST_MIC=402;
    private static final int REQUEST_PHONE=403;
    private static final int REQUEST_LOCATION=404;
    private static final int REQUEST_BLUETOOTH=405;
    private static final int REQUEST_NOTIFICATION=406;
    private static final int REQUEST_CONTACTS=407;
    private static final int REQUEST_ALL=408;
    private static final String NOTIF_CHANNEL="ach.local.test";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicInteger micToken=new AtomicInteger();
    private LinearLayout root;
    private TextView status;
    private int lastRequestCode=0;
    private String[] lastRequestPermissions;
    private boolean testRunning=false;

    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density + .5f);}
    private GradientDrawable bg(int color,int stroke,int radius){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(radius));
        if(stroke!=0)shape.setStroke(dp(1),stroke);
        return shape;
    }
    private TextView txt(String str,int sp,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(str);t.setTextSize(sp);t.setTextColor(color);
        t.setLineSpacing(dp(2),1.0f);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout.LayoutParams bottom(int space){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(space);return p;
    }
    private Button btn(String title,boolean primary,Runnable click){
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(title);b.setTextSize(12);
        b.setTextColor(primary?0xff073628:WHITE);
        b.setBackground(bg(primary?GREEN:0xff1f3549,primary?0:OUTLINE,11));
        b.setOnClickListener(v->click.run());
        return b;
    }
    private void action(LinearLayout parent,String title,boolean primary,Runnable click){
        LinearLayout.LayoutParams p=bottom(9);p.height=dp(46);
        parent.addView(btn(title,primary,click),p);
    }
    private LinearLayout section(String title,String details){
        LinearLayout card=vertical();
        card.setPadding(dp(15),dp(15),dp(15),dp(12));
        card.setBackground(bg(PANEL,OUTLINE,14));
        root.addView(card,bottom(13));
        card.addView(txt(title,16,WHITE,true),bottom(7));
        if(details!=null && !details.isEmpty())card.addView(txt(details,12,MUTED,false),bottom(10));
        return card;
    }
    private void addStatus(LinearLayout parent,String title,boolean granted){
        TextView status=txt("● "+title+": "+(granted?"разрешено":"не разрешено"),12,granted?GREEN:0xffffc18b,true);
        parent.addView(status,bottom(11));
    }
    private boolean has(String permission){
        if(permission==null)return false;
        return checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean usage(){
        AppOpsManager appops=(AppOpsManager)getSystemService(APP_OPS_SERVICE);
        return appops!=null && appops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void open(String action){
        try{startActivity(new Intent(action));}
        catch(Exception e){toast("На этом устройстве системный экран недоступен");}
    }
    private void appInfo(){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
        catch(Exception e){toast("Сведения о приложении недоступны");}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        root=vertical();
        root.setPadding(dp(16),dp(19),dp(16),dp(38));
        scroll.addView(root);
        setContentView(scroll);
        render();
    }
    @Override protected void onResume(){super.onResume();if(root!=null)render();}
    @Override protected void onDestroy(){micToken.incrementAndGet();super.onDestroy();}
    private void sectionLine(String label,String detail){
        LinearLayout c=section(label,detail);
        action(c,"Открыть сведения о приложении",false,this::appInfo);
    }
    private void render(){
        root.removeAllViews();
        root.addView(txt("Центр разрешений",25,GREEN,true),bottom(7));
        root.addView(txt("Android Control Hub · 0.5 Beta\nПрава предоставляются только после вашего подтверждения в Android. Можно запросить их по отдельности или одной серией.",12,MUTED,false),bottom(16));
        LinearLayout summary=section("Сводка по разрешениям","Разрешения Android делятся на обычные, запрашиваемые при использовании и специальные доступы. Root и системные привилегии не выдаются обычным APK.");
        int available=1;int granted=has(Manifest.permission.READ_CONTACTS)?1:0;
        String[] dangerous={
            Manifest.permission.READ_PHONE_STATE,Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION
        };
        for(String permission:dangerous){available++;if(has(permission))granted++;}
        if(Build.VERSION.SDK_INT>=31){available++;if(has(Manifest.permission.BLUETOOTH_CONNECT))granted++;}
        if(Build.VERSION.SDK_INT>=33){available++;if(has(Manifest.permission.POST_NOTIFICATIONS))granted++;}
        summary.addView(txt("Обычных пользовательских разрешений: "+granted+" из "+available+"\nUsage Access: "+(usage()?"разрешён":"не выдан"),13,WHITE,true),bottom(12));
        action(summary,"Запросить все применимые разрешения для диагностики",true,this::requestAllDiagnosticPermissions);
        summary.addView(txt("Android может показать несколько системных запросов. Можно отказать в любом из них. Кнопка НЕ выдаёт Usage Access, root, ADB или системные права. Доступ к микрофону, камере и местоположению предоставляется только с вашего согласия.",11,MUTED,false),bottom(12));
        action(summary,"Перейти к настройкам приложения",false,this::appInfo);
        LinearLayout usageBlock=section("01 · История запуска приложений","История переходов на экран и зарегистрированных foreground-служб. Требует отдельного Usage Access. На установленном из APK приложении Android 16 может показывать «Доступ запрещён» из-за Restricted Settings.");
        addStatus(usageBlock,"Доступ к статистике",usage());
        action(usageBlock,"Открыть системный доступ к статистике",true,()->open(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        action(usageBlock,"Карточка приложения: ограничения Android 16",false,this::appInfo);
        usageBlock.addView(txt("Если Android блокирует доступ: проверьте происхождение APK. На экране «Сведения о приложении» откройте меню ⋮ → «Разрешить ограниченные настройки» (если доступно), затем повторите выдачу Usage Access. Не отключайте защиту всего телефона.",11,MUTED,false),bottom(7));

        LinearLayout phone=section("02 · Модем / SIM / связь","Проверка общедоступных данных SIM и типа мобильной сети. IMEI, IMSI и другие защищённые идентификаторы не считываются.");
        addStatus(phone,"Состояние телефона",has(Manifest.permission.READ_PHONE_STATE));
        action(phone,"Проверить SIM и мобильную связь",true,()->ensure(REQUEST_PHONE,new String[]{Manifest.permission.READ_PHONE_STATE},this::testPhone));

        LinearLayout mic=section("03 · Микрофон","Краткий тест уровня входного сигнала (~1,5 с), без записи файла и передачи аудио.");
        addStatus(mic,"Микрофон",has(Manifest.permission.RECORD_AUDIO));
        action(mic,"Тест микрофона",true,()->ensure(REQUEST_MIC,new String[]{Manifest.permission.RECORD_AUDIO},this::testMicrophone));

        LinearLayout camera=section("04 · Камеры","Проверка доступа к аппаратному модулю камеры без сохранения фотографий. Индикатор камеры может кратковременно включиться.");
        addStatus(camera,"Камера",has(Manifest.permission.CAMERA));
        action(camera,"Проверить доступ к камере",true,()->ensure(REQUEST_CAMERA,new String[]{Manifest.permission.CAMERA},this::testCamera));

        LinearLayout location=section("05 · GPS / местоположение","Только по запросу: проверка последней известной точности местоположения. Координаты на экран не выводятся и не отправляются.");
        boolean loc=has(Manifest.permission.ACCESS_COARSE_LOCATION)||has(Manifest.permission.ACCESS_FINE_LOCATION);
        addStatus(location,"Местоположение",loc);
        action(location,"Проверить точность определения",true,()->ensure(REQUEST_LOCATION,new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},this::testLocation));

        LinearLayout bt=section("06 · Bluetooth и подключённые устройства","Диагностика состояния Bluetooth и количества ранее сопряжённых устройств. Сканирование окружения не выполняется.");
        if(Build.VERSION.SDK_INT>=31)addStatus(bt,"Близлежащие устройства",has(Manifest.permission.BLUETOOTH_CONNECT));
        else bt.addView(txt("Android до 12: базовое Bluetooth-разрешение выдаётся при установке.",12,MUTED,false),bottom(10));
        action(bt,"Проверить Bluetooth",true,()->{
            if(Build.VERSION.SDK_INT>=31)ensure(REQUEST_BLUETOOTH,new String[]{Manifest.permission.BLUETOOTH_CONNECT},this::testBluetooth);
            else testBluetooth();
        });

        LinearLayout notifications=section("07 · Локальные уведомления","Проверка вывода собственного тестового уведомления. Чтение чужих уведомлений не используется.");
        if(Build.VERSION.SDK_INT>=33)addStatus(notifications,"Уведомления",has(Manifest.permission.POST_NOTIFICATIONS));
        else notifications.addView(txt("На этом Android отдельное разрешение не требуется.",12,MUTED,false),bottom(7));
        action(notifications,"Показать тестовое уведомление",false,()->{
            if(Build.VERSION.SDK_INT>=33)ensure(REQUEST_NOTIFICATION,new String[]{Manifest.permission.POST_NOTIFICATIONS},this::testNotification);
            else testNotification();
        });

        LinearLayout contacts=section("08 · Контакты и файлы","READ_CONTACTS уже используется для добровольного экспорта VCF. Для файлов используется системное окно выбора — широкие права ко всему накопителю не требуются.");
        addStatus(contacts,"Контакты",has(Manifest.permission.READ_CONTACTS));
        action(contacts,"Открыть экспорт контактов и файлов",false,()->startActivity(new Intent(this,MainActivity.class)));

        LinearLayout special=section("09 · Специальные доступы Android","Некоторые доступы открываются только через отдельные настройки. Эти права не следует включать без реальной функции и обоснования.");
        special.addView(txt("Статистика использования: "+(usage()?"включена":"не выдана"),12,usage()?GREEN:MUTED,false),bottom(8));
        action(special,"Открыть список специальных доступов",false,()->open(Settings.ACTION_APPLICATION_SETTINGS));
        special.addView(txt("Поверх других окон · управление системными настройками · все файлы · чтение уведомлений · Accessibility · администратор устройства: в этой сборке НЕ запрашиваются, так как соответствующих функций нет.",11,MUTED,false),bottom(6));

        LinearLayout system=section("10 · Системные и технические права","ADB, root, привилегии системного приложения, Device Owner, чтение защищённых каталогов и безусловное управление чужими приложениями не могут быть выданы стандартной кнопкой разрешений.");
        action(system,"Открыть настройки Android",false,()->open(Settings.ACTION_SETTINGS));
        action(system,"Открыть сведения о приложении",false,this::appInfo);
        system.addView(txt("Для расширенной лабораторной диагностики нужен отдельно настроенный ПК с ADB и явным подтверждением отладки на конкретном телефоне. Установка этого APK не предоставляет ADB/root.",11,MUTED,false),bottom(6));
    }
    private String[] diagnosticPermissions(){
        ArrayList<String> all=new ArrayList<>();
        all.add(Manifest.permission.READ_CONTACTS);
        all.add(Manifest.permission.READ_PHONE_STATE);
        all.add(Manifest.permission.RECORD_AUDIO);
        all.add(Manifest.permission.CAMERA);
        all.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        all.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(Build.VERSION.SDK_INT>=31)all.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(Build.VERSION.SDK_INT>=33)all.add(Manifest.permission.POST_NOTIFICATIONS);
        ArrayList<String> missing=new ArrayList<>();
        for(String permission:all)if(!has(permission))missing.add(permission);
        return missing.toArray(new String[0]);
    }
    private void requestAllDiagnosticPermissions(){
        String[] missing=diagnosticPermissions();
        if(missing.length==0){
            result("Разрешения диагностики","Все применимые обычные разрешения уже выданы.\n\nСпециальный Usage Access при необходимости включается отдельно в настройках Android.");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Запросить разрешения диагностики")
            .setMessage("Будет запрошено недостающих разрешений: "+missing.length+
                "\n\n• Контакты — экспорт VCF\n• Состояние телефона — SIM и сеть\n• Камера — проверка модуля\n• Микрофон — краткий тест звука\n• Местоположение — только точность последней фиксации\n• Bluetooth — сопряжённые устройства\n• Уведомления — тестовое сообщение"+
                "\n\nAndroid покажет системные запросы. Вы вправе отказать в каждом. Никакой фоновый сбор данных не запускается. Usage Access выдаётся отдельно.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Перейти к запросам",(d,w)->requestPermissions(missing,REQUEST_ALL))
            .show();
    }
    private void ensure(int requestCode,String[] permissions,Runnable operation){
        boolean hasAll=true;
        for(String permission:permissions)if(!has(permission)){hasAll=false;break;}
        if(hasAll){operation.run();return;}
        lastRequestCode=requestCode;
        lastRequestPermissions=permissions;
        final String purpose;
        switch(requestCode){
            case REQUEST_PHONE:purpose="Считать состояние SIM и тип мобильной сети.";break;
            case REQUEST_MIC:purpose="Проверить уровень звука в микрофоне около 1,5 секунд без записи.";break;
            case REQUEST_CAMERA:purpose="Кратковременно открыть аппаратную камеру без сохранения фото.";break;
            case REQUEST_LOCATION:purpose="Проверить только точность последней GPS-фиксации, без передачи координат.";break;
            case REQUEST_BLUETOOTH:purpose="Показать состояние Bluetooth и число сопряжённых устройств.";break;
            case REQUEST_NOTIFICATION:purpose="Отобразить тестовое локальное уведомление.";break;
            default:purpose="Проверка выбранного модуля.";
        }
        new AlertDialog.Builder(this).setTitle("Запрос разрешения")
          .setMessage(purpose+"\n\nДоступ запрашивается только для этой функции. Отказ не мешает другим тестам.")
          .setNegativeButton("Отмена",null)
          .setPositiveButton("Продолжить",(d,w)->requestPermissions(permissions,requestCode))
          .show();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        render();
        if(requestCode==REQUEST_ALL){
            int grantedCount=0;
            for(String permission:permissions)if(has(permission))grantedCount++;
            result("Разрешения проверены","Разрешено новых: "+grantedCount+" из "+permissions.length+
                "\n\nОставшиеся разрешения можно запросить повторно или включить в сведениях о приложении.\n\nUsage Access — отдельный специальный доступ Android.");
            return;
        }
        if(results.length==0)return;
        boolean allowed=false;
        if(requestCode==REQUEST_LOCATION){
            allowed=has(Manifest.permission.ACCESS_COARSE_LOCATION)||has(Manifest.permission.ACCESS_FINE_LOCATION);
        }else{
            allowed=true;
            for(int result:results)if(result!=PackageManager.PERMISSION_GRANTED){allowed=false;break;}
        }
        if(!allowed){toast("Разрешение не выдано. Тест отменён.");return;}
        switch(requestCode){
            case REQUEST_PHONE:testPhone();break;
            case REQUEST_MIC:testMicrophone();break;
            case REQUEST_CAMERA:testCamera();break;
            case REQUEST_LOCATION:testLocation();break;
            case REQUEST_BLUETOOTH:testBluetooth();break;
            case REQUEST_NOTIFICATION:testNotification();break;
            default:break;
        }
    }
    private void result(String title,String message){
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
          .setPositiveButton("Закрыть",null).show();
    }
    private void testPhone(){
        try{
            TelephonyManager t=(TelephonyManager)getSystemService(TELEPHONY_SERVICE);
            if(t==null){result("Радиомодуль","Сервис телефонии недоступен");return;}
            StringBuilder s=new StringBuilder();
            s.append("Количество телефонных модулей/слотов (оценка): ").append(t.getPhoneCount());
            s.append("\nСостояние SIM (код): ").append(t.getSimState());
            s.append("\nОператор SIM: ").append(t.getSimOperatorName());
            s.append("\nОператор сети: ").append(t.getNetworkOperatorName());
            try{s.append("\nТип мобильной сети (код): ").append(t.getDataNetworkType());}
            catch(SecurityException e){s.append("\nТип мобильной сети: ограничено прошивкой");}
            s.append("\n\nСостояние SIM и регистрацию в сети следует дополнительно проверять сервисными тестами телефона. Это не тест радиочастотного тракта.");
            result("SIM и связь",s.toString());
        }catch(SecurityException e){result("SIM и связь","Android не разрешил доступ: "+e.getMessage());}
        catch(Exception e){result("SIM и связь","Сведения недоступны: "+e.getClass().getSimpleName());}
    }
    private void testLocation(){
        try{
            LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
            if(lm==null){result("Местоположение","Служба геолокации недоступна.");return;}
            if(!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)&&!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                result("Местоположение","Геолокация отключена. Включите её вручную, если хотите проверить GPS.");return;
            }
            Location best=null;
            for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
                try{
                    if(!lm.isProviderEnabled(provider))continue;
                    Location fix=lm.getLastKnownLocation(provider);
                    if(fix!=null && (best==null||fix.getElapsedRealtimeNanos()>best.getElapsedRealtimeNanos()))best=fix;
                }catch(Exception ignored){}
            }
            if(best==null){result("Местоположение","Последняя фиксация недоступна. Включите геолокацию и откройте карты, затем повторите тест.");return;}
            long age=Math.max(0,(SystemClock.elapsedRealtimeNanos()-best.getElapsedRealtimeNanos())/1000000000L);
            result("GPS / геолокация","Последний источник: "+best.getProvider()+
                "\nТочность (оценка): "+(best.hasAccuracy()?String.format(Locale.getDefault(),"%.1f м",best.getAccuracy()):"не указана")+
                "\nВозраст фиксации: "+age+" с\n\nЭто последняя известная точка, не новый GPS-замер. Координаты не показываются и никуда не передаются.");
        }catch(Exception e){result("Местоположение","Данные недоступны: "+e.getClass().getSimpleName());}
    }
    private void testBluetooth(){
        try{
            BluetoothManager mgr=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=mgr==null?null:mgr.getAdapter();
            if(adapter==null){result("Bluetooth","Bluetooth-адаптер не обнаружен.");return;}
            String mode=adapter.isEnabled()?"Включён":"Выключен";
            String count="Неизвестно";
            if(adapter.isEnabled()){
                try{count=String.valueOf(adapter.getBondedDevices().size());}
                catch(SecurityException e){count="доступ ограничен";}
            }
            result("Bluetooth","Адаптер: "+mode+"\nСопряжённые устройства: "+count+"\n\nИмена устройств не читаются и поиск устройств не выполняется.");
        }catch(Exception e){result("Bluetooth","Ошибка: "+e.getClass().getSimpleName());}
    }
    private void testNotification(){
        try{
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm==null){toast("Сервис уведомлений недоступен");return;}
            if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(NOTIF_CHANNEL,"Проверка Android Hub",NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,NOTIF_CHANNEL):new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Android Control Hub · проверка")
                .setContentText("Тестовое уведомление. Данные не передавались.")
                .setAutoCancel(true);
            nm.notify(1009,builder.build());
            handler.postDelayed(()->nm.cancel(1009),6000);
            toast("Тестовое уведомление создано");
        }catch(Exception e){result("Уведомления","Не удалось создать тестовое уведомление: "+e.getClass().getSimpleName());}
    }
    private void testCamera(){
        try{
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
            if(manager==null){result("Камера","Сервис камеры недоступен.");return;}
            String[] ids=manager.getCameraIdList();
            if(ids.length==0){result("Камера","Камеры не найдены.");return;}
            if(testRunning){toast("Другая проверка уже выполняется");return;}
            testRunning=true;
            handler.postDelayed(()->{testRunning=false;},3500);
            manager.openCamera(ids[0],new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice camera){
                    camera.close();testRunning=false;
                    result("Камера","Первый модуль камеры успешно открылся и закрыт.\n\nОбнаружено ID модулей: "+ids.length+
                        "\nЭто тест доступа к драйверу, а не проверка качества изображения/автофокуса.");
                }
                @Override public void onDisconnected(CameraDevice camera){
                    camera.close();testRunning=false;result("Камера","Камера была отключена.");
                }
                @Override public void onError(CameraDevice camera,int error){
                    camera.close();testRunning=false;result("Камера","Ошибка аппаратного открытия, код: "+error);
                }
            },handler);
        }catch(SecurityException e){testRunning=false;result("Камера","Разрешение не предоставлено.");}
        catch(Exception e){testRunning=false;result("Камера","Камера недоступна: "+e.getClass().getSimpleName());}
    }
    private void testMicrophone(){
        if(testRunning){toast("Сначала дождитесь окончания предыдущего теста");return;}
        testRunning=true;
        final int token=micToken.incrementAndGet();
        toast("Тест микрофона начат на ~1,5 секунды; аудио не сохраняется.");
        new Thread(()->{
            AudioRecord record=null;
            String message="";
            try{
                int rate=16000;
                int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<=0)throw new IllegalStateException("Формат микрофона не поддерживается");
                record=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,3200));
                if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Не удалось инициализировать микрофон");
                short[] buffer=new short[800];
                double peak=0;
                long until=SystemClock.elapsedRealtime()+1500;
                record.startRecording();
                while(SystemClock.elapsedRealtime()<until && token==micToken.get()){
                    int size=record.read(buffer,0,buffer.length);
                    if(size<=0)continue;
                    double sum=0;
                    for(int i=0;i<size;i++){double sample=buffer[i]/32768.0;sum+=sample*sample;}
                    double rms=Math.sqrt(sum/size);
                    if(rms>peak)peak=rms;
                }
                int percent=(int)Math.min(100,Math.round(peak*100));
                message="Максимальный RMS-уровень (отн.): "+percent+"%\n\nРечь и аудиозапись не сохранялись. Значение не является абсолютной калибровкой микрофона.";
            }catch(Exception e){message="Не удалось проверить микрофон: "+e.getClass().getSimpleName();}
            finally{
                if(record!=null){
                    try{record.stop();}catch(Exception ignored){}
                    try{record.release();}catch(Exception ignored){}
                }
            }
            final String out=message;
            runOnUiThread(()->{testRunning=false;if(token==micToken.get())result("Тест микрофона",out);});
        },"ach-local-mic-test").start();
    }
}
