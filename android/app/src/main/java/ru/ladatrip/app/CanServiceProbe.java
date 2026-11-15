package ru.ladatrip.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

/** Read-only AIDL metadata probe: never sends unknown binder transactions. */
public final class CanServiceProbe {
    public interface Listener {void onUpdate();}
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName service = new ComponentName("ru.yandex.yap.hal.services",
        "ru.yandex.yap.hal.services.CanService");
    private ServiceConnection connection;
    private String state="Не проверено", descriptor="", permission="";
    private boolean bound=false, exported=false;
    public CanServiceProbe(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    private void notifyStatus(String s){state=s;listener.onUpdate();}
    public String display(){return state+"\nЭкспорт: "+exported+" · Разрешение: "+permission+"\nAIDL: "+descriptor;}
    public JSONObject snapshot()throws JSONException{
        JSONObject o=new JSONObject();o.put("state",state);o.put("exported",exported);
        o.put("required_permission",permission);o.put("binder_descriptor",descriptor);return o;
    }
    public void start(){
        if(connection!=null){notifyStatus("Проверка уже выполняется");return;}
        try{
            ServiceInfo info=context.getPackageManager().getServiceInfo(service,0);
            exported=info.exported;permission=info.permission==null?"нет":info.permission;
            if(!exported){notifyStatus("Сервис не экспортирован для сторонних APK");return;}
            if(info.permission!=null&&context.checkSelfPermission(info.permission)!=PackageManager.PERMISSION_GRANTED){
                notifyStatus("Нет требуемого разрешения: "+info.permission);return;
            }
            connection=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder binder){
                    // Binder descriptor is introspection; no transact to undocumented interface.
                    try{descriptor=binder.getInterfaceDescriptor();notifyStatus("Binder подключён (без вызова методов)");}
                    catch(Exception e){notifyStatus("Binder: "+VehicleProbe.describe(e));}
                }
                @Override public void onServiceDisconnected(ComponentName name){notifyStatus("Сервис отключился");}
            };
            Intent intent=new Intent().setComponent(service);
            bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
            if(!bound){connection=null;notifyStatus("bindService вернул false");return;}
            notifyStatus("Ожидание Binder… (3 секунды)");
            handler.postDelayed(this::stop,3000);
        }catch(PackageManager.NameNotFoundException e){notifyStatus("Сервис отсутствует");}
        catch(SecurityException e){notifyStatus("SecurityException: доступ запрещён");stop();}
        catch(Exception e){notifyStatus("Ошибка привязки: "+VehicleProbe.describe(e));stop();}
    }
    public void stop(){
        if(bound&&connection!=null){try{context.unbindService(connection);}catch(Exception ignored){}}
        bound=false;connection=null;
        if(state.startsWith("Ожидание"))notifyStatus("Тайм-аут: Binder не ответил");
    }
}
