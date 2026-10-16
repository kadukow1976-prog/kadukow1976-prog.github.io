package ru.kadukov.androidcontrolhub;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Shizuku UserService: IPC contract for an explicitly authorized local session. */
public final class ProUserService extends Binder {
    public static final String DESCRIPTOR="ru.kadukov.androidcontrolhub.ProUserService";
    public static final int PING=IBinder.FIRST_CALL_TRANSACTION+30;
    public ProUserService(){}
    public void destroy(){System.exit(0);}
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        if(code!=PING)return super.onTransact(code,data,reply,flags);
        data.enforceInterface(DESCRIPTOR);
        reply.writeNoException();
        reply.writeString("Подключение Shizuku UserService успешно");
        return true;
    }
}
