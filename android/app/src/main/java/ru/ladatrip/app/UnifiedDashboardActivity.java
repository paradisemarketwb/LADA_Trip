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
    private android.widget.CheckBox boot;
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
        gps=txt("GPS: нет данных",12,WHITE,false);hero.addView(gps,spaced(4,0));
        Button gpsSettings=button("GPS и карты",false);
        hero.addView(gpsSettings,spaced(8,0));
        gpsSettings.setOnClickListener(v->startActivity(new Intent(this,GpsSettingsActivity.class)));
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
        LinearLayout metrics=row();
        quick.addView(metrics,spaced(12,7));
        metric(metrics,"FUEL_L","ТОПЛИВО","л");
        metric(metrics,"OUTSIDE_C","НА УЛИЦЕ","°C");
        Button fuel=button("История, тарировка и расход",false);
        quick.addView(fuel,spaced(8,0));
        fuel.setOnClickListener(v->startActivity(new Intent(this,FuelActivity.class)));

        LinearLayout trips=card(root);
        heading(trips,"ИСТОРИЯ ПОЕЗДОК","По CAN — расстояние без GPS; по GPS — маршрут и остановки");
        Button mileage=button("Источник пробега",false);
        trips.addView(mileage,spaced(8,4));
        mileage.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Пробег на главном экране")
            .setSingleChoiceItems(new String[]{"CAN","GPS"},useGpsMileage()?1:0,(dialog,index)->{
                Settings.mileagePreferences(this).edit().putBoolean("gps_mileage",index==1).apply();
                dialog.dismiss();refresh();
            }).setNegativeButton("Отмена",null).show());
        canTrips=txt("Поездок CAN ещё нет",13,FG,false);trips.addView(canTrips,spaced(7,8));
        gpsTrips=txt("GPS-поездок ещё нет",13,FG,false);trips.addView(gpsTrips,spaced(7,0));

        Button map=button("Карта поездок",true);
        trips.addView(map,spaced(8,0));
        map.setOnClickListener(v->startActivity(new Intent(this,YandexTripMapActivity.class)));

        LinearLayout tools=card(root);
        heading(tools,"ОДИН ЭКСПОРТ · ПОЛНАЯ ДИАГНОСТИКА",
            "Без секретных ключей и географических координат");
        Button calibrate=button("Тарировка скорости",false);
        tools.addView(calibrate,spaced(11,0));
        calibrate.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Тарировка скорости")
            .setItems(new String[]{"По скорости круиз-контроля","Ввести прошлый замер","Ручные параметры и контрольные точки"},(dialog,index)->{
                if(index==0)captureCruiseSpeed();
                else new AlertDialog.Builder(this).setTitle("Источник скорости")
                    .setItems(SPEED_SOURCE_LABELS,(d,source)->{
                        if(index==1)enterPastCruiseSpeed(source);else editSpeedCalibration(source);
                    }).show();
            }).show());
        boot=new android.widget.CheckBox(this);
        boot.setText("Запускать запись CAN и GPS после загрузки");
        boot.setChecked(Settings.autoStart(this));
        boot.setOnCheckedChangeListener((view,checked)->Settings.autoStart(this,checked));
        tools.addView(boot,spaced(5,0));
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
            try{speedCalibrations.put(source,readCalibration(
                preferences.getString(source+"/points",""),preferences.getString(source+"/factor",""),
                preferences.getString(source+"/offset","")));}
            catch(IllegalArgumentException ignored){}
        }
    }
    private VehicleDisplayFormat.SpeedCalibration readCalibration(String points,String factor,String offset){
        factor=factor.trim().replace(',','.');
        offset=offset.trim().replace(',','.');
        if(factor.isEmpty()&&offset.isEmpty())return VehicleDisplayFormat.SpeedCalibration.parse(points);
        return VehicleDisplayFormat.SpeedCalibration.manual(factor.isEmpty()?1:Double.parseDouble(factor),
            offset.isEmpty()?0:Double.parseDouble(offset));
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
        EditText factor=new EditText(this),offset=new EditText(this);
        factor.setSingleLine(true);offset.setSingleLine(true);
        factor.setHint("Коэффициент, например 1,05");
        offset.setHint("Поправка в км/ч, например −2");
        factor.setText(preferences.getString(source+"/factor",""));
        offset.setText(preferences.getString(source+"/offset",""));
        content.addView(factor);content.addView(offset);
        content.addView(txt("Для ручной поправки заполните коэффициент или смещение. Для расчёта по точкам оставьте оба поля пустыми.",13,FG,false));
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
                    if(option==1)change.remove(source+"/points").remove(source+"/factor").remove(source+"/offset");
                    change.apply();
                    loadSpeedCalibrations();refresh();
                }).show())
            .setPositiveButton("Применить",null).create();
        editor.setOnShowListener(dialog->editor.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v->{
                String input=points.getText().toString().trim();
                VehicleDisplayFormat.SpeedCalibration profile;
                try{profile=readCalibration(input,factor.getText().toString(),offset.getText().toString());}
                catch(IllegalArgumentException invalid){
                    points.setError("Проверьте пары скоростей или ручную поправку (коэффициент 0,5–2, смещение ±30 км/ч)");
                    return;
                }
                preferences.edit().putString(source+"/points",input).putString(source+"/factor",factor.getText().toString()).putString(source+"/offset",offset.getText().toString())
                    .putBoolean(source+"/enabled",true).apply();
                loadSpeedCalibrations();refresh();editor.dismiss();
                toast(String.format(Locale.getDefault(),"Коэффициент %.4f · смещение %+.1f км/ч",
                    profile.factor,profile.offset));
            }));
        editor.show();
    }
    private boolean useGpsMileage(){return Settings.gpsMileage(this);}

    private void captureCruiseSpeed(){
        VehicleLog.Reading reading=VehicleLog.speed(this);
        long now=System.currentTimeMillis();
        if(!("property".equals(reading.source)||"sensor".equals(reading.source))||
            !VehicleDisplayFormat.fresh(reading.at,now,6000)){
            toast("Нет свежего показания скорости автомобиля");return;
        }
        double measured;
        try{measured=Double.parseDouble(reading.value);}
        catch(NumberFormatException bad){toast("Не удалось прочитать скорость");return;}
        if(!Double.isFinite(measured)||measured<10||measured>240){
            toast("Нужен замер устойчивой скорости от 10 до 240 км/ч");return;
        }
        VehicleDisplayFormat.SpeedCalibration current=speedCalibrations.get(reading.source);
        double shown=current==null?measured:current.correct(measured);
        LinearLayout content=column();
        content.setPadding(dp(20),dp(8),dp(20),0);
        content.addView(txt(String.format(Locale.getDefault(),
            "Замер сохранён для этого диалога. Система: %.1f км/ч · с текущей поправкой: %.1f км/ч.",
            measured,shown),14,FG,false));
        content.addView(txt("Вводите эталон после остановки или попросите пассажира.",13,MUTED,false));
        EditText reference=new EditText(this);
        reference.setSingleLine(true);
        reference.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        reference.setHint("Скорость круиз-контроля, км/ч");
        content.addView(reference);
        AlertDialog editor=new AlertDialog.Builder(this).setTitle("Тарировка по круиз-контролю")
            .setView(content).setNegativeButton("Отмена",null).setPositiveButton("Применить",null).create();
        editor.setOnShowListener(d->editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                double value=Double.parseDouble(reference.getText().toString().trim().replace(',','.'));
                VehicleDisplayFormat.SpeedCalibration updated=
                    VehicleDisplayFormat.SpeedCalibration.fromCruise(current,measured,value);
                saveSpeedCorrection(reading.source,updated);
                editor.dismiss();
            }catch(IllegalArgumentException invalid){reference.setError("Проверьте эталон и текущую поправку");}
        }));
        editor.show();
    }
    private void enterPastCruiseSpeed(int index){
        String source=SPEED_SOURCES[index];
        VehicleDisplayFormat.SpeedCalibration current=speedCalibrations.get(source);
        LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),0);
        content.addView(txt("Введите показание спидометра приложения и скорость на круиз-контроле. Используйте тот же источник и профиль поправки, что были во время замера.",14,FG,false));
        EditText shown=cruiseInput("Приложение показывало, км/ч");
        EditText reference=cruiseInput("Скорость круиз-контроля, км/ч");
        content.addView(shown);content.addView(reference);
        AlertDialog editor=new AlertDialog.Builder(this).setTitle(SPEED_SOURCE_LABELS[index])
            .setView(content).setNegativeButton("Отмена",null).setPositiveButton("Применить",null).create();
        editor.setOnShowListener(d->editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                double display=Double.parseDouble(shown.getText().toString().trim().replace(',','.'));
                double target=Double.parseDouble(reference.getText().toString().trim().replace(',','.'));
                saveSpeedCorrection(source,VehicleDisplayFormat.SpeedCalibration.fromDisplayed(current,display,target));
                editor.dismiss();
            }catch(IllegalArgumentException invalid){reference.setError("Проверьте оба значения скорости");}
        }));
        editor.show();
    }

    private EditText cruiseInput(String hint){
        EditText input=new EditText(this);input.setSingleLine();
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(hint);return input;
    }

    private void saveSpeedCorrection(String source,VehicleDisplayFormat.SpeedCalibration correction){
        calibrationPreferences().edit()
            .putString(source+"/factor",Double.toString(correction.factor))
            .putString(source+"/offset",Double.toString(correction.offset))
            .putBoolean(source+"/enabled",true).apply();
        loadSpeedCalibrations();refresh();
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
        String original="";
        if(VehicleDisplayFormat.fresh(carSpeed.at,now,12000)){
            speed.setText(carSpeed.value);
            speedOrigin.setText("CAN · "+sourceLabel("SPEED",now));
            VehicleDisplayFormat.SpeedCalibration profile=speedCalibrations.get(carSpeed.source);
            if(profile!=null){
                try{
                    double adjusted=profile.correct(Double.parseDouble(carSpeed.value));
                    original=" · Исходная: "+formatSpeed(Double.parseDouble(carSpeed.value));
                    speed.setText(String.format(Locale.getDefault(),"%.1f",adjusted));
                    speedOrigin.append(" · с поправкой");
                }
                catch(NumberFormatException ignored){}
            }
        }else{speed.setText("—");speedOrigin.setText("CAN · нет свежей скорости");}
        speedComparison.setText("GPS: "+formatSpeed(GpsHealth.freshSpeed(this,now))+original);
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
        if(gps!=null)gps.setText(GpsHealth.indicator(this,now));
        boolean gpsMileage=useGpsMileage();
        if(canTrips!=null){
            canTrips.setVisibility(gpsMileage?View.GONE:View.VISIBLE);
            if(!gpsMileage)canTrips.setText("CAN · "+journeys.summary());
        }
        if(gpsTrips!=null){
            gpsTrips.setVisibility(gpsMileage?View.VISIBLE:View.GONE);
            if(gpsMileage)gpsTrips.setText("GPS · "+tripStore.recentTrips());
        }
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
        getSharedPreferences("vehicle_live_settings",MODE_PRIVATE).edit()
            .putBoolean("auto_with_gps",true).apply();
        try{startForegroundService(new Intent(this,VehicleLiveService.class));}
        catch(Exception e){toast("CAN: "+e.getClass().getSimpleName());}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            try{
                Settings.monitor(this,true);
                startForegroundService(new Intent(this,TripService.class));
            }catch(Exception ex){toast("GPS: "+ex.getClass().getSimpleName());}
        }
        refresh();
    }
    private void stopAll(){
        Settings.monitor(this,false);
        boot.setChecked(false);
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