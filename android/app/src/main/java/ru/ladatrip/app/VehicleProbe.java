package ru.ladatrip.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android Automotive read-only probe. Reflection avoids packaging an OEM android.car library.
 * IDs and permission mappings are R1 OTA hypotheses; R2 must be confirmed on the device.
 */
public final class VehicleProbe {
    public interface Listener { void onUpdate(); }

    private static final class Spec {
        final String name, permission;
        final int fallbackId;
        Spec(String n, int id, String p) { name=n; fallbackId=id; permission="android.car.permission."+p; }
    }
    private static final Spec[] SPECS = {
        new Spec("PERF_VEHICLE_SPEED",      0x11600207,"CAR_SPEED"),
        new Spec("FUEL_LEVEL",              0x11600307,"CAR_ENERGY"),
        new Spec("FUEL_LEVEL_LOW",          0x11200405,"CAR_ENERGY"),
        new Spec("IGNITION_STATE",          0x11400409,"CAR_POWER"),
        new Spec("GEAR_SELECTION",          0x11400400,"CAR_POWERTRAIN"),
        new Spec("CURRENT_GEAR",            0x11400401,"CAR_POWERTRAIN"),
        new Spec("PARKING_BRAKE_ON",        0x11200402,"CAR_POWERTRAIN"),
        new Spec("ENV_OUTSIDE_TEMPERATURE", 0x11600703,"CAR_EXTERIOR_ENVIRONMENT"),
        new Spec("ENGINE_RPM",              0x11600305,"CAR_ENGINE_DETAILED"),
        new Spec("ENGINE_COOLANT_TEMP",     0x11600301,"CAR_ENGINE_DETAILED"),
        new Spec("PERF_ODOMETER",           0x11600204,"CAR_MILEAGE"),
        new Spec("VENDOR_CANRX_REAR_GEAR_ENGAGED",0x2140F003,"CAR_VENDOR_EXTENSION"),
        new Spec("VENDOR_CANRX_SAS",        0x2140F006,"CAR_VENDOR_EXTENSION")
    };
    private static final int CONNECT_TIMEOUT_MS=8000;
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private volatile boolean running, connected;
    private volatile String connectionStatus="Не проверено", listStatus="Не проверено";
    private volatile long startedAt;
    private Object car, manager, listenerProxy;
    private Method unregisterMethod;

    private static final class Entry {
        final Spec spec;
        String state="Не проверено", value="", source="", idSource="", error="", subscribe="";
        int id, vehicleStatus=-1, callbacks;
        long lastValueAt, lastEventAt;
        Entry(Spec s){spec=s; id=s.fallbackId; idSource="R1 fallback";}
        JSONObject json()throws JSONException{
            JSONObject o=new JSONObject();
            o.put("id",String.format(Locale.US,"0x%08X",id));
            o.put("id_origin",idSource);o.put("required_permission_r1",spec.permission);
            o.put("state",state);o.put("value",value);o.put("source",source);
            o.put("vehicle_status",vehicleStatus);o.put("subscription",subscribe);
            o.put("callback_count",callbacks);o.put("last_value_at_ms",lastValueAt);
            o.put("last_callback_at_ms",lastEventAt);o.put("error",error);
            return o;
        }
    }
    public VehicleProbe(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        for(Spec s:SPECS)entries.put(s.name,new Entry(s));
    }
    private void notifyUi(){main.post(()->{if(running)listener.onUpdate();});}
    private void status(String status){connectionStatus=status;notifyUi();}
    private void update(Entry e,String state,String value,String source,String error,int vehicleStatus,boolean event){
        synchronized(entries){
            e.state=state;e.value=value==null?"":value;e.source=source;e.error=error==null?"":error;
            e.vehicleStatus=vehicleStatus;
            if(state.startsWith("Доступно")){
                e.lastValueAt=System.currentTimeMillis();
                if(event){e.lastEventAt=e.lastValueAt;e.callbacks++;}
            }
        }
        notifyUi();
    }
    public String display(){
        StringBuilder out=new StringBuilder("CarService: "+connectionStatus+"\nСписок конфигураций: "+listStatus);
        long now=System.currentTimeMillis();
        synchronized(entries){
            for(Entry e:entries.values()){
                out.append("\n\n").append(e.spec.name).append(" [")
                   .append(String.format(Locale.US,"0x%08X",e.id)).append("]");
                out.append("\n").append(e.state);
                if(!e.value.isEmpty())out.append(" · ").append(e.value);
                if(e.lastValueAt!=0)out.append("\nПоследнее значение: ").append((now-e.lastValueAt)/1000).append(" с назад");
                if(!e.subscribe.isEmpty())out.append("\nПодписка: ").append(e.subscribe);
                if(!e.error.isEmpty())out.append("\n").append(e.error);
            }
        }
        return out.toString();
    }
    public JSONObject snapshot()throws JSONException{
        JSONObject root=new JSONObject();root.put("connection",connectionStatus);
        root.put("configuration_list",listStatus);root.put("started_at_ms",startedAt);
        root.put("captured_at_ms",System.currentTimeMillis());root.put("ota_reference","R1; running R2 not yet verified");
        JSONObject props=new JSONObject();
        synchronized(entries){for(Map.Entry<String,Entry> e:entries.entrySet())props.put(e.getKey(),e.getValue().json());}
        root.put("properties",props);return root;
    }
    public void start(){
        if(running)return;
        running=true;connected=false;startedAt=System.currentTimeMillis();
        status("Подключение...");
        main.postDelayed(()->{if(running&&!connected){status("Тайм-аут 8 с");finishUnresolved("Нет соединения с CarService");}},CONNECT_TIMEOUT_MS);
        try{
            Class<?> carClass=Class.forName("android.car.Car");
            Method factory=null;
            for(Method m:carClass.getMethods()){
                Class<?>[] p=m.getParameterTypes();
                if(m.getName().equals("createCar")&&p.length==3
                    &&Context.class.isAssignableFrom(p[0])
                    &&ServiceConnection.class.isAssignableFrom(p[1])
                    &&(Handler.class.isAssignableFrom(p[2])||Looper.class.isAssignableFrom(p[2]))){factory=m;break;}
            }
            if(factory==null)throw new NoSuchMethodException("Car.createCar(Context,ServiceConnection,Handler)");
            ServiceConnection connection=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder binder){
                    if(!running)return;connected=true;status("Подключён: "+name.getClassName());
                    worker.execute(VehicleProbe.this::readProperties);
                }
                @Override public void onServiceDisconnected(ComponentName name){connected=false;status("CarService отключён");}
            };
            car=factory.invoke(null,context,connection,null);
            if(car==null)throw new IllegalStateException("Car.createCar returned null");
            car.getClass().getMethod("connect").invoke(car);
        }catch(Throwable error){status("Ошибка подключения: "+describe(error));finishUnresolved("Нет Car API");}
    }
    private void finishUnresolved(String why){
        synchronized(entries){for(Entry e:entries.values())if(e.state.equals("Не проверено"))e.state=why;}
        notifyUi();
    }
    private static Object call(Object obj,String name,Class<?>[] types,Object...args)throws Exception{
        return obj.getClass().getMethod(name,types).invoke(obj,args);
    }
    private void readProperties(){
        try{
            manager=call(car,"getCarManager",new Class<?>[]{String.class},"property");
            if(manager==null)throw new IllegalStateException("getCarManager(property) returned null");

            Map<Integer,Object> configs=new LinkedHashMap<>();
            boolean listingWorked=false;
            try{
                Object raw=call(manager,"getPropertyList",new Class<?>[]{});
                if(raw instanceof List){
                    for(Object config:(List<?>)raw){
                        int id=((Number)call(config,"getPropertyId",new Class<?>[]{})).intValue();
                        configs.put(id,config);
                    }
                    listingWorked=true;listStatus="Получено "+configs.size()+" конфигураций";
                }else listStatus="Неожиданный тип списка: "+(raw==null?"null":raw.getClass().getName());
            }catch(Throwable t){listStatus="Недоступен: "+describe(t);}
            notifyUi();

            for(Spec s:SPECS){
                if(!running)break;
                Entry e=entries.get(s.name);
                resolveId(e);
                int grant=context.checkSelfPermission(s.permission);
                if(grant!=PackageManager.PERMISSION_GRANTED){
                    update(e,"Разрешение не выдано","", "PackageManager",s.permission,-1,false);
                    continue;
                }
                if(listingWorked && !configs.containsKey(e.id)){
                    update(e,"Нет в getPropertyList","", "CarPropertyManager.getPropertyList","Возможна фильтрация по разрешениям", -1,false);
                    continue;
                }
                try{
                    Class<?> type=Float.class;
                    Object config=configs.get(e.id);
                    if(config!=null){
                        type=(Class<?>)call(config,"getPropertyType",new Class<?>[]{});
                    }else{
                        // When property list is inaccessible, determine a best-effort type from R1.
                        type=(s.name.equals("IGNITION_STATE")||s.name.equals("GEAR_SELECTION")||s.name.equals("CURRENT_GEAR"))?Integer.class:
                             (s.name.equals("PARKING_BRAKE_ON")||s.name.equals("FUEL_LEVEL_LOW")||s.name.startsWith("VENDOR_"))?Boolean.class:Float.class;
                    }
                    Object value=call(manager,"getProperty",new Class<?>[]{Class.class,int.class,int.class},type,e.id,0);
                    if(value==null){update(e,"Нет значения","", "CarPropertyManager.getProperty","Вернул null",-1,false);continue;}
                    int valueStatus=((Number)call(value,"getStatus",new Class<?>[]{})).intValue();
                    Object data=call(value,"getValue",new Class<?>[]{});
                    if(valueStatus!=0){
                        update(e,"Значение недоступно","", "CarPropertyManager.getProperty","status="+valueStatus,valueStatus,false);
                    }else{
                        update(e,"Доступно",humanize(s.name,data),"CarPropertyManager.getProperty","",valueStatus,false);
                        subscribe(e);
                    }
                }catch(Throwable t){
                    update(e,isPermissionError(t)?"Доступ запрещён":"Ошибка чтения","",
                           "CarPropertyManager",describe(t),-1,false);
                }
            }
            status("Первичное чтение завершено");
        }catch(Throwable t){status("Ошибка менеджера: "+describe(t));finishUnresolved("Менеджер недоступен");}
    }
    private void resolveId(Entry entry){
        for(String cls:new String[]{"android.car.VehiclePropertyIds","android.hardware.automotive.vehicle.V2_0.VehicleProperty"}){
            try{
                entry.id=Class.forName(cls).getField(entry.spec.name).getInt(null);
                entry.idSource=cls;return;
            }catch(Throwable ignored){}
        }
        entry.id=entry.spec.fallbackId;entry.idSource="R1 OTA numeric fallback (verify on R2)";
    }
    private synchronized void subscribe(Entry entry){
        if(!running||manager==null)return;
        try{
            Method register=null;
            for(Method m:manager.getClass().getMethods()){
                Class<?>[] p=m.getParameterTypes();
                if(m.getName().equals("registerListener")&&p.length==3&&p[0].isInterface()
                    &&p[1]==int.class &&p[2]==float.class){register=m;break;}
            }
            if(register==null)throw new NoSuchMethodException("registerListener(listener,int,float)");
            final Class<?> iface=register.getParameterTypes()[0];
            if(listenerProxy==null){
                listenerProxy=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},(proxy,method,args)->{
                    if(method.getDeclaringClass()==Object.class){
                        if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                        if(method.getName().equals("equals"))return args!=null&&args.length>0&&proxy==args[0];
                        if(method.getName().equals("toString"))return "LadaTripSensorListener";
                    }
                    if(method.getName().equals("onChangeEvent")&&args!=null&&args.length>0&&args[0]!=null){
                        Object value=args[0];
                        try{
                            int id=((Number)call(value,"getPropertyId",new Class<?>[]{})).intValue();
                            int st=((Number)call(value,"getStatus",new Class<?>[]{})).intValue();
                            Object raw=call(value,"getValue",new Class<?>[]{});
                            synchronized(entries){
                                for(Entry e:entries.values())if(e.id==id){
                                    update(e,st==0?"Доступно (событие)":"Значение недоступно",
                                        st==0?humanize(e.spec.name,raw):"",
                                        "CarPropertyManager.registerListener","status="+st,st,true);
                                    break;
                                }
                            }
                        }catch(Throwable ignored){}
                    }else if(method.getName().equals("onErrorEvent")&&args!=null&&args.length>0){
                        int id=((Number)args[0]).intValue();
                        synchronized(entries){for(Entry e:entries.values())if(e.id==id){
                            e.error="Событие ошибки CarPropertyManager";notifyUi();break;
                        }}
                    }
                    return null;
                });
                try{unregisterMethod=manager.getClass().getMethod("unregisterListener",iface);}
                catch(NoSuchMethodException ignored){}
            }
            Object result=register.invoke(manager,listenerProxy,entry.id,1.0f);
            entry.subscribe=Boolean.FALSE.equals(result)?"registerListener вернул false":"registerListener успешно вызван";
            notifyUi();
        }catch(Throwable t){entry.subscribe="Нет подписки: "+describe(t);notifyUi();}
    }
    private static String humanize(String name,Object raw){
        if(raw==null)return "null";
        if("PERF_VEHICLE_SPEED".equals(name)&&raw instanceof Number){
            double speed=((Number)raw).doubleValue();
            return String.format(Locale.US,"%.1f км/ч (Humax R2, raw=%s)",
                VehicleDisplayFormat.speedKmh(speed,"property"),raw);
        }
        if(raw.getClass().isArray()){
            int n=Array.getLength(raw);List<String> parts=new ArrayList<>();
            for(int i=0;i<Math.min(n,16);i++)parts.add(String.valueOf(Array.get(raw,i)));
            return "["+android.text.TextUtils.join(", ",parts)+(n>16?", ...":"")+"]";
        }
        return String.valueOf(raw)+(name.equals("FUEL_LEVEL")?" (сырые единицы)":"");
    }
    private static boolean isPermissionError(Throwable e){
        while(e!=null){if(e instanceof SecurityException)return true;e=e.getCause();}return false;
    }
    public static String describe(Throwable e){
        while(e instanceof InvocationTargetException&&((InvocationTargetException)e).getCause()!=null)e=e.getCause();
        String detail=e.getMessage()==null?"":e.getMessage().replace('\n',' ').replace('\r',' ');
        return e.getClass().getSimpleName()+(detail.isEmpty()?"":": "+detail.substring(0,Math.min(180,detail.length())));
    }
    public void stop(){
        running=false;
        if(manager!=null&&unregisterMethod!=null&&listenerProxy!=null){
            try{unregisterMethod.invoke(manager,listenerProxy);}catch(Throwable ignored){}
        }
        if(car!=null)try{car.getClass().getMethod("disconnect").invoke(car);}catch(Throwable ignored){}
        worker.shutdownNow();
    }
}
