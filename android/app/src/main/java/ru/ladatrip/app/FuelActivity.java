package ru.ladatrip.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FuelActivity extends Activity {
    private static final String[] SOURCES={"property","sensor","ui"};
    private static final String[] LABELS={"CarService","CarSensor","Инженерный экран"};
    private static final long DAY=86400000L;
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private TextView summary,selection;
    private ProgressBar gauge;
    private Plot plot;
    private int source;
    private long period=DAY,customFrom,customUntil;
    private boolean activeTrip;
    private int loadGeneration;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(16*getResources().getDisplayMetrics().density);
        screen.setPadding(pad,pad,pad,pad);
        summary=new TextView(this);summary.setTextSize(18);screen.addView(summary);
        gauge=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        gauge.setMax(100);screen.addView(gauge);
        Button filter=new Button(this);filter.setText("Источник и период");screen.addView(filter);
        filter.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Источник")
            .setItems(LABELS,(dialog,index)->new AlertDialog.Builder(this).setTitle("Период")
                .setItems(new String[]{"Текущая GPS-поездка","День","Неделя","Месяц","Выбрать даты"},(d,p)->{
                    if(p==4){chooseDates(index);return;}
                    source=index;activeTrip=p==0;customFrom=0;customUntil=0;
                    if(p>0)period=DAY*new int[]{1,7,30}[p-1];
                    load();
                }).show()).show());
        plot=new Plot();screen.addView(plot,new LinearLayout.LayoutParams(-1,0,1));
        selection=new TextView(this);screen.addView(selection);
        Button calibrate=new Button(this);calibrate.setText("Ёмкость бака и точки тарировки");
        screen.addView(calibrate);calibrate.setOnClickListener(v->editCalibration());
        String active=VehicleLog.source(this,"FUEL_L");
        for(int i=0;i<SOURCES.length;i++)if(SOURCES[i].equals(active))source=i;
        setContentView(screen);
        load();
    }

    private void chooseDates(int index){
        LinearLayout range=new LinearLayout(this);range.setOrientation(LinearLayout.VERTICAL);
        EditText from=new EditText(this),until=new EditText(this);
        from.setSingleLine(true);until.setSingleLine(true);
        from.setHint("С (ГГГГ-ММ-ДД)");until.setHint("По (ГГГГ-ММ-ДД включительно)");
        range.addView(from);range.addView(until);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Период истории")
            .setView(range).setNegativeButton("Отмена",null).setPositiveButton("Показать",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                java.time.LocalDate start=java.time.LocalDate.parse(from.getText().toString().trim());
                java.time.LocalDate end=java.time.LocalDate.parse(until.getText().toString().trim());
                if(end.isBefore(start))throw new IllegalArgumentException();
                java.time.ZoneId zone=java.time.ZoneId.systemDefault();
                customFrom=start.atStartOfDay(zone).toInstant().toEpochMilli();
                customUntil=end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
                source=index;activeTrip=false;
                dialog.dismiss();load();
            }catch(java.time.DateTimeException|IllegalArgumentException invalid){
                until.setError("Проверьте формат и порядок дат");
            }
        }));
        dialog.show();
    }
    private SharedPreferences settings(){return getSharedPreferences("fuel_calibration",MODE_PRIVATE);}
    private void load(){
        int generation=++loadGeneration;
        String key=SOURCES[source],label=LABELS[source];
        double capacity=settings().getFloat("capacity",0);
        FuelCalibration calibration=FuelCalibration.parse(settings().getString(key,""),capacity>0?capacity:300);
        long from=customUntil!=0?customFrom:System.currentTimeMillis()-period;
        long until=customUntil!=0?customUntil:Long.MAX_VALUE;
        boolean onlyCurrent=activeTrip;
        selection.setText("Коснитесь графика для просмотра сырого и сглаженного показаний.");
        reader.execute(()->{
            try(VehicleLog log=new VehicleLog(this)){
                long start=from;
                if(onlyCurrent){
                    try(TripStore trips=new TripStore(this)){start=trips.startTime(trips.openTrip());}
                    if(start==0){
                        runOnUiThread(()->{
                            if(isDestroyed()||generation!=loadGeneration)return;
                            plot.samples=new ArrayList<>();plot.invalidate();gauge.setVisibility(View.GONE);
                            summary.setText("Нет активной записи GPS-поездки.");
                        });
                        return;
                    }
                }
                List<Sample> samples=new ArrayList<>();
                ArrayDeque<Double> window=new ArrayDeque<>();
                long previous=0;
                int rises=0,drops=0;
                double last=Double.NaN;
                for(VehicleLog.Reading reading:log.fuelHistory(start,until)){
                    if(!key.equals(reading.source))continue;
                    double raw;
                    try{raw=Double.parseDouble(reading.value.replace(',','.'));}
                    catch(NumberFormatException invalid){continue;}
                    double litres=calibration.litres(raw);
                    if(!Double.isFinite(litres))continue;
                    boolean gap=previous==0||reading.at-previous>10*60*1000L;
                    if(gap){window.clear();last=Double.NaN;}
                    previous=reading.at;
                    if(window.size()==5)window.removeFirst();
                    window.addLast(litres);
                    Double[] sorted=window.toArray(new Double[0]);
                    Arrays.sort(sorted);
                    double level=sorted[sorted.length/2];
                    if(sorted.length==5&&Double.isFinite(last)){
                        if(level-last>=4)rises++;
                        if(last-level>=4)drops++;
                    }
                    last=level;
                    samples.add(new Sample(reading.at,raw,litres,level,gap,sorted.length==5));
                }
                int up=rises,down=drops;
                String estimate=estimate(samples);
                runOnUiThread(()->{
                    if(isDestroyed()||generation!=loadGeneration)return;
                    plot.samples=samples;plot.invalidate();
                    gauge.setVisibility(View.GONE);
                    if(samples.isEmpty()){
                        summary.setText(label+" · за выбранный период нет показаний в диапазоне тарировки");
                        return;
                    }
                    Sample newest=samples.get(samples.size()-1);
                    boolean current=VehicleDisplayFormat.fresh(newest.time,System.currentTimeMillis(),12000);
                    boolean low=current&&VehicleDisplayFormat.fresh(VehicleLog.at(this,"FUEL_LOW"),
                        System.currentTimeMillis(),12000)&&"Мало".equals(VehicleDisplayFormat.state(
                        "FUEL_LOW",VehicleLog.value(this,"FUEL_LOW"),VehicleLog.source(this,"FUEL_LOW")));
                    summary.setText(String.format(Locale.getDefault(),
                        "%s · %s%.1f л%s\n%s · возможные скачки: ↑%d ↓%d",
                        label,current?"":"Последнее: ",newest.litres,low?" · резерв":"",
                        date(newest.time),up,down));
                    if(samples.size()>1)summary.append(String.format(Locale.getDefault(),
                        "\nИзменение уровня за период: %+.1f л",newest.level-samples.get(0).level));
                    if(estimate!=null)summary.append("\n"+estimate);
                    if(capacity>0){
                        int percent=(int)Math.min(100,Math.round(newest.litres/capacity*100));
                        gauge.setVisibility(View.VISIBLE);gauge.setProgress(percent);
                        summary.append(String.format(Locale.getDefault()," · %d%%",percent));
                    }
                });
            }catch(RuntimeException error){
                runOnUiThread(()->{if(!isDestroyed()&&generation==loadGeneration)
                    summary.setText("Не удалось прочитать историю топлива.");});
            }
        });
    }
    private String estimate(List<Sample> samples){
        if(samples.size()<6)return null;
        TripStore.TripSpan trip;
        try(TripStore trips=new TripStore(this)){
            trip=trips.latestLongTrip(samples.get(0).time,samples.get(samples.size()-1).time);
        }
        if(trip==null)return null;
        Sample start=null,end=null,previous=null;
        for(Sample sample:samples){
            if(sample.time<=trip.start){
                start=sample;
            }else if(sample.time<=trip.end+60000){
                if(sample.gap || (previous!=null&&Math.abs(sample.level-previous.level)>=4))return null;
                if(sample.time>=trip.end){end=sample;break;}
            }
            previous=sample;
        }
        if(start==null||end==null||!start.stable||!end.stable||trip.start-start.time>60000)return null;
        double used=start.level-end.level,km=trip.meters/1000;
        double consumption=100*used/km;
        if(used<2||consumption>40)return null;
        return String.format(Locale.getDefault(),
            "Оценка последней подходящей GPS-поездки: %.1f км · %.1f л · %.1f л/100 км",
            km,used,consumption);
    }
    private void editCalibration(){
        String key=SOURCES[source];
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        EditText capacity=new EditText(this);capacity.setHint("Ёмкость бака, л");
        capacity.setSingleLine(true);
        capacity.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        float stored=settings().getFloat("capacity",0);
        if(stored>0)capacity.setText(Float.toString(stored));
        form.addView(capacity);
        EditText points=new EditText(this);points.setHint("Строка: показание и фактические литры");
        points.setMinLines(3);points.setText(settings().getString(key,""));form.addView(points);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Тарировка · "+LABELS[source])
            .setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null)
            .setNeutralButton("Сбросить точки",(d,w)->{settings().edit().remove(key).apply();load();})
            .create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                float size=Float.parseFloat(capacity.getText().toString().replace(',','.'));
                String lookup=points.getText().toString().trim();
                FuelCalibration.parse(lookup,size);
                for(String other:SOURCES)if(!other.equals(key))
                    FuelCalibration.parse(settings().getString(other,""),size);
                settings().edit().putFloat("capacity",size).putString(key,lookup).apply();
                dialog.dismiss();load();
            }catch(IllegalArgumentException invalid){points.setError("Проверьте ёмкость и возрастающие пары точек всех источников");}
        }));
        dialog.show();
    }
    @Override protected void onDestroy(){reader.shutdownNow();super.onDestroy();}
    private String date(long time){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(time));}

    private static final class Sample {
        final long time;
        final double raw,litres,level;
        final boolean gap,stable;
        Sample(long time,double raw,double litres,double level,boolean gap,boolean stable){
            this.time=time;this.raw=raw;this.litres=litres;this.level=level;
            this.gap=gap;this.stable=stable;
        }
    }
    private final class Plot extends View {
        List<Sample> samples=new ArrayList<>();
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Plot(){super(FuelActivity.this);setContentDescription("История уровня топлива");}
        private float x(Sample point){
            long first=samples.get(0).time,last=samples.get(samples.size()-1).time;
            return 12+(getWidth()-24)*((float)(point.time-first)/Math.max(1,last-first));
        }
        @Override protected void onDraw(Canvas canvas){
            canvas.drawColor(0xffeef3f8);
            if(samples.isEmpty())return;
            double max=1;
            for(Sample point:samples)max=Math.max(max,point.level);
            Sample previous=null;
            float height=Math.max(1,getHeight()-24);
            paint.setColor(0xff245ab3);paint.setStrokeWidth(3f);
            for(Sample point:samples){
                float y=12+height*(float)(1-point.level/max);
                if(previous==null||point.gap)canvas.drawCircle(x(point),y,3,paint);
                else canvas.drawLine(x(previous),12+height*(float)(1-previous.level/max),x(point),y,paint);
                previous=point;
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getAction()!=MotionEvent.ACTION_UP||samples.isEmpty())return true;
            Sample best=samples.get(0);
            for(Sample point:samples)if(Math.abs(x(point)-event.getX())<Math.abs(x(best)-event.getX()))best=point;
            selection.setText(String.format(Locale.getDefault(),
                "%s · сырое %.2f · %.1f л · сглаженное %.1f л",date(best.time),best.raw,best.litres,best.level));
            return performClick();
        }
    }
}
