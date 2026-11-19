package ru.ladatrip.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;

/**
 * One real dashboard for Android 9, designed for Humax 960x1280 portrait.
 * No WebView, navigation into OEM engineering, elevated permissions, or vehicle writes.
 */
public final class UnifiedDashboardActivity extends Activity {
    private static final int DARK=Color.rgb(13,24,41);
    private static final int BLUE=Color.rgb(48,108,220);
    private static final int FG=Color.rgb(28,46,72);
    private static final int MUTED=Color.rgb(111,129,153);
    private static final int BG=Color.rgb(240,245,251);
    private static final int WHITE=Color.WHITE;
    private static final int LOCATION_REQUEST=1211,CAR_REQUEST=1212,SAVE_REQUEST=1213;
    private static final String[] CAR_PERMISSION={
        "android.car.permission.CAR_SPEED","android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_POWERTRAIN","android.car.permission.CAR_POWER",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
    };
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,TextView> values=new LinkedHashMap<>();
    private final Map<String,TextView> sources=new LinkedHashMap<>();
    private final Runnable update=new Runnable(){
        @Override public void run(){refresh();handler.postDelayed(this,1600);}
    };
    private TextView speed, speedOrigin, speedComparison, status, gps, canTrips, gpsTrips, clock, rSteer, rEngine, gear;
    private String pendingExport;
    private final Map<String,VehicleDisplayFormat.SpeedCalibration> speedCalibrations=new LinkedHashMap<>();
    private static final String[] SPEED_SOURCES={"property","sensor","ui"};
    private static final String[] SPEED_SOURCE_LABELS={"CarService (Humax R2)","CarSensor","Инженерный экран"};
    private CanJourneyStore journeys;
    private TripStore tripStore;
    private boolean userStarting;
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);return l;
    }
    private GradientDrawable surface(int color,int radius){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;
    }
    private GradientDrawable heroBg(){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{DARK,Color.rgb(20,47,81),Color.rgb(27,84,135)});
        g.setCornerRadius(dp(23));return g;
    }
    private TextView txt(String text,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;
    }
    private LinearLayout.LayoutParams spaced(int top,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(top),0,dp(bottom));return p;
    }
    private LinearLayout card(LinearLayout root){
        LinearLayout c=column();c.setPadding(dp(17),dp(17),dp(17),dp(17));
        c.setBackground(surface(WHITE,18));root.addView(c,spaced(13,0));return c;
    }
    private Button button(String title,boolean primary){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);
        b.setTextColor(primary?WHITE:FG);
        b.setPadding(dp(14),dp(13),dp(14),dp(13));
        b.setBackground(surface(primary?BLUE:Color.rgb(227,235,245),12));
        return b;
    }
    private void heading(LinearLayout c,String title,String secondary){
        TextView h=txt(title,12,BLUE,true);h.setLetterSpacing(.07f);c.addView(h);
        if(secondary!=null)c.addView(txt(secondary,12,MUTED,false),spaced(4,4));
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        journeys=new CanJourneyStore(this);
        tripStore=new TripStore(this);
        draw();
    }
    private void draw(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);LinearLayout root=column();
        root.setPadding(dp(17),dp(16),dp(17),dp(32));scroll.addView(root);
        setContentView(scroll);

        LinearLayout header=row();root.addView(header,spaced(0,12));
        LinearLayout left=column();header.addView(left,new LinearLayout.LayoutParams(0,-2,1f));
        TextView brand=txt("LADA  /  TRIP",23,DARK,true);
        brand.setLetterSpacing(.08f);left.addView(brand);
        left.addView(txt("АВТОМОБИЛЬ · ПОЕЗДКИ · ОФЛАЙН",11,MUTED,false));
        clock=txt("—",14,MUTED,false);header.addView(clock);

        LinearLayout hero=column();hero.setPadding(dp(20),dp(20),dp(20),dp(20));
        hero.setBackground(heroBg());root.addView(hero);
        hero.addView(txt("СКОРОСТЬ АВТОМОБИЛЯ",12,Color.rgb(159,198,240),true));
        LinearLayout speedLine=row();hero.addView(speedLine,spaced(3,2));
        speed=txt("—",69,WHITE,true);speedLine.addView(speed);
        TextView units=txt("  км/ч",18,Color.rgb(187,210,238),false);
        speedLine.addView(units);
        speedOrigin=txt("Ожидание CarService",12,Color.rgb(179,205,233),false);
        hero.addView(speedOrigin);
        speedComparison=txt("",12,Color.rgb(179,205,233),false);
        hero.addView(speedComparison,spaced(3,0));
        loadSpeedCalibrations();
        status=txt("Мониторинг ещё не запущен",13,WHITE,true);
        hero.addView(status,spaced(14,5));
        hero.addView(txt("«Начать всё» включает автоматическую запись после включения автомобиля. «Стоп» отключает её.",11,Color.rgb(183,208,236),false),spaced(4,3));

        LinearLayout actions=row();hero.addView(actions,spaced(10,0));
        Button go=button("▶ Начать всё",true);
        Button stop=button("■ Стоп",false);
        actions.addView(go,new LinearLayout.LayoutParams(0,dp(51),1.3f));
        LinearLayout.LayoutParams stopP=new LinearLayout.LayoutParams(0,dp(51),.75f);
        stopP.leftMargin=dp(8);actions.addView(stop,stopP);
        go.setOnClickListener(v->startAll());
        stop.setOnClickListener(v->stopAll());

        LinearLayout quick=card(root);
        heading(quick,"ПОКАЗАНИЯ В РЕАЛЬНОМ ВРЕМЕНИ",
            "Только подтверждённые источники, без искусственных значений");
        LinearLayout metrics1=row(),metrics2=row();quick.addView(metrics1,spaced(12,7));quick.addView(metrics2);
        metric(metrics1,"FUEL_L","ТОПЛИВО","л");
        metric(metrics1,"OUTSIDE_C","НА УЛИЦЕ","°C");
        metric(metrics2,"RPM","ОБОРОТЫ","об/мин");
        metric(metrics2,"COOLANT_C","ТЕМПЕРАТУРА ОЖ","°C");

        Button fuel=button("История и тарировка топлива",false);
        quick.addView(fuel,spaced(8,0));
        fuel.setOnClickListener(v->startActivity(new Intent(this,FuelActivity.class)));

        LinearLayout car=card(root);
        heading(car,"СОСТОЯНИЕ АВТОМОБИЛЯ",null);
        field(car,"IGNITION","Зажигание","");
        field(car,"ENGINE","Двигатель","");
        field(car,"HANDBRAKE","Ручник","");
        field(car,"GEAR","Передача","");
        field(car,"REVERSE","Задняя передача","");
        field(car,"FUEL_LOW","Резерв топлива","");
        field(car,"STEER_RAW","Поворот руля","ед.");
        car.addView(txt("Сигналы из инженерного меню сохраняются как последний снимок, а не подменяются текущими. RPM и ОЖ без разрешения доступны только через отдельный ELM327.",12,MUTED,false),spaced(8,0));

        LinearLayout doors=card(root);
        heading(doors,"КУЗОВ И ОХРАНА","Статусы обновляются только из разрешённых источников");
        field(doors,"DRIVER_DOOR","Дверь водителя","");
        field(doors,"PASSENGER_DOOR","Передняя пассажирская","");
        field(doors,"REAR_LEFT_DOOR","Задняя левая","");
        field(doors,"REAR_RIGHT_DOOR","Задняя правая","");
        field(doors,"TAIL_GATE","Багажник","");
        field(doors,"HOOD","Капот","");
        field(doors,"ALARM","Охрана","");

        LinearLayout trips=card(root);
        heading(trips,"ИСТОРИЯ ПОЕЗДОК","По CAN — расстояние без GPS; по GPS — маршрут и остановки");
        gps=txt("GPS: нет данных",12,MUTED,false);trips.addView(gps,spaced(10,5));
        canTrips=txt("Поездок CAN ещё нет",13,FG,false);trips.addView(canTrips,spaced(7,8));
        gpsTrips=txt("GPS-поездок ещё нет",13,FG,false);trips.addView(gpsTrips,spaced(7,0));

        Button map=button("Карта поездок",true);
        trips.addView(map,spaced(8,0));
        map.setOnClickListener(v->startActivity(new Intent(this,RouteMapActivity.class)));

        LinearLayout tools=card(root);
        heading(tools,"ОДИН ЭКСПОРТ · ПОЛНАЯ ДИАГНОСТИКА",
            "Без секретных ключей и географических координат");
        Button calibrate=button("Тарировка скорости",false);
        tools.addView(calibrate,spaced(11,0));
        calibrate.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Источник скорости")
            .setItems(SPEED_SOURCE_LABELS,(dialog,index)->editSpeedCalibration(index)).show());
        Button systems=button("Система и источники данных",false);
        tools.addView(systems,spaced(5,0));
        systems.setOnClickListener(v->startActivity(new Intent(this,SourceDiagnosticsActivity.class)));
        Button report=button("Сохранить единый отчёт JSON",true);
        tools.addView(report,spaced(11,5));report.setOnClickListener(v->
            save("lada-trip-full-diagnostics.json","application/json",UnifiedDiagnostics.build(this).toString()));
        Button canCsv=button("Сохранить поездки CAN · CSV",false);
        tools.addView(canCsv,spaced(5,5));canCsv.setOnClickListener(v->
            save("lada-trip-can-trips.csv","text/csv",journeys.exportCsv()));
        Button details=button("GPS, настройки и расширенная диагностика",false);
        tools.addView(details,spaced(5,0));
        details.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
        Button engine=button("ELM327 · RPM и температура ОЖ",false);
        tools.addView(engine,spaced(8,0));
        engine.setOnClickListener(v->startActivity(new Intent(this,VehicleDashboardActivity.class)));
        root.addView(txt("LADA Trip 1.0 · Humax AIVI-N3111RV · Только чтение",12,MUTED,false),spaced(15,0));
        refresh();
    }
    private void metric(LinearLayout root,String key,String name,String suffix){
        LinearLayout col=column();col.setPadding(dp(8),dp(9),dp(8),dp(9));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1f);
        root.addView(col,lp);
        col.addView(txt(name,11,MUTED,true));
        TextView v=txt("—",28,FG,true);col.addView(v,spaced(4,1));
        TextView info=txt("Нет данных",10,MUTED,false);col.addView(info);
        v.setTag(suffix);values.put(key,v);sources.put(key,info);
    }
    private void field(LinearLayout c,String key,String label,String units){
        LinearLayout r=row();c.addView(r,spaced(12,1));
        TextView title=txt(label,14,MUTED,false);
        r.addView(title,new LinearLayout.LayoutParams(0,-2,1.0f));
        LinearLayout answer=column();answer.setGravity(Gravity.END);
        TextView value=txt("—",15,FG,true);value.setGravity(Gravity.END);
        value.setTag(units);answer.addView(value);
        TextView info=txt("Не получено",10,MUTED,false);
        info.setGravity(Gravity.END);answer.addView(info);
        r.addView(answer,new LinearLayout.LayoutParams(0,-2,1.15f));
        values.put(key,value);sources.put(key,info);
    }
    private String formatSpeed(double kmh){
        return Double.isFinite(kmh)?String.format(Locale.getDefault(),"%.1f км/ч",kmh):"—";
    }
    private SharedPreferences calibrationPreferences(){
        return getSharedPreferences("speed_calibration",MODE_PRIVATE);
    }
    private void loadSpeedCalibrations(){
        SharedPreferences preferences=calibrationPreferences();
        speedCalibrations.clear();
        for(String source:SPEED_SOURCES){
            if(!preferences.getBoolean(source+"/enabled",false))continue;
            try{speedCalibrations.put(source,VehicleDisplayFormat.SpeedCalibration.parse(
                preferences.getString(source+"/points","")));}
            catch(IllegalArgumentException ignored){}
        }
    }
    private void editSpeedCalibration(int index){
        String source=SPEED_SOURCES[index];
        SharedPreferences preferences=calibrationPreferences();
        LinearLayout content=column();
        content.setPadding(dp(22),dp(8),dp(22),0);
        content.addView(txt("Одна точка на строку: измеренная и эталонная скорости в км/ч через пробел. Нужны минимум две разные скорости. Вводите данные на стоянке.",14,FG,false));
        EditText points=new EditText(this);
        points.setHint("30 33\n60 64");
        points.setMinLines(3);
        points.setText(preferences.getString(source+"/points",""));
        content.addView(points);
        if(!"ui".equals(source)){
            Button reference=button("Подставить проверенные GPS-точки",false);
            content.addView(reference);
            reference.setOnClickListener(v->{
                String suggested=GpsSpeedReference.points(this,source);
                try{VehicleDisplayFormat.SpeedCalibration.parse(suggested);}
                catch(IllegalArgumentException unavailable){
                    toast("Нужно больше устойчивых GPS-замеров при разных скоростях");
                    return;
                }
                points.setText(suggested);
            });
        }
        AlertDialog editor=new AlertDialog.Builder(this).setTitle(SPEED_SOURCE_LABELS[index])
            .setView(content).setNegativeButton("Отмена",null)
            .setNeutralButton("Параметры", (dialog,which)->new AlertDialog.Builder(this)
                .setTitle("Сохранённая тарировка")
                .setItems(new String[]{"Отключить","Сбросить"},(menu,option)->{
                    SharedPreferences.Editor change=preferences.edit().remove(source+"/enabled");
                    if(option==1)change.remove(source+"/points");
                    change.apply();
                    loadSpeedCalibrations();refresh();
                }).show())
            .setPositiveButton("Применить",null).create();
        editor.setOnShowListener(dialog->editor.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v->{
                String input=points.getText().toString().trim();
                VehicleDisplayFormat.SpeedCalibration profile;
                try{profile=VehicleDisplayFormat.SpeedCalibration.parse(input);}
                catch(IllegalArgumentException invalid){
                    points.setError("Проверьте пары скоростей (0–240 км/ч) и направление зависимости");
                    return;
                }
                preferences.edit().putString(source+"/points",input)
                    .putBoolean(source+"/enabled",true).apply();
                loadSpeedCalibrations();refresh();editor.dismiss();
                toast(String.format(Locale.getDefault(),"Коэффициент %.4f · смещение %+.1f км/ч",
                    profile.factor,profile.offset));
            }));
        editor.show();
    }
    private boolean showLast(String key){return VehicleDisplayFormat.engineerOnly(key);}
    private void refresh(){
        if(speed==null)return;
        long now=System.currentTimeMillis();
        clock.setText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(now)));
        boolean carActive=VehicleLog.running(this),gpsActive=Settings.monitor(this);
        status.setText("CAN: "+(carActive?"включён":"выключен")+
            "     GPS: "+(gpsActive?"включён":"выключен"));
        VehicleLog.Reading carSpeed=VehicleLog.speed(this);
        String corrected="—";
        if(VehicleDisplayFormat.fresh(carSpeed.at,now,12000)){
            speed.setText(carSpeed.value);
            speedOrigin.setText("CAN · "+sourceLabel("SPEED",now));
            VehicleDisplayFormat.SpeedCalibration profile=speedCalibrations.get(carSpeed.source);
            if(profile!=null){
                try{corrected=formatSpeed(profile.correct(Double.parseDouble(carSpeed.value)));}
                catch(NumberFormatException ignored){}
            }
        }else{speed.setText("—");speedOrigin.setText("CAN · нет свежей скорости");}
        speedComparison.setText("GPS: "+formatSpeed(GpsHealth.freshSpeed(this,now))+
            "   ·   Тарировка: "+corrected);
        for(Map.Entry<String,TextView> entry:values.entrySet()){
            String k=entry.getKey(),src=VehicleLog.source(this,k);
            long at=VehicleLog.at(this,k);
            boolean recent=VehicleDisplayFormat.fresh(at,now,12000);
            boolean last="ui".equals(src)&&showLast(k)&&at>0;
            boolean available=(recent||last)&&!"—".equals(VehicleLog.value(this,k));
            String text=available?VehicleDisplayFormat.state(k,VehicleLog.value(this,k),src):"—";
            String units=(String)entry.getValue().getTag();
            if(units!=null&&!units.isEmpty()&&available)text+=" "+units;
            entry.getValue().setText(text);
            entry.getValue().setTextColor(last&&!recent?MUTED:FG);
            TextView info=sources.get(k);
            if(info!=null)info.setText(sourceLabel(k,now));
        }
        if(gps!=null)gps.setText(GpsHealth.summary(this));
        if(canTrips!=null)canTrips.setText("CAN · "+journeys.summary());
        if(gpsTrips!=null)gpsTrips.setText("GPS · "+tripStore.recentTrips());
    }
    private String sourceLabel(String key,long now){
        long time=VehicleLog.at(this,key);
        if(time==0)return VehicleDisplayFormat.engineerOnly(key)?
            "Нет доступного фонового показания":"Нет данных";
        long seconds=Math.max(0,(now-time)/1000);
        String src=VehicleLog.source(this,key);
        if("ui".equals(src))return seconds>12?
            "ПОСЛЕДНЕЕ, не LIVE · "+seconds+" с":
            "Инженерный экран · "+seconds+" с";
        if(seconds>12)return "Устарело · "+seconds+" с";
        return ("property".equals(src)?"CarService":
            "sensor".equals(src)?"CarSensor":
            "elm".equals(src)?"ELM327":src)+" · "+seconds+" с";
    }
    private void startAll(){
        userStarting=true;
        ArrayList<String> needs=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            needs.add(Manifest.permission.ACCESS_FINE_LOCATION);
        for(String p:CAR_PERMISSION){
            try{
                PermissionInfo info=getPackageManager().getPermissionInfo(p,0);
                if((info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE)==PermissionInfo.PROTECTION_DANGEROUS
                    &&checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)needs.add(p);
            }catch(PackageManager.NameNotFoundException ignored){}
        }
        if(!needs.isEmpty()){requestPermissions(needs.toArray(new String[0]),CAR_REQUEST);return;}
        enableAll();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==CAR_REQUEST&&userStarting){
            userStarting=false;
            enableAll();
        }
    }
    private void enableAll(){
        userStarting=false;
        // One-time opt-in starts both monitors on future Android boots.
        Settings.autoStart(this,true);
        getSharedPreferences("vehicle_live_settings",MODE_PRIVATE).edit()
            .putBoolean("auto_with_gps",true).apply();
        try{startForegroundService(new Intent(this,VehicleLiveService.class));}
        catch(Exception e){toast("CAN: "+e.getClass().getSimpleName());}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
            if(lm!=null&&lm.isProviderEnabled(LocationManager.GPS_PROVIDER)){
                try{Settings.monitor(this,true);
                    startForegroundService(new Intent(this,TripService.class));}
                catch(Exception ex){toast("GPS: "+ex.getClass().getSimpleName());}
            }else toast("GPS выключен. Монитор CAN продолжает работать.");
        }
        refresh();
    }
    private void stopAll(){
        Settings.monitor(this,false);
        Settings.autoStart(this,false);
        getSharedPreferences("vehicle_live_settings",MODE_PRIVATE).edit()
            .putBoolean("auto_with_gps",false).apply();
        Intent gps=new Intent(this,TripService.class).setAction(TripService.ACTION_STOP);
        Intent can=new Intent(this,VehicleLiveService.class).setAction(VehicleLiveService.ACTION_STOP);
        try{startService(gps);startService(can);}
        catch(Exception e){toast("Остановка: "+e.getClass().getSimpleName());}
        refresh();
    }
    private void save(String name,String mime,String content){
        pendingExport=content;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,name);
        try{startActivityForResult(i,SAVE_REQUEST);}
        catch(Exception e){toast("Не удалось открыть выбор файла");}
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code==SAVE_REQUEST&&result==RESULT_OK&&data!=null&&
            data.getData()!=null&&pendingExport!=null){
            try(OutputStream o=getContentResolver().openOutputStream(data.getData())){
                if(o==null)throw new IllegalStateException("No output");
                o.write(pendingExport.getBytes(StandardCharsets.UTF_8));o.flush();
                toast("Отчёт сохранён");
            }catch(Exception e){toast("Ошибка сохранения: "+e.getClass().getSimpleName());}
            finally{pendingExport=null;}
        }
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){
        super.onResume();handler.removeCallbacks(update);handler.post(update);
    }
    @Override protected void onPause(){handler.removeCallbacks(update);super.onPause();}
    @Override protected void onDestroy(){
        handler.removeCallbacks(update);
        if(journeys!=null)journeys.close();
        if(tripStore!=null)tripStore.close();
        super.onDestroy();
    }
}