package ru.ladatrip.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Run on a worker thread. Read-only engine source discovery on the real R2 firmware.
 * No privileged service transactions, no shell, no upgrades or changes to the car.
 */
public final class EngineAccessAudit {
    private static final int RPM=0x11600305, COOLANT=0x11600301;
    private static final String BASE="android.car.permission.";
    private EngineAccessAudit(){}

    public static JSONObject inspect(Context c){
        JSONObject root=new JSONObject();
        long at=System.currentTimeMillis();
        try{
            root.put("app","LADA Trip Engine Sources 0.6");
            root.put("device",android.os.Build.MODEL);
            root.put("build",android.os.Build.DISPLAY);
            root.put("at_ms",at);
            root.put("read_only",true);
            JSONObject permissions=new JSONObject();
            for(String name:new String[]{"CAR_ENGINE_DETAILED","CAR_ENGINE_DETAILED_3P","CAR_POWERTRAIN","CAR_SPEED"}){
                String p=BASE+name;
                JSONObject item=new JSONObject();
                try{
                    PermissionInfo info=c.getPackageManager().getPermissionInfo(p,0);
                    int type=info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE;
                    item.put("protection",type==PermissionInfo.PROTECTION_DANGEROUS?"dangerous":
                        type==PermissionInfo.PROTECTION_NORMAL?"normal":"signature_or_privileged");
                    item.put("granted",c.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED);
                }catch(PackageManager.NameNotFoundException ex){item.put("declared",false);}
                permissions.put(name,item);
            }
            root.put("permissions",permissions);
            JSONObject carInfo=new JSONObject();
            root.put("android_automotive",carInfo);
            inspectCar(c,carInfo);
            JSONObject oem=new JSONObject();
            root.put("oem_services",oem);
            inspectYandex(c,oem);
            root.put("conclusion","RPM and coolant require actual readable values; a property ID or binder descriptor alone does not grant access.");
        }catch(Exception e){try{root.put("audit_error",e.toString());}catch(Exception ignored){}}
        return root;
    }

    private static void inspectCar(Context ctx,JSONObject report){
        final CountDownLatch connected=new CountDownLatch(1);
        final Object[] holder=new Object[1];
        Object car=null;
        try{
            Class<?> clazz=Class.forName("android.car.Car");
            Method factory=null;
            for(Method m:clazz.getMethods()){
                Class<?>[] p=m.getParameterTypes();
                if(m.getName().equals("createCar")&&p.length==3&&
                    Context.class.isAssignableFrom(p[0])&&
                    ServiceConnection.class.isAssignableFrom(p[1])&&
                    (Handler.class.isAssignableFrom(p[2])||Looper.class.isAssignableFrom(p[2]))){
                    factory=m;break;
                }
            }
            if(factory==null)throw new NoSuchMethodException("Car.createCar(Context,ServiceConnection,Handler)");
            ServiceConnection callback=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder binder){
                    holder[0]=name.flattenToShortString();connected.countDown();
                }
                @Override public void onServiceDisconnected(ComponentName name){}
            };
            car=factory.invoke(null,ctx,callback,null);
            if(car==null)throw new IllegalStateException("Car.createCar null");
            car.getClass().getMethod("connect").invoke(car);
            if(!connected.await(8,TimeUnit.SECONDS)){
                report.put("connection","TIMEOUT");return;
            }
            report.put("connection","CONNECTED");
            Object property=car.getClass().getMethod("getCarManager",String.class).invoke(car,"property");
            JSONObject values=new JSONObject();
            report.put("properties",values);
            if(property==null){report.put("property_manager","NULL");return;}
            List<?> configs=(List<?>)property.getClass().getMethod("getPropertyList").invoke(property);
            report.put("listed_count",configs==null?0:configs.size());
            inspectProp(property,configs,values,"RPM",RPM,ctx);
            inspectProp(property,configs,values,"COOLANT_C",COOLANT,ctx);
            Object sensor=car.getClass().getMethod("getCarManager",String.class).invoke(car,"sensor");
            JSONObject sensors=new JSONObject();
            report.put("car_sensors",sensors);
            if(sensor==null){sensors.put("state","manager_null");}
            else{
                int[] ids=(int[])sensor.getClass().getMethod("getSupportedSensors").invoke(sensor);
                sensors.put("supported_count",ids.length);
                for(String[] candidate:new String[][]{
                    {"SENSOR_TYPE_RPM","RPM"},
                    {"SENSOR_TYPE_ENGINE_COOLANT_TEMP","COOLANT_C"},
                    {"SENSOR_TYPE_COOLANT_TEMP","COOLANT_C_ALT"}}){
                    JSONObject item=new JSONObject();
                    String name=candidate[0];
                    try{
                        int id=Class.forName("android.car.hardware.CarSensorManager").getField(name).getInt(null);
                        boolean supported=false;for(int i:ids)if(i==id){supported=true;break;}
                        item.put("id",id);item.put("supported",supported);
                        if(supported){
                            try{
                                Object event=sensor.getClass().getMethod("getLatestSensorEvent",int.class).invoke(sensor,id);
                                if(event==null)item.put("read","null");
                                else{
                                    Object floats=event.getClass().getField("floatValues").get(event);
                                    Object ints=event.getClass().getField("intValues").get(event);
                                    String val=fmt(floats!=null&&Array.getLength(floats)>0?Array.get(floats,0):
                                        ints!=null&&Array.getLength(ints)>0?Array.get(ints,0):null);
                                    item.put("read",val);
                                    if(!"null".equals(val)&&candidate[1].equals("RPM"))
                                        VehicleLog.put(ctx,"sensor","RPM",val,"Engine audit: CarSensorManager");
                                    if(!"null".equals(val)&&candidate[1].equals("COOLANT_C"))
                                        VehicleLog.put(ctx,"sensor","COOLANT_C",val,"Engine audit: CarSensorManager");
                                }
                            }catch(Throwable ex){item.put("read_error",describe(ex));}
                        }
                    }catch(NoSuchFieldException ex){item.put("state","CONSTANT_MISSING");}
                    catch(Throwable ex){item.put("state",describe(ex));}
                    sensors.put(name,item);
                }
            }
        }catch(Throwable e){
            try{report.put("error",describe(e));}catch(Exception ignored){}
        }finally{
            if(car!=null)try{car.getClass().getMethod("disconnect").invoke(car);}catch(Throwable ignored){}
        }
    }
    private static void inspectProp(Object manager,List<?> configs,JSONObject out,String name,int id,Context c)
        throws Exception{
        JSONObject item=new JSONObject();
        item.put("id",String.format(Locale.US,"0x%08X",id));
        Object config=null;
        if(configs!=null)for(Object cfg:configs){
            try{
                int found=((Number)cfg.getClass().getMethod("getPropertyId").invoke(cfg)).intValue();
                if(found==id){config=cfg;break;}
            }catch(Exception ignored){}
        }
        item.put("listed",config!=null);
        if(config!=null){
            try{
                Class<?> type=(Class<?>)config.getClass().getMethod("getPropertyType").invoke(config);
                Object result=manager.getClass().getMethod("getProperty",Class.class,int.class,int.class)
                    .invoke(manager,type,id,0);
                if(result==null)item.put("read","null");
                else{
                    int status=((Number)result.getClass().getMethod("getStatus").invoke(result)).intValue();
                    item.put("status",status);
                    if(status==0){
                        String v=fmt(result.getClass().getMethod("getValue").invoke(result));
                        item.put("read",v);
                        if(!"null".equals(v))VehicleLog.put(c,"property",name,v,"Engine audit: protected property");
                    }
                }
            }catch(Throwable ex){item.put("read_error",describe(ex));}
        }else item.put("read","not_listed_or_filtered");
        out.put(name,item);
    }
    private static void inspectYandex(Context c,JSONObject out){
        try{
            PackageInfo pkg=c.getPackageManager().getPackageInfo("ru.yandex.yap.hal.services",PackageManager.GET_SERVICES);
            if(pkg.services!=null){
                org.json.JSONArray arr=new org.json.JSONArray();
                for(ServiceInfo s:pkg.services){
                    if(s.name.toLowerCase(Locale.ROOT).contains("can")||
                       s.name.toLowerCase(Locale.ROOT).contains("sensor")){
                        JSONObject item=new JSONObject();
                        item.put("service",s.name);item.put("exported",s.exported);
                        item.put("required_permission",s.permission==null?JSONObject.NULL:s.permission);
                        arr.put(item);
                    }
                }
                out.put("can_or_sensor_service_candidates",arr);
            }
            out.put("note","Service names and permissions only; no unknown binder methods are called.");
        }catch(Throwable ex){try{out.put("error",describe(ex));}catch(Exception ignored){}}
    }
    private static String fmt(Object value){
        if(value==null)return "null";
        if(!value.getClass().isArray())return String.valueOf(value);
        StringBuilder b=new StringBuilder("[");
        for(int i=0;i<Math.min(6,Array.getLength(value));i++){
            if(i>0)b.append(',');b.append(Array.get(value,i));
        }
        return b.append(']').toString();
    }
    private static String describe(Throwable ex){
        while(ex instanceof InvocationTargetException&&((InvocationTargetException)ex).getCause()!=null)
            ex=ex.getCause();
        String m=ex.getMessage();
        if(m==null)m="";
        m=m.replace('\n',' ').replace('\r',' ');
        return ex.getClass().getSimpleName()+(m.isEmpty()?"":": "+m.substring(0,Math.min(120,m.length())));
    }
}
