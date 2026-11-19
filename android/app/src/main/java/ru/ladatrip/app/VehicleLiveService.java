package ru.ladatrip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Continuous, read-only car telemetry. No OEM write, reset, reboot or upgrade calls. */
public final class VehicleLiveService extends Service {
    public static final String ACTION_STOP="ru.ladatrip.app.VEHICLE_STOP";
    private static final String CHANNEL="vehicle_live";
    private static final int NOTIFICATION_ID=184;
    private static final class Signal {
        final String name,permission;final int id;
        Signal(String n,int id,String permission){this.name=n;this.id=id;this.permission="android.car.permission."+permission;}
    }
    private static final Signal[] PROPERTIES={
        new Signal("SPEED",0x11600207,"CAR_SPEED"),
        new Signal("FUEL_L",0x11600307,"CAR_ENERGY"),
        new Signal("FUEL_LOW",0x11200405,"CAR_ENERGY"),
        new Signal("IGNITION",0x11400409,"CAR_POWER"),
        new Signal("GEAR",0x11400401,"CAR_POWERTRAIN"),
        new Signal("HANDBRAKE",0x11200402,"CAR_POWERTRAIN"),
        new Signal("OUTSIDE_C",0x11600703,"CAR_EXTERIOR_ENVIRONMENT"),
        new Signal("RPM",0x11600305,"CAR_ENGINE_DETAILED"),
        new Signal("COOLANT_C",0x11600301,"CAR_ENGINE_DETAILED"),
        new Signal("ODOMETER",0x11600204,"CAR_MILEAGE"),
        new Signal("STEER_RAW",0x2140F006,"CAR_VENDOR_EXTENSION"),
        new Signal("REVERSE",0x2140F003,"CAR_VENDOR_EXTENSION")
    };
    private static final class Sensor {
        final String name; final int id;
        Sensor(String n,int i){name=n;id=i;}
    }
    private static final Sensor[] SENSORS={
        new Sensor("SPEED",291504647),
        new Sensor("RPM",291504901),
        new Sensor("GEAR",289408000),
        new Sensor("IGNITION",289408009),
        new Sensor("FUEL_L",291504903),
        new Sensor("HANDBRAKE",287310850),
        new Sensor("ODOMETER",291504644)
    };
    private long lastTripSpeedAt;
    private volatile Object car,propertyManager,sensorManager;
    private volatile Map<Integer,Object> configurations=new HashMap<>();
    private volatile int[] supportedSensors=new int[0];
    private volatile boolean connected;
    private ScheduledExecutorService executor;
    private ServiceConnection serviceConnection;
    private int connectionFailures;
    private CanJourneyStore canTrips;
    private long lastReconnectAttempt;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"LADA Trip — автомобиль",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,VehicleDashboardActivity.class),PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_trip)
            .setContentTitle("LADA Trip · данные автомобиля")
            .setContentText("Чтение CarService, без команд автомобилю")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(NOTIFICATION_ID,n);
        canTrips=new CanJourneyStore(this);
        canTrips.resumeRecording();
        VehicleLog.running(this,true);
        VehicleLog.state(this,"Подключение к Android CarService");
        executor=Executors.newSingleThreadScheduledExecutor();
        connect();
        executor.scheduleWithFixedDelay(this::pollSafely,2,2,TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent i,int flags,int startId){
        if(i!=null && ACTION_STOP.equals(i.getAction())){
            VehicleLog.running(this,false);stopForeground(true);stopSelf();return START_NOT_STICKY;
        }
        return START_STICKY;
    }
    private void connect(){
        try{
            Class<?> carClass=Class.forName("android.car.Car");
            Method method=null;
            for(Method m:carClass.getMethods()){
                Class<?>[] p=m.getParameterTypes();
                if(m.getName().equals("createCar")&&p.length==3
                    &&Context.class.isAssignableFrom(p[0])
                    &&ServiceConnection.class.isAssignableFrom(p[1])
                    &&(Handler.class.isAssignableFrom(p[2])||Looper.class.isAssignableFrom(p[2]))){
                    method=m;break;
                }
            }
            if(method==null)throw new NoSuchMethodException("Car.createCar with ServiceConnection");
            serviceConnection=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder binder){
                    connected=true;
                    VehicleLog.state(VehicleLiveService.this,"Подключён к CarService");
                    if(executor!=null && !executor.isShutdown())executor.execute(VehicleLiveService.this::prepareManagers);
                }
                @Override public void onServiceDisconnected(ComponentName name){
                    connected=false;propertyManager=null;sensorManager=null;
                    VehicleLog.state(VehicleLiveService.this,"CarService отключился");
                }
            };
            car=method.invoke(null,this,serviceConnection,null);
            if(car==null)throw new IllegalStateException("Car.createCar returned null");
            car.getClass().getMethod("connect").invoke(car);
        }catch(Throwable t){
            VehicleLog.state(this,"Не удалось подключиться: "+describe(t));
        }
    }
    private void prepareManagers(){
        try{
            Object result=car.getClass().getMethod("getCarManager",String.class).invoke(car,"property");
            propertyManager=result;
            Map<Integer,Object> cfg=new HashMap<>();
            if(result!=null){
                Object raw=result.getClass().getMethod("getPropertyList").invoke(result);
                if(raw instanceof List)for(Object item:(List<?>)raw){
                    int id=((Number)item.getClass().getMethod("getPropertyId").invoke(item)).intValue();
                    cfg.put(id,item);
                }
            }
            configurations=cfg;
            VehicleLog.state(this,"CarService подключён · свойств "+cfg.size());
        }catch(Throwable t){VehicleLog.state(this,"Ошибка CarPropertyManager: "+describe(t));}
        try{
            Object result=car.getClass().getMethod("getCarManager",String.class).invoke(car,"sensor");
            sensorManager=result;
            if(result!=null){
                Object raw=result.getClass().getMethod("getSupportedSensors").invoke(result);
                if(raw instanceof int[])supportedSensors=(int[])raw;
            }
        }catch(Throwable t){android.util.Log.w("LadaVehicle","CarSensorManager not available",t);}
    }
    private void pollSafely(){
        if(!VehicleLog.running(this))return;
        try{
            if(!connected||propertyManager==null){
                if(++connectionFailures%10==1)
                    VehicleLog.state(this,connected?"CarPropertyManager недоступен":"Ожидаем подключение CarService");
                if(!connected && System.currentTimeMillis()-lastReconnectAttempt>30000){
                    lastReconnectAttempt=System.currentTimeMillis();
                    if(car!=null)try{car.getClass().getMethod("disconnect").invoke(car);}
                        catch(Throwable ignored){}
                    car=null;
                    new Handler(Looper.getMainLooper()).post(this::connect);
                }
            }else{connectionFailures=0;pollProperties();}
            if(sensorManager!=null)pollSensors();
            // Independent distance-only trip recording while GPS is unavailable.
            VehicleLog.Reading speed=VehicleLog.speed(this);
            long now=System.currentTimeMillis();
            if(canTrips!=null && speed.at!=lastTripSpeedAt &&
                VehicleDisplayFormat.fresh(speed.at,now,6000)){
                try{
                    double kmh=Double.parseDouble(speed.value);
                    Integer ignition=null;
                    if(VehicleDisplayFormat.fresh(VehicleLog.at(this,"IGNITION"),now,30000))
                        try{ignition=Integer.parseInt(VehicleLog.value(this,"IGNITION"));}
                        catch(NumberFormatException ignored){}
                    canTrips.sample(kmh,ignition,speed.source,speed.at);
                    lastTripSpeedAt=speed.at;
                }catch(NumberFormatException ignored){}
            }
        }catch(Throwable t){VehicleLog.state(this,"Ошибка чтения: "+describe(t));}
    }
    private void pollProperties(){
        Map<Integer,Object> cfgs=configurations;
        for(Signal signal:PROPERTIES){
            if(!VehicleLog.running(this))return;
            if(checkSelfPermission(signal.permission)!=PackageManager.PERMISSION_GRANTED)continue;
            Object config=cfgs.get(signal.id);
            if(config==null)continue;
            try{
                Class<?> valueType=(Class<?>)config.getClass().getMethod("getPropertyType").invoke(config);
                Object wrapper=propertyManager.getClass()
                    .getMethod("getProperty",Class.class,int.class,int.class)
                    .invoke(propertyManager,valueType,signal.id,0);
                if(wrapper==null)continue;
                int status=((Number)wrapper.getClass().getMethod("getStatus").invoke(wrapper)).intValue();
                if(status!=0)continue;
                Object raw=wrapper.getClass().getMethod("getValue").invoke(wrapper);
                if(raw==null)continue;
                String display=formatProperty(signal.name,raw);
                if(display==null)continue;
                VehicleLog.put(this,"property",signal.name,display,
                    "ID=0x"+Integer.toHexString(signal.id)+
                        ("SPEED".equals(signal.name)?"; Humax R2; raw="+raw+" km/h":
                        "; direct CarPropertyManager"));
            }catch(Throwable t){
                android.util.Log.w("LadaVehicle","Property "+signal.name+" unavailable: "+describe(t));
            }
        }
    }
    private static String formatProperty(String name,Object raw){
        if(raw instanceof Number){
            double number=((Number)raw).doubleValue();
            // R2 Humax VHAL returns a speed matching km/h despite the AOSP m/s contract.
            // Road test: previous 122.5 in-app was 34.0 raw x 3.6, while dashboard showed 38.
            if("SPEED".equals(name))return speedValue(number,"property");
            if("FUEL_L".equals(name)||"OUTSIDE_C".equals(name)||"COOLANT_C".equals(name))
                return String.format(Locale.US,"%.1f",number);
            if("IGNITION".equals(name))return String.valueOf(((Number)raw).intValue());
            return String.valueOf(raw);
        }
        if(raw.getClass().isArray()){
            StringBuilder out=new StringBuilder("[");
            for(int i=0;i<Math.min(Array.getLength(raw),8);i++){
                if(i>0)out.append(',');out.append(Array.get(raw,i));
            }
            return out.append(']').toString();
        }
        return String.valueOf(raw);
    }
    private void pollSensors(){
        int[] supported=supportedSensors;
        Object manager=sensorManager;
        for(Sensor signal:SENSORS){
            boolean available=false;
            for(int id:supported)if(id==signal.id){available=true;break;}
            if(!available)continue;
            try{
                Object event=manager.getClass().getMethod("getLatestSensorEvent",int.class).invoke(manager,signal.id);
                if(event==null)continue;
                Object floats=event.getClass().getField("floatValues").get(event);
                Object ints=event.getClass().getField("intValues").get(event);
                Object data=(floats!=null&&Array.getLength(floats)>0)?Array.get(floats,0):
                    (ints!=null&&Array.getLength(ints)>0)?Array.get(ints,0):null;
                if(data==null)continue;
                String value;
                if("SPEED".equals(signal.name)&&data instanceof Number)
                    value=speedValue(((Number)data).doubleValue(),"sensor");
                else if(("FUEL_L".equals(signal.name))&&data instanceof Number)
                    value=String.format(Locale.US,"%.1f",((Number)data).doubleValue());
                else if("HANDBRAKE".equals(signal.name) && data instanceof Number){
                    int raw=((Number)data).intValue();
                    value=raw==1?"Затянут":raw==0?"Отпущен":"Неизвестный код "+raw;
                }
                else value=String.valueOf(data);
                if(value==null)continue;
                VehicleLog.put(this,"sensor",signal.name,value,
                    "CarSensorManager.getLatestSensorEvent("+signal.id+"); "+
                        ("HANDBRAKE".equals(signal.name)?"parking intValues[0] 1=applied,0=not applied; raw="+data:
                        "raw sensor data="+data+("SPEED".equals(signal.name)?" m/s":"")));
            }catch(Throwable t){android.util.Log.d("LadaVehicle","Sensor "+signal.name+" unavailable: "+describe(t));}
        }
    }
    private static String speedValue(double raw,String source){
        double kmh=VehicleDisplayFormat.speedKmh(raw,source);
        return Double.isFinite(kmh)?String.format(Locale.US,"%.1f",kmh):null;
    }
    private static String describe(Throwable t){
        while(t instanceof InvocationTargetException&&((InvocationTargetException)t).getCause()!=null)
            t=t.getCause();
        String text=t.getMessage();
        if(text==null)text="";
        text=text.replace('\n',' ').replace('\r',' ');
        return t.getClass().getSimpleName()+(text.isEmpty()?"":": "+text.substring(0,Math.min(text.length(),100)));
    }
    @Override public void onDestroy(){
        VehicleLog.running(this,false);
        if(executor!=null)executor.shutdownNow();
        if(canTrips!=null){try{canTrips.finish();}finally{canTrips.close();canTrips=null;}}
        if(car!=null)try{car.getClass().getMethod("disconnect").invoke(car);}catch(Throwable ignored){}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
