package ru.ladatrip.app;

import android.app.Activity;
import android.app.AlertDialog;
import org.json.JSONObject;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/** Portrait dashboard of last-known vehicle readings. Never writes to ECU or opens hidden menus. */
public final class VehicleDashboardActivity extends Activity {
    private static final int CODE_PERMISSION=621,CODE_EXPORT=622;
    private static final int BG=Color.rgb(243,247,252);
    private static final int DARK=Color.rgb(20,34,54);
    private static final int BLUE=Color.rgb(55,101,202);
    private static final int GREY=Color.rgb(113,127,144);
    private static final String[] PERMISSIONS={
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_POWER",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
    };
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.Map<String,TextView> fields=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,TextView> meta=new java.util.LinkedHashMap<>();
    private TextView speedText,speedMeta,stateText,elmStatus,auditStatus;
    private volatile JSONObject latestEngineAudit;
    private String pendingExport;
    private final Runnable tick=new Runnable(){@Override public void run(){refresh();handler.postDelayed(this,1500);}};
    private int dp(float f){return (int)(f*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable bg(int color,int r){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(r));return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private Button button(String text,boolean primary){
        Button b=new Button(this);b.setAllCaps(false);b.setText(text);b.setTextSize(14);
        b.setTextColor(primary?Color.WHITE:DARK);b.setBackground(bg(primary?BLUE:Color.rgb(225,233,244),10));
        b.setPadding(dp(12),dp(10),dp(12),dp(10));return b;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(DARK);getWindow().setNavigationBarColor(DARK);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        LinearLayout root=col();root.setPadding(dp(20),dp(18),dp(20),dp(30));scroll.addView(root);
        setContentView(scroll);

        TextView title=text("LADA / LIVE",23,DARK,true);root.addView(title);
        root.addView(text("Показания автомобиля · только чтение",13,GREY,false));
        LinearLayout hero=card(root,true);
        speedText=text("—",60,Color.WHITE,true);hero.addView(speedText);
        hero.addView(text("км/ч",17,Color.rgb(170,194,226),false));
        speedMeta=text("Нет данных",12,Color.rgb(181,200,226),false);
        hero.addView(speedMeta);
        stateText=text("Мониторинг не запущен",13,DARK,false);
        root.addView(stateText,margin(9,7));

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);root.addView(controls,margin(5,8));
        Button start=button("▶ Начать",true),stop=button("■ Стоп",false);
        controls.addView(start,new LinearLayout.LayoutParams(0,dp(52),1f));
        LinearLayout.LayoutParams stopParams=new LinearLayout.LayoutParams(0,dp(52),.7f);
        stopParams.leftMargin=dp(8);controls.addView(stop,stopParams);
        start.setOnClickListener(v->startVehicle());
        stop.setOnClickListener(v->{stopVehicle();refresh();});

        LinearLayout first=card(root,false);
        field(first,"FUEL_L","Топливо","л");
        field(first,"OUTSIDE_C","На улице","°C");
        field(first,"FUEL_LOW","Мало топлива","");
        LinearLayout second=card(root,false);
        field(second,"IGNITION","Зажигание","");
        field(second,"ENGINE","Двигатель","");
        field(second,"GEAR","Передача","");
        field(second,"HANDBRAKE","Ручник","");
        field(second,"REVERSE","Задний ход","");
        LinearLayout steering=card(root,false);
        steering.addView(text("РУЛЬ · СЫРОЕ ЗНАЧЕНИЕ",12,BLUE,true));
        field(steering,"STEER_RAW","Поворот","ед.");
        steering.addView(text("±5300 — сырое значение, не градусы. Руль обновляется только при открытом CAN Information. После возврата показывается ПОСЛЕДНЕЕ значение, не LIVE.",12,GREY,false));
        LinearLayout doors=card(root,false);
        doors.addView(text("КУЗОВ · ДВЕРИ И КАПОТ",12,BLUE,true));
        doors.addView(text("Статусы Humax CAN Information фиксируются только когда открыт инженерный экран. Ниже может быть последнее зафиксированное состояние, а не текущее.",12,GREY,false));
        field(doors,"DRIVER_DOOR","Водительская дверь","");
        field(doors,"PASSENGER_DOOR","Пассажирская дверь","");
        field(doors,"REAR_LEFT_DOOR","Задняя левая дверь","");
        field(doors,"REAR_RIGHT_DOOR","Задняя правая дверь","");
        field(doors,"TAIL_GATE","Багажник","");
        field(doors,"HOOD","Капот","");
        field(doors,"ALARM","Охрана","");
        LinearLayout extra=card(root,false);
        extra.addView(text("ДОПОЛНИТЕЛЬНЫЕ ПАРАМЕТРЫ",12,BLUE,true));
        field(extra,"RPM","Обороты","об/мин");
        field(extra,"COOLANT_C","Температура двигателя (ОЖ)","°C");
        field(extra,"ODOMETER","Общий пробег","км");
        extra.addView(text("Обороты и ОЖ: сначала проверяем штатный CarService без адаптера. На R2 доступ CAR_ENGINE_DETAILED обычно защищён; отсутствие данных отображается прочерком.",12,GREY,false));
        Button inspect=button("Проверить RPM/ОЖ через штатную систему",true);
        extra.addView(inspect,margin(10,3));
        inspect.setOnClickListener(v->auditEngine());
        auditStatus=text("Диагностика двигателя ещё не запускалась",12,GREY,false);
        extra.addView(auditStatus,margin(3,6));
        Button exportAudit=button("Экспорт проверки двигателя · JSON",false);
        extra.addView(exportAudit,margin(4,10));
        exportAudit.setOnClickListener(v->{
            JSONObject j=latestEngineAudit;
            if(j==null){toast("Сначала запусти проверку RPM/ОЖ");return;}
            export("lada-engine-sources.json","application/json",j.toString());
        });
        extra.addView(text("РЕЗЕРВ · BLUETOOTH ELM327 (НЕОБЯЗАТЕЛЬНО)",12,BLUE,true));
        extra.addView(text("Если штатная R2 блокирует RPM и ОЖ, можно использовать уже имеющийся ELM327: стандартные PID 010C и 0105. Для остальных данных адаптер не нужен. Не подключаемся автоматически.",12,GREY,false));
        Button elmStart=button("Найти и подключить ELM327",false);
        extra.addView(elmStart,margin(7,3));
        elmStart.setOnClickListener(v->chooseElm());
        Button elmStop=button("Отключить ELM327",false);
        extra.addView(elmStop,margin(4,3));
        elmStop.setOnClickListener(v->{
            startService(new Intent(this,EngineElmService.class).setAction(EngineElmService.ACTION_STOP));
            toast("Соединение ELM327 остановлено");
        });
        elmStatus=text("ELM327 не подключён",12,GREY,false);
        extra.addView(elmStatus,margin(4,6));

        LinearLayout settings=card(root,false);
        settings.addView(text("НАСТРОЙКИ МОНИТОРИНГА",12,BLUE,true));
        Button request=button("Запросить автомобильные разрешения",false);settings.addView(request);
        request.setOnClickListener(v->requestAvailablePermissions());
        CheckBox auto=new CheckBox(this);
        auto.setText("Запускать данные автомобиля вместе с GPS");
        auto.setTextSize(13);auto.setTextColor(DARK);
        auto.setChecked(getSharedPreferences("vehicle_live_settings",MODE_PRIVATE).getBoolean("auto_with_gps",false));
        auto.setOnCheckedChangeListener((v,checked)->
            getSharedPreferences("vehicle_live_settings",MODE_PRIVATE).edit().putBoolean("auto_with_gps",checked).apply());
        settings.addView(auto);
        Button access=button("Настройки чтения инженерного экрана",false);settings.addView(access);
        access.setOnClickListener(v->{
            try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
            catch(Exception ex){toast("Настройки специальных возможностей недоступны");}
        });
        settings.addView(text("Не нужно открывать инженерное меню для основных показателей. Не используйте разделы Updates / SW Upgrade — они не требуются приложению.",12,GREY,false));

        LinearLayout exports=card(root,false);
        exports.addView(text("ЛОКАЛЬНЫЙ ЖУРНАЛ",12,BLUE,true));
        Button csv=button("Сохранить историю показаний CSV",false);
        exports.addView(csv);csv.setOnClickListener(v->export("lada-trip-vehicle.csv","text/csv",VehicleLog.exportCsv(this)));
        Button json=button("Сохранить отчёт JSON",false);
        exports.addView(json);json.setOnClickListener(v->export("lada-trip-vehicle.json","application/json",VehicleLog.exportJson(this).toString()));
        exports.addView(text("Значения записываются при изменении и периодически. Экспорт не включает GPS-координаты и не отправляется в сеть.",12,GREY,false));
        String prior=getSharedPreferences("engine_audit",MODE_PRIVATE).getString("last_report",null);
        if(prior!=null)try{latestEngineAudit=new JSONObject(prior);}catch(Exception ignored){}
        refresh();
    }
    private LinearLayout.LayoutParams margin(int top,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(top),0,dp(bottom));return p;
    }
    private LinearLayout card(LinearLayout root,boolean dark){
        LinearLayout c=col();c.setPadding(dp(18),dp(15),dp(18),dp(15));
        c.setBackground(bg(dark?DARK:Color.WHITE,17));root.addView(c,margin(14,0));return c;
    }
    private void field(LinearLayout parent,String key,String label,String units){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);parent.addView(row,margin(12,1));
        TextView name=text(label,14,GREY,false);row.addView(name,new LinearLayout.LayoutParams(0,-2,.98f));
        LinearLayout right=col();right.setGravity(Gravity.END);
        TextView value=text("—",16,DARK,true);value.setGravity(Gravity.END);
        right.addView(value);TextView source=text("",10,GREY,false);
        source.setGravity(Gravity.END);right.addView(source);
        row.addView(right,new LinearLayout.LayoutParams(0,-2,1.35f));
        fields.put(key,value);meta.put(key,source);
        value.setTag(units);
    }
    private static boolean uiHistory(String key){
        return "STEER_RAW".equals(key)||"ENGINE".equals(key)||"REVERSE".equals(key)
            ||"ALARM".equals(key)||"TAIL_GATE".equals(key)||"HOOD".equals(key)
            ||"DRIVER_DOOR".equals(key)||"PASSENGER_DOOR".equals(key)
            ||"REAR_LEFT_DOOR".equals(key)||"REAR_RIGHT_DOOR".equals(key);
    }
    private static String readableState(String key,String raw,String source){
        if("IGNITION".equals(key)){
            if("4".equals(raw))return "Включено";
            return "код "+raw;
        }
        if("HANDBRAKE".equals(key)){
            if("sensor".equals(source)){
                if("1".equals(raw)||"Затянут".equals(raw))return "Затянут";
                if("0".equals(raw)||"Отпущен".equals(raw))return "Отпущен";
            }
            if(raw.startsWith("Not Applied"))return "Отпущен";
            if(raw.startsWith("Applied"))return "Затянут";
        }
        if("GEAR".equals(key)&&"sensor".equals(source))return "код "+raw;
        if("REVERSE".equals(key)){
            if(raw.startsWith("Not Engaged"))return "Выключен";
            if(raw.startsWith("Engaged"))return "Включён";
        }
        if("ENGINE".equals(key)){
            if(raw.startsWith("Engine Running"))return "Работает";
            if(raw.startsWith("Engine Off")||raw.startsWith("Engine Not Running")
                ||raw.startsWith("Engine Stopped"))return "Выключен";
        }
        if(key.endsWith("_DOOR")||"TAIL_GATE".equals(key)||"HOOD".equals(key)){
            if(raw.startsWith("Close")||raw.startsWith("Locked"))
                return ("TAIL_GATE".equals(key)||"HOOD".equals(key))?"Закрыт":"Закрыта";
            if(raw.startsWith("Open")||raw.startsWith("Unlocked"))
                return ("TAIL_GATE".equals(key)||"HOOD".equals(key))?"Открыт":"Открыта";
            if(raw.startsWith("Unknown"))return "Неизвестно";
        }
        if("STEER_RAW".equals(key)){
            try{return String.format(Locale.US,"%+.0f",Double.parseDouble(raw.replace(',','.')));}
            catch(Exception ignored){}
        }
        return raw;
    }
    private void refresh(){
        if(stateText==null)return;
        long now=System.currentTimeMillis();
        boolean running=VehicleLog.running(this);
        stateText.setText((running?"● Чтение включено · ":"○ Чтение выключено · ")+VehicleLog.state(this));
        if(elmStatus!=null)elmStatus.setText("Bluetooth ELM327: "+EngineElmService.state(this));
        long speedTime=VehicleLog.at(this,"SPEED");
        boolean speedFresh=speedTime>0 && now-speedTime<12000;
        speedText.setText(speedFresh?VehicleLog.value(this,"SPEED"):"—");
        speedMeta.setText(describeSource("SPEED",now));
        for(String signal:fields.keySet()){
            long lastAt=VehicleLog.at(this,signal);
            String src=VehicleLog.source(this,signal);
            String raw=VehicleLog.value(this,signal);
            boolean available=lastAt>0 && !"—".equals(raw) &&
                !("ui".equals(src) && "CAN RX".equalsIgnoreCase(raw));
            boolean fresh=available && now-lastAt<12000;
            // OEM Accessibility is screen-only. Preserve its last observed value
            // visibly labelled "not LIVE"; never misrepresent it as current.
            boolean lastUi=available && "ui".equals(src) && uiHistory(signal);
            boolean show=fresh||lastUi;
            String value=show?readableState(signal,raw,src):"—";
            String units=(String)fields.get(signal).getTag();
            fields.get(signal).setText(value+(show && units!=null&&!units.isEmpty()?" "+units:""));
            fields.get(signal).setTextColor(lastUi&&!fresh?GREY:DARK);
            meta.get(signal).setText(describeSource(signal,now));
        }
    }
    private String describeSource(String signal,long now){
        long timestamp=VehicleLog.at(this,signal);
        String src=VehicleLog.source(this,signal);
        if(timestamp<=0){
            return uiHistory(signal)?"Источник: инженерное меню · ещё не считано":
                "нет доступных данных";
        }
        String by="property".equals(src)?"CarProperty": "sensor".equals(src)?"CarSensor":
            "ui".equals(src)?"Engineering UI":"elm".equals(src)?"Bluetooth ELM":src;
        long age=Math.max(0,(now-timestamp)/1000);
        if("ui".equals(src)){
            return age<=12?by+" · видимый экран · "+age+" с назад":
                "ПОСЛЕДНЕЕ (не LIVE) · "+age+" с назад";
        }
        return by+" · "+age+" с назад"+(age>12?" · устарело":"");
    }
    private void auditEngine(){
        if(auditStatus!=null)auditStatus.setText("Проверяем разрешения, CarPropertyManager и CarSensorManager...");
        new Thread(()->{
            JSONObject report=EngineAccessAudit.inspect(getApplicationContext());
            latestEngineAudit=report;
            getSharedPreferences("engine_audit",MODE_PRIVATE).edit().putString("last_report",report.toString()).apply();
            runOnUiThread(()->{
                if(auditStatus==null)return;
                String details;
                try{
                    JSONObject permissions=report.optJSONObject("permissions");
                    JSONObject car=report.optJSONObject("android_automotive");
                    JSONObject props=car==null?null:car.optJSONObject("properties");
                    boolean granted=permissions!=null&&permissions.optJSONObject("CAR_ENGINE_DETAILED")!=null&&
                        permissions.optJSONObject("CAR_ENGINE_DETAILED").optBoolean("granted");
                    details="CAR_ENGINE_DETAILED: "+(granted?"разрешено":"не выдано")+
                        "\nCarService: "+(car==null?"не проверено":car.optString("connection","ошибка"));
                    if(props!=null){
                        JSONObject rpm=props.optJSONObject("RPM"),ct=props.optJSONObject("COOLANT_C");
                        details+="\nRPM: "+(rpm==null?"не проверено":rpm.optString("read","см. отчёт"))+
                            "\nОЖ: "+(ct==null?"не проверено":ct.optString("read","см. отчёт"));
                    }
                    details+="\nПодробности сохраняются в JSON.";
                }catch(Exception ex){details="Проверка завершена. Сохрани JSON.";}
                auditStatus.setText(details);
            });
        },"LadaTripEngineAudit").start();
    }
    private void chooseElm(){
        startActivity(new Intent(this,ElmPairingActivity.class));
    }
    private void startVehicle(){
        requestAvailablePermissions();
        try{
            startForegroundService(new Intent(this,VehicleLiveService.class));
            refresh();toast("Запущено чтение автомобильных данных");
        }catch(Exception e){toast("Запуск не удался: "+e.getClass().getSimpleName());}
    }
    private void stopVehicle(){
        Intent stop=new Intent(this,VehicleLiveService.class).setAction(VehicleLiveService.ACTION_STOP);
        startService(stop);
        toast("Чтение остановлено");
    }
    private void requestAvailablePermissions(){
        ArrayList<String> list=new ArrayList<>();
        for(String p:PERMISSIONS){
            try{
                PermissionInfo info=getPackageManager().getPermissionInfo(p,0);
                if((info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE)==PermissionInfo.PROTECTION_DANGEROUS
                    &&checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)list.add(p);
            }catch(PackageManager.NameNotFoundException ignored){}
        }
        if(!list.isEmpty())requestPermissions(list.toArray(new String[0]),CODE_PERMISSION);
        else toast("Все доступные разрешения уже получены");
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants){
        super.onRequestPermissionsResult(code,names,grants);
        if(code==CODE_PERMISSION)refresh();
    }
    private void export(String name,String mime,String content){
        pendingExport=content;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,name);
        try{startActivityForResult(i,CODE_EXPORT);}
        catch(Exception e){toast("Сохранение файла недоступно");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==CODE_EXPORT&&result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingExport!=null){
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
                if(out==null)throw new IllegalStateException("OutputStream is null");
                out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                out.flush();toast("Файл сохранён");
            }catch(Exception ex){toast("Ошибка сохранения: "+ex.getClass().getSimpleName());}
            finally{pendingExport=null;}
        }
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(tick);handler.post(tick);}
    @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}
}
