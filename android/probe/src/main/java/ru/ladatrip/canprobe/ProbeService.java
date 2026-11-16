package ru.ladatrip.canprobe;

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
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

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

/** Foreground read-only poller for Android Automotive R2. No CAN writes. */
public final class ProbeService extends Service {
    private static final String CHANNEL="can_probe_test";
    private static final int NOTIFICATION_ID=9051;
    private static final class Prop {
        final String name,permission;
        final int id;
        Prop(String name,int id,String permission){
            this.name=name;this.id=id;this.permission="android.car.permission."+permission;
        }
    }
    private static final Prop[] PROPS={
        new Prop("SPEED",0x11600207,"CAR_SPEED"),
        new Prop("FUEL_LEVEL",0x11600307,"CAR_ENERGY"),
        new Prop("FUEL_LOW",0x11200405,"CAR_ENERGY"),
        new Prop("IGNITION",0x11400409,"CAR_POWER"),
        new Prop("GEAR_SELECTION",0x11400400,"CAR_POWERTRAIN"),
        new Prop("CURRENT_GEAR",0x11400401,"CAR_POWERTRAIN"),
        new Prop("PARKING_BRAKE",0x11200402,"CAR_POWERTRAIN"),
        new Prop("OUTSIDE_TEMP",0x11600703,"CAR_EXTERIOR_ENVIRONMENT"),
        new Prop("RPM",0x11600305,"CAR_ENGINE_DETAILED"),
        new Prop("ODOMETER",0x11600204,"CAR_MILEAGE"),
        new Prop("STEERING_SAS",0x2140F006,"CAR_VENDOR_EXTENSION"),
        new Prop("REVERSE_GEAR",0x2140F003,"CAR_VENDOR_EXTENSION")
    };
    private static final class Sensor {
        final String name;final int id;
        Sensor(String n,int id){name=n;this.id=id;}
    }
    // IDs confirmed by the previous live R2 diagnostic, not CAN arbitration IDs.
    private static final Sensor[] SENSORS={
        new Sensor("SPEED",291504647),
        new Sensor("RPM",291504901),
        new Sensor("GEAR",289408000),
        new Sensor("IGNITION",289408009),
        new Sensor("FUEL_LEVEL",291504903),
        new Sensor("PARKING_BRAKE",287310850),
        new Sensor("ODOMETER",291504644),
        new Sensor("NIGHT",287310855)
    };
    private final Handler main=new Handler(Looper.getMainLooper());
    private ScheduledExecutorService scheduler;
    private volatile Object car, properties, sensors;
    private volatile Map<Integer,Object> configs=new HashMap<>();
    private volatile int[] supportedSensors=new int[0];
    private volatile String connection="WAITING";
    private ServiceConnection carConnection,yandexConnection;
    private boolean canBound;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"CAN Probe Test",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,ProbeActivity.class),PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("CAN Probe: тест чтения")
            .setContentText("Запись изменений активна — только чтение")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(NOTIFICATION_ID,notification);
        connectCar();
        probeYandexBinder();
        scheduler=Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollSafely,1,2,TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!CaptureStore.active(this)){stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    private void record(String source,String signal,String value,String detail){
        CaptureStore.event(this,source,signal,value,detail);
    }
    private void connectCar(){
        try{
            Class<?> cls=Class.forName("android.car.Car");
            Method factory=null;
            for(Method m:cls.getMethods()){
                Class<?>[] p=m.getParameterTypes();
                if(m.getName().equals("createCar")&&p.length==3&&
                   Context.class.isAssignableFrom(p[0])&&ServiceConnection.class.isAssignableFrom(p[1])&&
                   (Handler.class.isAssignableFrom(p[2])||Looper.class.isAssignableFrom(p[2]))){
                    factory=m;break;
                }
            }
            if(factory==null)throw new NoSuchMethodException("Car.createCar with ServiceConnection");
            carConnection=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder service){
                    connection="CONNECTED";
                    record("CAR","CONNECTION","CONNECTED",name.getClassName());
                    try{
                        properties=car.getClass().getMethod("getCarManager",String.class).invoke(car,"property");
                        record("CAR","PROPERTY_MANAGER",properties==null?"NULL":"AVAILABLE","");
                        if(properties!=null){
                            Object raw=properties.getClass().getMethod("getPropertyList").invoke(properties);
                            Map<Integer,Object> map=new HashMap<>();
                            if(raw instanceof List)for(Object cfg:(List<?>)raw){
                                int id=((Number)cfg.getClass().getMethod("getPropertyId").invoke(cfg)).intValue();
                                map.put(id,cfg);
                            }
                            configs=map;
                            record("CAR","PROPERTY_CONFIG_COUNT",String.valueOf(map.size()),
                                "List of properties visible to unprivileged APK");
                        }
                    }catch(Throwable ex){record("CAR","PROPERTY_INIT_ERROR",error(ex),"");}
                    try{
                        sensors=car.getClass().getMethod("getCarManager",String.class).invoke(car,"sensor");
                        record("SENSOR","MANAGER",sensors==null?"NULL":"AVAILABLE","");
                        if(sensors!=null){
                            supportedSensors=(int[])sensors.getClass().getMethod("getSupportedSensors").invoke(sensors);
                            record("SENSOR","SUPPORTED_COUNT",String.valueOf(supportedSensors.length),"");
                        }
                    }catch(Throwable ex){record("SENSOR","INIT_ERROR",error(ex),"");}
                }
                @Override public void onServiceDisconnected(ComponentName name){
                    connection="DISCONNECTED";properties=null;sensors=null;
                    record("CAR","CONNECTION","DISCONNECTED","");
                }
            };
            car=factory.invoke(null,this,carConnection,null);
            if(car==null)throw new IllegalStateException("Car.createCar returns null");
            car.getClass().getMethod("connect").invoke(car);
            record("CAR","CONNECT_REQUEST","SENT","Car.createCar(...).connect()");
        }catch(Throwable ex){
            connection="ERROR";
            record("CAR","CONNECT_ERROR",error(ex),"");
        }
    }
    private void probeYandexBinder(){
        try{
            ComponentName name=new ComponentName("ru.yandex.yap.hal.services","ru.yandex.yap.hal.services.CanService");
            ServiceInfo info=getPackageManager().getServiceInfo(name,0);
            record("YANDEX","EXPORTED",String.valueOf(info.exported),"required="+info.permission);
            if(!info.exported)return;
            if(info.permission!=null && checkSelfPermission(info.permission)!=PackageManager.PERMISSION_GRANTED){
                record("YANDEX","BIND","PERMISSION_DENIED",info.permission);return;
            }
            yandexConnection=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName c,IBinder binder){
                    try{record("YANDEX","BINDER_DESCRIPTOR",binder.getInterfaceDescriptor(),"Read-only descriptor query");}
                    catch(Exception ex){record("YANDEX","BINDER_ERROR",error(ex),"");}
                }
                @Override public void onServiceDisconnected(ComponentName c){
                    record("YANDEX","BINDER","DISCONNECTED","");
                }
            };
            canBound=bindService(new Intent().setComponent(name),yandexConnection,Context.BIND_AUTO_CREATE);
            record("YANDEX","BIND",canBound?"REQUESTED":"FALSE","No unknown AIDL method calls");
        }catch(Throwable ex){record("YANDEX","ERROR",error(ex),"");}
    }
    private void pollSafely(){
        if(!CaptureStore.active(this))return;
        CaptureStore.count(this,"direct_polls");
        try{
            if(properties!=null)pollProperties();
            else record("CAR","POLL_STATUS",connection.equals("ERROR")?"CONNECT_ERROR":"WAITING_FOR_MANAGER",connection);
            if(sensors!=null)pollSensors();
            else record("SENSOR","POLL_STATUS","WAITING_FOR_MANAGER","");
        }catch(Throwable ex){record("SYSTEM","POLL_ERROR",error(ex),"");}
    }
    private void pollProperties(){
        Map<Integer,Object> cfgs=configs;
        for(Prop p:PROPS){
            if(!CaptureStore.active(this))break;
            if(checkSelfPermission(p.permission)!=PackageManager.PERMISSION_GRANTED){
                record("PROPERTY",p.name,"PERMISSION_DENIED",p.permission);continue;
            }
            Object cfg=cfgs.get(p.id);
            if(cfg==null){
                record("PROPERTY",p.name,"NOT_LISTED",String.format(Locale.US,"0x%08X",p.id));
                continue;
            }
            try{
                Class<?> type=(Class<?>)cfg.getClass().getMethod("getPropertyType").invoke(cfg);
                Object val=properties.getClass().getMethod("getProperty",Class.class,int.class,int.class)
                    .invoke(properties,type,p.id,0);
                if(val==null){record("PROPERTY",p.name,"NO_VALUE","null");continue;}
                int status=((Number)val.getClass().getMethod("getStatus").invoke(val)).intValue();
                if(status!=0){record("PROPERTY",p.name,"STATUS_"+status,"status from CarPropertyValue");continue;}
                Object raw=val.getClass().getMethod("getValue").invoke(val);
                record("PROPERTY",p.name,"VALUE: "+format(raw),
                    "0x"+Integer.toHexString(p.id)+"; raw units; sampled every 2 sec");
            }catch(Throwable ex){record("PROPERTY",p.name,"ERROR: "+error(ex),"");}
        }
    }
    private void pollSensors(){
        for(Sensor s:SENSORS){
            boolean declared=false;
            for(int id:supportedSensors)if(id==s.id){declared=true;break;}
            if(!declared){record("SENSOR",s.name,"NOT_SUPPORTED","ID "+s.id+" not in getSupportedSensors()");continue;}
            try{
                Object val=sensors.getClass().getMethod("getLatestSensorEvent",int.class).invoke(sensors,s.id);
                if(val==null){record("SENSOR",s.name,"NO_VALUE","null");continue;}
                StringBuilder sb=new StringBuilder();
                for(String field:new String[]{"floatValues","intValues","longValues"}){
                    try{
                        Object arr=val.getClass().getField(field).get(val);
                        if(arr!=null && arr.getClass().isArray()){
                            sb.append(field).append("=[");
                            for(int i=0;i<Math.min(6,Array.getLength(arr));i++){
                                if(i>0)sb.append(",");
                                sb.append(Array.get(arr,i));
                            }
                            sb.append("] ");
                        }
                    }catch(Exception ignored){}
                }
                record("SENSOR",s.name,"VALUE: "+sb.toString().trim(),
                    "getLatestSensorEvent("+s.id+"); raw arrays");
            }catch(Throwable ex){record("SENSOR",s.name,"ERROR: "+error(ex),"");}
        }
    }
    private static String format(Object raw){
        if(raw==null)return "null";
        if(!raw.getClass().isArray())return String.valueOf(raw);
        StringBuilder b=new StringBuilder("[");
        for(int i=0;i<Math.min(Array.getLength(raw),12);i++){
            if(i>0)b.append(",");b.append(Array.get(raw,i));
        }
        return b.append("]").toString();
    }
    private static String error(Throwable e){
        while(e instanceof InvocationTargetException&&((InvocationTargetException)e).getCause()!=null)
            e=e.getCause();
        String s=e.getMessage();
        return e.getClass().getSimpleName()+(s==null?"":": "+s.replace('\n',' ').substring(0,Math.min(s.length(),100)));
    }
    @Override public void onDestroy(){
        if(scheduler!=null)scheduler.shutdownNow();
        if(canBound&&yandexConnection!=null)try{unbindService(yandexConnection);}catch(Exception ignored){}
        if(car!=null)try{car.getClass().getMethod("disconnect").invoke(car);}catch(Exception ignored){}
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
