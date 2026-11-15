package ru.ladatrip.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only, optional Android Automotive probe. No compile-time android.car dependency. */
public final class VehicleProbe {
    public interface Listener { void onUpdate(); }
    private static final int TIMEOUT_MS = 8000;
    private static final String[] PROPERTIES = {
        "PERF_VEHICLE_SPEED", "ENGINE_RPM", "IGNITION_STATE", "CURRENT_GEAR",
        "GEAR_SELECTION", "FUEL_LEVEL", "PARKING_BRAKE_ON", "FUEL_LEVEL_LOW"
    };
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private Object car, manager, callbackProxy;
    private boolean running, connected;
    private long startedAt;
    private String connectionStatus = "Не проверено";

    private static final class Entry {
        String state = "Не проверено", value = "", source = "";
        int id = -1;
        long timestamp;
        JSONObject json() throws JSONException {
            JSONObject o=new JSONObject();o.put("state",state);o.put("value",value);
            o.put("property_id",id<0?JSONObject.NULL:String.format("0x%08X",id));
            o.put("timestamp_ms",timestamp);o.put("source",source);return o;
        }
    }
    public VehicleProbe(Context c,Listener l){
        context=c.getApplicationContext();listener=l;
        for(String name:PROPERTIES)entries.put(name,new Entry());
    }
    private void update(String name,String state,String value,String source){
        synchronized(entries){Entry e=entries.get(name);if(e==null)return;
            e.state=state;e.value=value==null?"":value;e.source=source;e.timestamp=System.currentTimeMillis();}
        main.post(listener::onUpdate);
    }
    private void status(String msg){connectionStatus=msg;main.post(listener::onUpdate);}
    public String getConnectionStatus(){return connectionStatus;}
    public String display(){
        StringBuilder b=new StringBuilder("CarService: "+connectionStatus+"\n");
        synchronized(entries){for(Map.Entry<String,Entry> pair:entries.entrySet()){
            Entry e=pair.getValue();b.append("\n").append(pair.getKey()).append(": ").append(e.state);
            if(!e.value.isEmpty())b.append(" · ").append(e.value);
        }}return b.toString();
    }
    public JSONObject snapshot() throws JSONException {
        JSONObject root=new JSONObject();root.put("connection",connectionStatus);root.put("started_at",startedAt);
        JSONObject props=new JSONObject();synchronized(entries){for(Map.Entry<String,Entry> p:entries.entrySet())props.put(p.getKey(),p.getValue().json());}
        root.put("properties",props);return root;
    }
    public void start(){
        if(running)return;
        running=true;startedAt=System.currentTimeMillis();status("Подключение…");
        synchronized(entries){for(Entry e:entries.values()){e.state="Ожидание";e.value="";e.id=-1;}}
        main.postDelayed(()->{
            if(running&&!connected){status("Тайм-аут подключения");finishUnresolved("Нет подключения к CarService");}
        },TIMEOUT_MS);
        try {
            Class<?> clazz=Class.forName("android.car.Car");
            ServiceConnection sc=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder service){
                    connected=true;status("Подключён: "+name.getClassName());worker.execute(()->readProperties());
                }
                @Override public void onServiceDisconnected(ComponentName name){connected=false;status("CarService отключён");}
            };
            Method factory=null;
            for(Method m:clazz.getMethods()){
                if(!m.getName().equals("createCar"))continue;
                Class<?>[] p=m.getParameterTypes();
                if(p.length==3 && Context.class.isAssignableFrom(p[0]) &&
                    ServiceConnection.class.isAssignableFrom(p[1]) &&
                    (Handler.class.isAssignableFrom(p[2]) || Looper.class.isAssignableFrom(p[2]))) {
                    factory=m;break;
                }
            }
            if(factory==null)throw new NoSuchMethodException("Car.createCar(Context, ServiceConnection, Handler/Looper)");
            car=factory.invoke(null,context,sc,null);
            if(car==null){status("Car.createCar вернул null");finishUnresolved("CarService недоступен");return;}
            car.getClass().getMethod("connect").invoke(car);
        }catch(Throwable error){
            status("Ошибка: "+describe(error));finishUnresolved("Car API недоступен");
        }
    }
    private void finishUnresolved(String result){
        synchronized(entries){for(Entry e:entries.values())if(e.state.equals("Ожидание"))e.state=result;}
        main.post(listener::onUpdate);
    }
    private void readProperties(){
        try {
            manager=car.getClass().getMethod("getCarManager",String.class).invoke(car,"property");
            if(manager==null)throw new IllegalStateException("getCarManager(property) = null");
            Class<?> ids;
            try{ids=Class.forName("android.car.VehiclePropertyIds");}
            catch(ClassNotFoundException e){ids=Class.forName("android.hardware.automotive.vehicle.V2_0.VehicleProperty");}
            for(String key:PROPERTIES){
                if(!running)break;
                int id;
                try{id=ids.getField(key).getInt(null);}catch(Throwable ex){update(key,"Константа отсутствует",describe(ex),"runtime reflection");continue;}
                synchronized(entries){entries.get(key).id=id;}
                String permission=permissionFor(key);
                if(permission!=null && context.checkSelfPermission(permission)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                    update(key,"Разрешение не выдано",permission,"CarPropertyManager");continue;
                }
                try {
                    Object config=manager.getClass().getMethod("getCarPropertyConfig",int.class).invoke(manager,id);
                    if(config==null){update(key,"Не поддерживается / скрыто", "Нет конфигурации свойства", "CarPropertyManager");continue;}
                    Class<?> type=(Class<?>)config.getClass().getMethod("getPropertyType").invoke(config);
                    Object value=manager.getClass().getMethod("getProperty",Class.class,int.class,int.class)
                        .invoke(manager,type,id,0);
                    if(value==null){update(key,"Значение не поступает","null","CarPropertyManager");continue;}
                    int availability=(Integer)value.getClass().getMethod("getStatus").invoke(value);
                    Object raw=value.getClass().getMethod("getValue").invoke(value);
                    if(availability!=0){update(key,"Значение недоступно","status="+availability,"CarPropertyManager");continue;}
                    update(key,"Доступно",humanize(key,raw),"CarPropertyManager.getProperty");
                    subscribe(key,id);
                }catch(Throwable ex){
                    String status=isPermissionError(ex)?"Доступ запрещён": "Ошибка чтения";
                    update(key,status,describe(ex),"CarPropertyManager");
                }
            }
            status("Чтение завершено; подписки по возможности");
        }catch(Throwable e){status("CarPropertyManager: "+describe(e));finishUnresolved("Ошибка CarPropertyManager");}
    }
    private synchronized void subscribe(String name,int id){
        if(!running||manager==null)return;
        try {
            if(callbackProxy==null){
                Class<?> iface=Class.forName("android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
                callbackProxy=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},(proxy,method,args)->{
                    if(method.getName().equals("onChangeEvent")&&args!=null&&args.length>0&&args[0]!=null){
                        Object val=args[0];int changed=(Integer)val.getClass().getMethod("getPropertyId").invoke(val);
                        for(String key:PROPERTIES){Entry e; synchronized(entries){e=entries.get(key);}
                            if(e.id==changed){int status=(Integer)val.getClass().getMethod("getStatus").invoke(val);
                                Object raw=val.getClass().getMethod("getValue").invoke(val);
                                update(key,status==0?"Доступно (обновляется)":"Значение недоступно",
                                    status==0?humanize(key,raw):"status="+status,"CarPropertyManager.callback");break;}
                        }
                    }
                    return null;
                });
            }
            Class<?> iface=callbackProxy.getClass().getInterfaces()[0];
            manager.getClass().getMethod("registerCallback",iface,int.class,float.class)
                .invoke(manager,callbackProxy,id,1.0f);
        }catch(Throwable ex){ // Some Android 9 variants expose only polling.
            update(name,"Доступно (без подписки)",valueOf(name),"registerCallback: "+describe(ex));
        }
    }
    private String valueOf(String name){synchronized(entries){return entries.get(name).value;}}
    private static String humanize(String name,Object raw){
        if(raw==null)return "null";
        if("PERF_VEHICLE_SPEED".equals(name)&&raw instanceof Number)
            return String.format(java.util.Locale.US,"%.1f км/ч (%.2f м/с)",((Number)raw).doubleValue()*3.6,((Number)raw).doubleValue());
        return String.valueOf(raw)+("FUEL_LEVEL".equals(name)?" (сырое значение; единицы уточнить)":"");
    }
    private static String permissionFor(String name){
        if(name.equals("PERF_VEHICLE_SPEED"))return "android.car.permission.CAR_SPEED";
        if(name.equals("FUEL_LEVEL")||name.equals("FUEL_LEVEL_LOW"))return "android.car.permission.CAR_ENERGY";
        return "android.car.permission.CAR_POWERTRAIN";
    }
    private static boolean isPermissionError(Throwable t){
        while(t!=null){if(t instanceof SecurityException)return true;t=t.getCause();}return false;
    }
    public static String describe(Throwable t){
        Throwable cause=t;
        while(cause instanceof InvocationTargetException && ((InvocationTargetException)cause).getCause()!=null)
            cause=((InvocationTargetException)cause).getCause();
        String message=cause.getMessage();return cause.getClass().getSimpleName()+
            (message==null?"":": "+message.replaceAll("[\\r\\n]"," ").substring(0,Math.min(message.length(),180)));
    }
    public void stop(){
        running=false;
        if(manager!=null&&callbackProxy!=null){
            try{Class<?> iface=callbackProxy.getClass().getInterfaces()[0];
                manager.getClass().getMethod("unregisterCallback",iface).invoke(manager,callbackProxy);
            }catch(Throwable ignored){}
        }
        if(car!=null){try{car.getClass().getMethod("disconnect").invoke(car);}catch(Throwable ignored){}}
        worker.shutdownNow();
    }
}
