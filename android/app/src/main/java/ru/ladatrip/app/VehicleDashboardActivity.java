package ru.ladatrip.app;

import android.app.Activity;
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
    private TextView speedText,speedMeta,stateText;
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
        steering.addView(text("Плюс — влево, минус — вправо. Примерный диапазон ±5300. Это не градусы. Доступно через инженерное меню только пока оно открыто.",12,GREY,false));
        LinearLayout doors=card(root,false);
        doors.addView(text("КУЗОВ · СТАТУСЫ ИНЖЕНЕРНОГО МЕНЮ",12,BLUE,true));
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
        field(extra,"ODOMETER","Общий пробег","км");
        extra.addView(text("RPM, одометр и другие защищённые сигналы показываются только при фактическом получении. Отсутствие разрешения не маскируется нулями.",12,GREY,false));

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
    private void refresh(){
        if(stateText==null)return;
        long now=System.currentTimeMillis();
        boolean running=VehicleLog.running(this);
        stateText.setText((running?"● Чтение включено · ":"○ Чтение выключено · ")+VehicleLog.state(this));
        String speed=VehicleLog.value(this,"SPEED");
        long age=VehicleLog.at(this,"SPEED");
        boolean fresh=age>0 && now-age<12000;
        speedText.setText(fresh?speed:"—");
        speedMeta.setText(describeSource("SPEED",now));
        for(String signal:fields.keySet()){
            long seen=VehicleLog.at(this,signal);
            boolean valid=seen>0 && now-seen<12000;
            String value=valid?VehicleLog.value(this,signal):"—";
            if(valid && "IGNITION".equals(signal)){
                if("4".equals(value))value="Включено (4)";
                else value="код "+value;
            }
            if(valid && ("GEAR".equals(signal)||"HANDBRAKE".equals(signal))
                && "sensor".equals(VehicleLog.source(this,signal)))value="код "+value;
            if(valid && "STEER_RAW".equals(signal)){
                try{
                    double raw=Double.parseDouble(value.replace(',','.'));
                    value=String.format(Locale.US,"%+.0f",raw);
                }catch(Exception ignored){}
            }
            String units=(String)fields.get(signal).getTag();
            fields.get(signal).setText(value+(valid && units!=null&&!units.isEmpty()?" "+units:""));
            meta.get(signal).setText(describeSource(signal,now));
        }
    }
    private String describeSource(String signal,long now){
        long timestamp=VehicleLog.at(this,signal);
        if(timestamp<=0)return "нет доступных данных";
        String source=VehicleLog.source(this,signal);
        String by="property".equals(source)?"CarProperty":"sensor".equals(source)?"CarSensor":
            "ui".equals(source)?"Engineering UI":source;
        long age=Math.max(0,(now-timestamp)/1000);
        return by+" · "+age+" с назад"+(age>12?" · устарело":"");
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
