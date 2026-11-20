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
                .setItems(new String[]{"Текущая поездка","День","Неделя","Месяц","Выбрать даты"},(d,p)->{
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
        boolean gpsMileage=Settings.gpsMileage(this);
        selection.setText("Коснитесь графика для просмотра сырого и сглаженного показаний.");
        reader.execute(()->{
            try(VehicleLog log=new VehicleLog(this)){
                long start=from;
                if(onlyCurrent){
                    if(gpsMileage){
                        try(TripStore trips=new TripStore(this)){start=trips.startTime(trips.openTrip());}
                    }else{
                        try(CanJourneyStore trips=new CanJourneyStore(this)){start=trips.activeStart();}
                    }
                    if(start==0){
                        runOnUiThread(()->{
                            if(isDestroyed()||generation!=loadGeneration)return;
                            plot.samples=new ArrayList<>();plot.invalidate();gauge.setVisibility(View.GONE);
                            summary.setText("Нет активной поездки для выбранного источника пробега.");
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
                String estimate=estimate(samples,start,until,gpsMileage);
                runOnUiThread(()->{
                    if(isDestroyed()||generation!=loadGeneration)return;
                    plot.samples=samples;plot.selected=null;plot.invalidate();
                    gauge.setVisibility(View.GONE);
                    if(samples.isEmpty()){
                        summary.setText(label+" · за выбранный период нет показаний в диапазоне тарировки\n"+estimate);
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
                    summary.append("\n"+estimate);
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
    private String estimate(List<Sample> samples,long from,long until,boolean gpsMileage){
        List<TripStore.TripSpan> spans;
        if(gpsMileage){
            try(TripStore trips=new TripStore(this)){spans=trips.tripsInPeriod(from,until);}
        }else{
            try(CanJourneyStore trips=new CanJourneyStore(this)){spans=trips.tripsInPeriod(from,until);}
        }
        double total=0,covered=0,litres=0;
        int count=0;
        for(TripStore.TripSpan trip:spans){
            total+=trip.meters;
            if(trip.end==0||trip.meters<20000)continue;
            double used=estimateTrip(samples,trip);
            if(Double.isFinite(used)){litres+=used;covered+=trip.meters;count++;}
        }
        String distance=String.format(Locale.getDefault(),"%s за период: %.1f км",
            gpsMileage?"GPS":"CAN",total/1000);
        if(count==0)return distance+" · данных для оценки расхода пока мало";
        return distance+String.format(Locale.getDefault(),
            "\nОценка (%d поезд.): %.1f л за %.1f км · %.1f л/100 км",
            count,litres,covered/1000,litres*100000/covered);
    }
    private double estimateTrip(List<Sample> samples,TripStore.TripSpan trip){
        Sample start=null,end=null,previous=null;
        for(Sample sample:samples){
            if(sample.time<=trip.start){
                start=sample;
            }else if(sample.time<=trip.end+60000){
                if(sample.gap || (previous!=null&&Math.abs(sample.level-previous.level)>=4))return Double.NaN;
                if(sample.time>=trip.end){end=sample;break;}
            }else break;
            previous=sample;
        }
        if(start==null||end==null||!start.stable||!end.stable||trip.start-start.time>60000)return Double.NaN;
        double used=start.level-end.level;
        return used>=2&&used*100000/trip.meters<=40?used:Double.NaN;
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
        Sample selected;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path fill=new android.graphics.Path();
        Plot(){super(FuelActivity.this);setContentDescription("История уровня топлива");}
        private float x(Sample point){
            long first=samples.get(0).time,last=samples.get(samples.size()-1).time;
            return 44+Math.max(1,getWidth()-56)*((float)(point.time-first)/Math.max(1,last-first));
        }
        @Override protected void onDraw(Canvas canvas){
            canvas.drawColor(0xfff7fafc);
            if(samples.isEmpty())return;
            double max=1;
            for(Sample point:samples)max=Math.max(max,point.level);
            Sample previous=null;
            float height=Math.max(1,getHeight()-48);
            paint.setTextSize(13*getResources().getDisplayMetrics().scaledDensity);
            paint.setStrokeWidth(1f);
            paint.setColor(0xff526c87);
            for(int i=0;i<=4;i++){
                float y=18+height*i/4;
                canvas.drawText(String.format(Locale.getDefault(),"%.0f",max*(4-i)/4),4,y+4,paint);
                canvas.drawLine(44,y,getWidth()-12,y,paint);
            }
            java.text.DateFormat day=new java.text.SimpleDateFormat("dd.MM",Locale.getDefault());
            String first=day.format(new Date(samples.get(0).time));
            String last=day.format(new Date(samples.get(samples.size()-1).time));
            canvas.drawText(first,44,getHeight()-8,paint);
            if(!first.equals(last))canvas.drawText(last,getWidth()-12-paint.measureText(last),getHeight()-8,paint);
            float bottom=18+height;
            paint.setShader(new android.graphics.LinearGradient(0,18,0,bottom,
                0x77276de0,0x08276de0,android.graphics.Shader.TileMode.CLAMP));
            fill.reset();
            for(Sample point:samples){
                float px=x(point),py=18+height*(float)(1-point.level/max);
                if(previous==null||point.gap){
                    if(previous!=null){fill.lineTo(x(previous),bottom);fill.close();}
                    fill.moveTo(px,bottom);fill.lineTo(px,py);
                }else fill.lineTo(px,py);
                previous=point;
            }
            if(previous!=null){fill.lineTo(x(previous),bottom);fill.close();}
            canvas.drawPath(fill,paint);
            paint.setShader(null);
            previous=null;
            paint.setColor(0xff245ab3);paint.setStrokeWidth(3f);
            for(Sample point:samples){
                float y=18+height*(float)(1-point.level/max);
                if(previous==null||point.gap)canvas.drawCircle(x(point),y,3,paint);
                else canvas.drawLine(x(previous),18+height*(float)(1-previous.level/max),x(point),y,paint);
                previous=point;
            }
            if(selected!=null){
                float sx=x(selected),sy=18+height*(float)(1-selected.level/max);
                paint.setStrokeWidth(1f);
                canvas.drawLine(sx,18,sx,18+height,paint);
                canvas.drawCircle(sx,sy,6,paint);
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked();
            if(samples.isEmpty()||(action!=MotionEvent.ACTION_DOWN&&action!=MotionEvent.ACTION_MOVE&&
                action!=MotionEvent.ACTION_UP))return true;
            int lo=0,hi=samples.size()-1;
            while(lo<hi){
                int mid=(lo+hi)/2;
                if(x(samples.get(mid))<event.getX())lo=mid+1;else hi=mid;
            }
            Sample best=samples.get(lo);
            if(lo>0&&Math.abs(x(samples.get(lo-1))-event.getX())<Math.abs(x(best)-event.getX()))
                best=samples.get(lo-1);
            selected=best;invalidate();
            selection.setText(String.format(Locale.getDefault(),
                "%s · сырое %.2f · %.1f л · сглаженное %.1f л",date(best.time),best.raw,best.litres,best.level));
            return action!=MotionEvent.ACTION_UP||performClick();
        }
    }
}
