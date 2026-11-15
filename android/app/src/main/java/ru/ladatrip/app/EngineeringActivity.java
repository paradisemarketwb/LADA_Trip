package ru.ladatrip.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Opt-in engineering diagnostics; never sends car commands or changes GPS recorder. */
public final class EngineeringActivity extends Activity {
    private static final int PERMISSION_CODE=510, EXPORT_CODE=511;
    private static final String[] CAR_PERMISSIONS={
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_POWER",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
        "android.car.permission.CAR_ENGINE_DETAILED",
        "android.car.permission.CAR_DIAGNOSTICS",
        "android.car.permission.CAR_MILEAGE",
        "android.car.permission.CAR_VENDOR_EXTENSION"
    };
    private VehicleProbe vehicle;
    private CarSensorProbe sensors;
    private CanServiceProbe can;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView permissionsView,vehicleView,sensorView,canView,accessView;
    private final Runnable refreshTick=new Runnable(){@Override public void run(){refresh();handler.postDelayed(this,2000);}};
    private String pendingExport;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        vehicle=new VehicleProbe(this,this::refresh);
        sensors=new CarSensorProbe(this,this::refresh);
        can=new CanServiceProbe(this,this::refresh);
        draw();refresh();
    }
    private TextView label(String t,int size){
        TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(Color.rgb(20,35,55));
        v.setPadding(0,dp(7),0,dp(7));return v;
    }
    private void add(LinearLayout root,View v){root.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    private Button button(LinearLayout root,String caption,View.OnClickListener click){
        Button b=new Button(this);b.setText(caption);b.setAllCaps(false);b.setTextSize(14);add(root,b);b.setOnClickListener(click);return b;
    }
    private void draw(){
        ScrollView sc=new ScrollView(this);sc.setBackgroundColor(Color.rgb(245,248,252));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(18),dp(22),dp(30));sc.addView(root);setContentView(sc);
        add(root,label("LADA TRIP · ИНЖЕНЕРНЫЙ РЕЖИМ",20));
        add(root,label("Только чтение. Все диагностические действия запускаются вручную; GPS-мониторинг и Telegram работают отдельно.",13));
        add(root,label("УРОВЕНЬ 1 · ANDROID AUTOMOTIVE",17));
        permissionsView=label("Проверяем разрешения…",12);add(root,permissionsView);
        button(root,"Запросить доступные разрешения автомобиля",v->askPermissions());
        button(root,"Проверить свойства CarPropertyManager",v->{
            vehicle.stop();vehicle=new VehicleProbe(this,this::refresh);vehicle.start();refresh();
        });
        vehicleView=label("Ожидание запуска",13);add(root,vehicleView);
        add(root,label("ДОПОЛНИТЕЛЬНО · CAR SENSOR MANAGER",17));
        add(root,label("Независимая проверка встроенных сенсоров Android Automotive. Сырые массивы значений без неподтверждённой расшифровки.",13));
        button(root,"Проверить CarSensorManager",v->{
            sensors.stop();sensors=new CarSensorProbe(this,this::refresh);sensors.start();refresh();
        });
        sensorView=label("Ожидание запуска",13);add(root,sensorView);
        add(root,label("УРОВЕНЬ 2 · YANDEX CAN",17));
        add(root,label("Только проверка exported/permission, bindService и AIDL-дескриптора. Никаких неизвестных транзакций CAN.",13));
        button(root,"Проверить CanService",v->can.start());
        canView=label("Ожидание запуска",13);add(root,canView);
        add(root,label("УРОВЕНЬ 3 · ИНЖЕНЕРНОЕ МЕНЮ",17));
        add(root,label("Необязательный резерв. Требует отдельного ручного включения Accessibility; читает только доступный текст инженерного меню Humax, без автоматического открытия и нажатий.",13));
        button(root,"Открыть настройки доступности Android",v->{
            try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
            catch(Exception e){toast("Настройки недоступны: "+VehicleProbe.describe(e));}
        });
        button(root,"Открыть инженерное меню CAN вручную",v->{
            Intent i=new Intent().setComponent(new ComponentName(
                "com.humaxdigital.avtovaz.engineeringmode",
                "com.humaxdigital.avtovaz.engineeringmode.can.CanInfoActivity"));
            try{startActivity(i);}catch(Exception e){toast("Инженерное меню недоступно стороннему APK: "+VehicleProbe.describe(e));}
        });
        button(root,"Показать считанные значения меню",v->refresh());
        accessView=label("Ожидание",13);add(root,accessView);
        add(root,label("ОТЧЁТ И ТЕСТИРОВАНИЕ",17));
        button(root,"Обновить состояния",v->refresh());
        button(root,"Сохранить инженерный отчёт JSON на USB",v->export());
        add(root,label("Не использовать как прибор безопасности или для управления автомобилем. Единицы топлива и доступность свойств требуют проверки.",12));
    }
    private void refresh(){
        if(permissionsView==null)return;
        permissionsView.setText(permissionReport());
        vehicleView.setText(vehicle.display());sensorView.setText(sensors.display());canView.setText(can.display());
        accessView.setText("Accessibility: "+(isAccessibilityEnabled()?"включено":"выключено")+
            "\n"+EngineeringAccessibilityService.snapshot());
    }
    private boolean isAccessibilityEnabled(){
        try{
            String settings=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(settings==null)return false;
            ComponentName ours=new ComponentName(this,EngineeringAccessibilityService.class);
            for(String token:settings.split(":")){
                ComponentName enabled=ComponentName.unflattenFromString(token);
                if(ours.equals(enabled))return true;
            }
            return false;
        }catch(Exception e){return false;}
    }
    private String permissionReport(){
        StringBuilder report=new StringBuilder("Android "+Build.VERSION.RELEASE+" · "+Build.DISPLAY+"\n");
        for(String p:CAR_PERMISSIONS){
            String simple=p.substring(p.lastIndexOf('.')+1);
            try{
                PermissionInfo info=getPackageManager().getPermissionInfo(p,0);
                int protection=info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE;
                String kind=protection==PermissionInfo.PROTECTION_DANGEROUS?"dangerous":
                    protection==PermissionInfo.PROTECTION_NORMAL?"normal":"signature/другое";
                boolean granted=checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;
                report.append(simple).append(": ").append(kind).append(" · ").append(granted?"выдано":"не выдано").append('\n');
            }catch(PackageManager.NameNotFoundException e){report.append(simple).append(": в системе не объявлено\n");}
        }return report.toString();
    }
    private void askPermissions(){
        ArrayList<String> want=new ArrayList<>();
        for(String permission:CAR_PERMISSIONS){
            try{
                PermissionInfo info=getPackageManager().getPermissionInfo(permission,0);
                if((info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE)==PermissionInfo.PROTECTION_DANGEROUS&&
                    checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)want.add(permission);
            }catch(PackageManager.NameNotFoundException ignored){}
        }
        if(want.isEmpty()){toast("Нет новых разрешений dangerous для запроса");refresh();return;}
        requestPermissions(want.toArray(new String[0]),PERMISSION_CODE);
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants){
        super.onRequestPermissionsResult(code,names,grants);
        if(code==PERMISSION_CODE){refresh();toast("Результаты разрешений записаны в отчёт");}
    }
    private JSONObject report(){
        JSONObject out=new JSONObject();
        try{
            out.put("application","LADA Trip Engineering 0.3.0");
            out.put("time",new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ",Locale.US).format(new Date()));
            out.put("device_build",Build.DISPLAY);out.put("device_model",Build.MODEL);
            out.put("read_only",true);out.put("permission_report",permissionReport());
            out.put("android_car",vehicle.snapshot());out.put("car_sensors",sensors.snapshot());out.put("yandex_can",can.snapshot());
            out.put("accessibility_enabled",isAccessibilityEnabled());
            out.put("accessibility_readings",EngineeringAccessibilityService.snapshot());
            out.put("gps_trip_unmodified",true);
            out.put("caveat","Only a device test can confirm accessible automotive values. No secrets/GPS history exported.");
        }catch(Exception ignored){}return out;
    }
    private void export(){
        try{pendingExport=report().toString(2);}catch(Exception e){toast("Не удалось сформировать JSON");return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"lada-trip-engineering.json");
        try{startActivityForResult(i,EXPORT_CODE);}catch(Exception e){toast("Выбор USB недоступен: "+VehicleProbe.describe(e));}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==EXPORT_CODE && resultCode==RESULT_OK && data!=null&&data.getData()!=null&&pendingExport!=null){
            try(OutputStream output=getContentResolver().openOutputStream(data.getData())){
                if(output==null)throw new IllegalStateException("Нет потока для записи");
                output.write(pendingExport.getBytes(StandardCharsets.UTF_8));output.flush();toast("Отчёт сохранён");
            }catch(Exception e){toast("Ошибка экспорта: "+VehicleProbe.describe(e));}
            finally{pendingExport=null;}
        }
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refreshTick);handler.post(refreshTick);}
    @Override protected void onPause(){handler.removeCallbacks(refreshTick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refreshTick);vehicle.stop();sensors.stop();can.stop();super.onDestroy();}
}
