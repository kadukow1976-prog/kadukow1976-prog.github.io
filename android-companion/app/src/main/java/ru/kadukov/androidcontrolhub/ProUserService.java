package ru.kadukov.androidcontrolhub;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class ProUserService extends Binder {
    public static final String DESCRIPTOR="ru.kadukov.androidcontrolhub.ProUserService";
    public static final int PING=IBinder.FIRST_CALL_TRANSACTION+30;
    public static final int EXECUTE=IBinder.FIRST_CALL_TRANSACTION+31;
    public ProUserService(){}
    public void destroy(){System.exit(0);}

    private static String invoke(String... args)throws Exception {
        Process process=Runtime.getRuntime().exec(args);
        StringBuilder output=new StringBuilder();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()))){
            String line;
            while((line=reader.readLine())!=null){
                if(output.length()<65536)output.append(line,0,Math.min(line.length(),65536-output.length())).append('\n');
            }
        }
        if(!process.waitFor(20,TimeUnit.SECONDS)){
            process.destroyForcibly();
            throw new IllegalStateException("Превышено время выполнения");
        }
        if(process.exitValue()!=0)throw new IllegalStateException("Команда не выполнена (код "+process.exitValue()+")");
        return output.toString();
    }
    private static boolean safe(String id){
        return id!=null&&id.length()<180&&id.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
            &&!id.startsWith("com.android.")&&!id.startsWith("com.google.android.")
            &&!id.startsWith("com.oplus.")&&!id.startsWith("com.oneplus.")
            &&!id.startsWith("com.qualcomm.")&&!id.startsWith("com.mediatek.")
            &&!id.equals("ru.kadukov.androidcontrolhub");
    }
    private static String execute(String operation,String pkg){
        if(!safe(pkg))return "ОТКАЗ: неподходящий пакет.";
        if(!"CACHE".equals(operation)&&!"DATA".equals(operation)&&!"DISABLE".equals(operation))
            return "ОТКАЗ: неподдерживаемое действие.";
        try{
            String user=invoke("/system/bin/am","get-current-user").trim();
            if(!user.matches("[0-9]{1,4}"))return "ОТКАЗ: профиль пользователя не определён.";
            String location=invoke("/system/bin/pm","path","--user",user,pkg);
            if(!location.contains("package:"))return "ОТКАЗ: пакет не обнаружен.";
            String systems=invoke("/system/bin/pm","list","packages","-s","--user",user);
            if(systems.contains("package:"+pkg+"\\n")||systems.trim().endsWith("package:"+pkg))
                return "ОТКАЗ: приложение имеет системный статус.";
            if(location.contains("package:/system/")||location.contains("package:/product/")
                ||location.contains("package:/vendor/")||location.contains("package:/system_ext/"))
                return "ОТКАЗ: системный пакет.";
            String out;
            if("CACHE".equals(operation)){
                String help=invoke("/system/bin/pm","help");
                if(!help.contains("--cache-only"))return "ОТКАЗ: ОС не поддерживает безопасную очистку кэша.";
                out=invoke("/system/bin/pm","clear","--user",user,"--cache-only",pkg);
            }else if("DATA".equals(operation)){
                out=invoke("/system/bin/pm","clear","--user",user,pkg);
            }else{
                out=invoke("/system/bin/pm","disable-user","--user",user,pkg);
            }
            return ("Success".equals(out.trim())||out.contains("new state: disabled-user")?"УСПЕХ":"ПРОВЕРЬТЕ")+
                ": "+operation+" "+pkg+"\n"+out.trim();
        }catch(Exception ex){return "ОШИБКА: "+ex.getClass().getSimpleName()+": "+ex.getMessage();}
    }

    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        if(code==PING){
            data.enforceInterface(DESCRIPTOR);reply.writeNoException();
            reply.writeString("Shizuku UserService подключён");return true;
        }
        if(code==EXECUTE){
            data.enforceInterface(DESCRIPTOR);
            String mode=data.readString(),pkg=data.readString();
            String result=execute(mode,pkg);
            reply.writeNoException();reply.writeString(result);return true;
        }
        return super.onTransact(code,data,reply,flags);
    }
}
