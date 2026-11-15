package ru.ladatrip.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Independent, read-only legacy CarSensorManager check.
 * Only uses system-provided sensor constants and events; no OEM Binder transactions.
 */
public final class CarSensorProbe {
    public interface Listener { void onUpdate(); }
    private static final String[] NAMES={
        "SENSOR_TYPE_CAR_SPEED",
        "SENSOR_TYPE_RPM",
        "SENSOR_TYPE_GEAR",
        "SENSOR_TYPE_IGNITION_STATE",
        "SENSOR_TYPE_FUEL_LEVEL",
        "SENSOR_TYPE_PARKING_BRAKE",
        "SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE",
        "SENSOR_TYPE_ODOMETER",
        "SENSOR_TYPE_NIGHT"
    };
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private volatile boolean running,connected;
    private volatile String state="Не проверено",supportedState="Не проверено";
    private Object car,manager,listenerProxy;
    private Method unregister;
    private static final class Entry {
        int sensorId=-1;
        String state="Не проверено",value="",detail="";
        long lastAt;
        int callbackCount;
        JSONObject json()throws JSONException{
            JSONObject j=new JSONObject();
            j.put("sensor_id",sensorId<0?JSONObject.NULL:sensorId);
            j.put("state",state);j.put("raw_value",value);j.put("detail",detail);
            j.put("last_value_at_ms",lastAt);j.put("callback_count",callbackCount);return j;
        }
    }
    public CarSensorProbe(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        for(String n:NAMES)entries.put(n,new Entry());
    }
    private void notifyUi(){main.post(()->{if(running)listener.onUpdate();});}
    private void status(String s){state=s;notifyUi();}
    public String display(){
        StringBuilder b=new StringBuilder("CarSensorManager: "+state+"\n"+supportedState);
        synchronized(entries){
            for(Map.Entry<String,Entry> pair:entries.entrySet()){
                Entry e=pair.getValue();
                b.append("\n\n").append(pair.getKey()).append(": ").append(e.state);
                if(e.sensorId!=-1)b.append(" [").append(e.sensorId).append("]");
                if(!e.value.isEmpty())b.append(" · ").append(e.value);
                if(e.lastAt!=0)b.append("\nПоследнее значение: ").append((System.currentTimeMillis()-e.lastAt)/1000).append(" с назад");
                if(!e.detail.isEmpty())b.append("\n").append(e.detail);
            }
        }
        return b.toString();
    }
    public JSONObject snapshot()throws JSONException{
        JSONObject j=new JSONObject();j.put("connection",state);j.put("supported_sensors",supportedState);
        j.put("captured_at_ms",System.currentTimeMillis());
        JSONObject all=new JSONObject();
        synchronized(entries){for(Map.Entry<String,Entry> e:entries.entrySet())all.put(e.getKey(),e.getValue().json());}
        j.put("sensors",all);return j;
    }
    public void start(){
        if(running)return;
        running=true;connected=false;status("Подключение...");
        main.postDelayed(()->{if(running&&!connected){status("Тайм-аут подключения");unresolved("CarService не подключился");}},8000);
        try{
            Class<?> c=Class.forName("android.car.Car");
            Method factory=null;
            for(Method m:c.getMethods()){
                Class<?>[] p=m.getParameterTypes();
                if(m.getName().equals("createCar")&&p.length==3 &&
                    Context.class.isAssignableFrom(p[0])&&ServiceConnection.class.isAssignableFrom(p[1])&&
                    (Handler.class.isAssignableFrom(p[2])||Looper.class.isAssignableFrom(p[2]))){factory=m;break;}
            }
            if(factory==null)throw new NoSuchMethodException("Car.createCar(Context, ServiceConnection, Handler)");
            ServiceConnection conn=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder binder){
                    if(!running)return;connected=true;status("Подключён: "+name.getClassName());
                    worker.execute(CarSensorProbe.this::readSensors);
                }
                @Override public void onServiceDisconnected(ComponentName name){connected=false;status("CarService отключён");}
            };
            car=factory.invoke(null,context,conn,null);
            if(car==null)throw new IllegalStateException("Car.createCar returned null");
            car.getClass().getMethod("connect").invoke(car);
        }catch(Throwable e){status("Car API: "+VehicleProbe.describe(e));unresolved("Нет доступа к Car API");}
    }
    private void unresolved(String message){
        synchronized(entries){for(Entry e:entries.values())if(e.state.equals("Не проверено"))e.state=message;}
        notifyUi();
    }
    private void update(Entry e,String st,String raw,String detail,boolean callback){
        synchronized(entries){
            e.state=st;e.value=raw;e.detail=detail;
            if(st.startsWith("Доступно")){e.lastAt=System.currentTimeMillis();if(callback)e.callbackCount++;}
        }
        notifyUi();
    }
    private void readSensors(){
        try{
            manager=car.getClass().getMethod("getCarManager",String.class).invoke(car,"sensor");
            if(manager==null)throw new IllegalStateException("getCarManager(sensor) returned null");
            Object raw=manager.getClass().getMethod("getSupportedSensors").invoke(manager);
            if(!(raw instanceof int[]))throw new IllegalStateException("getSupportedSensors type: "+(raw==null?"null":raw.getClass().getName()));
            int[] supported=(int[])raw;
            supportedState="Объявлены "+supported.length+" sensor ID";
            Class<?> constants=Class.forName("android.car.hardware.CarSensorManager");
            for(String name:NAMES){
                if(!running)break;
                Entry e=entries.get(name);
                int id;
                try{id=constants.getField(name).getInt(null);}
                catch(Throwable ex){update(e,"Константа отсутствует","",VehicleProbe.describe(ex),false);continue;}
                e.sensorId=id;
                boolean found=false;for(int x:supported)if(x==id){found=true;break;}
                if(!found){update(e,"Не поддерживается","","ID не найден в getSupportedSensors()",false);continue;}
                try{
                    Object event=manager.getClass().getMethod("getLatestSensorEvent",int.class).invoke(manager,id);
                    if(event==null)update(e,"Заявлен, но значения нет","","getLatestSensorEvent() = null",false);
                    else update(e,"Доступно",describeEvent(event),"getLatestSensorEvent()",false);
                }catch(Throwable ex){
                    update(e,hasSecurityException(ex)?"Доступ запрещён":"Ошибка чтения","",VehicleProbe.describe(ex),false);
                }
                subscribe(e);
            }
            status("Список датчиков обработан");
        }catch(Throwable error){status("CarSensorManager: "+VehicleProbe.describe(error));unresolved("Нет доступа к sensor manager");}
    }
    private synchronized void subscribe(Entry e){
        if(!running||manager==null)return;
        try{
            Method register=null;
            for(Method m:manager.getClass().getMethods()){
                Class<?>[] p=m.getParameterTypes();
                if(m.getName().equals("registerListener")&&p.length==3&&p[0].isInterface()
                    &&p[1]==int.class&&p[2]==int.class){register=m;break;}
            }
            if(register==null){e.detail+="; registerListener недоступен";notifyUi();return;}
            Class<?> iface=register.getParameterTypes()[0];
            if(listenerProxy==null){
                listenerProxy=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},(proxy,method,args)->{
                    if(method.getDeclaringClass()==Object.class){
                        if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                        if(method.getName().equals("equals"))return args!=null&&args.length>0&&proxy==args[0];
                        if(method.getName().equals("toString"))return "LadaTripSensorListener";
                    }
                    if(method.getName().equals("onSensorChanged")&&args!=null&&args.length>0&&args[0]!=null){
                        Object event=args[0];
                        Integer sensorId=readIntField(event,"sensorType");
                        if(sensorId!=null){synchronized(entries){
                            for(Entry ent:entries.values())if(ent.sensorId==sensorId){
                                update(ent,"Доступно (событие)",describeEvent(event),"CarSensorManager listener",true);
                                break;
                            }
                        }}
                    }
                    return null;
                });
                try{unregister=manager.getClass().getMethod("unregisterListener",iface);}
                catch(NoSuchMethodException ignored){}
            }
            int rate=3;
            try{rate=Class.forName("android.car.hardware.CarSensorManager").getField("SENSOR_RATE_NORMAL").getInt(null);}
            catch(Throwable ignored){}
            Object result=register.invoke(manager,listenerProxy,e.sensorId,rate);
            e.detail+=(Boolean.FALSE.equals(result)?"; регистрация отклонена":"; подписка зарегистрирована");
            notifyUi();
        }catch(Throwable t){e.detail+="; ошибка подписки: "+VehicleProbe.describe(t);notifyUi();}
    }
    private static Integer readIntField(Object obj,String name){
        try{return ((Number)obj.getClass().getField(name).get(obj)).intValue();}
        catch(Throwable ignored){return null;}
    }
    private static String describeEvent(Object event){
        StringBuilder b=new StringBuilder();
        for(String name:new String[]{"floatValues","intValues","longValues"}){
            try{
                Object values=event.getClass().getField(name).get(event);
                if(values==null||!values.getClass().isArray())continue;
                int n=Math.min(Array.getLength(values),8);
                b.append(name).append("=");
                b.append("[");
                for(int i=0;i<n;i++){if(i>0)b.append(",");b.append(String.valueOf(Array.get(values,i)));}
                if(Array.getLength(values)>8)b.append(",...");
                b.append("] ");
            }catch(Throwable ignored){}
        }
        return b.length()==0?"Событие доступно; формат неизвестен":b.toString().trim();
    }
    private static boolean hasSecurityException(Throwable t){
        while(t!=null){if(t instanceof SecurityException)return true;t=t.getCause();}return false;
    }
    public void stop(){
        running=false;
        if(manager!=null&&listenerProxy!=null&&unregister!=null)
            try{unregister.invoke(manager,listenerProxy);}catch(Throwable ignored){}
        if(car!=null)try{car.getClass().getMethod("disconnect").invoke(car);}catch(Throwable ignored){}
        worker.shutdownNow();
    }
}
