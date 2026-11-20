package ru.ladatrip.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recorded tracks on a local coordinate grid. */
public final class RouteMapActivity extends Activity {
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private RouteView map;
    private TextView detail;
    private Button select,externalMap;
    private long selected;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        select=new Button(this);
        select.setText("Выбрать поездку");
        detail=new TextView(this);
        int padding=(int)(16*getResources().getDisplayMetrics().density);
        detail.setPadding(padding,padding,padding,padding);
        detail.setText("Загрузка поездок…");
        map=new RouteView();
        screen.addView(select,new LinearLayout.LayoutParams(-1,-2));
        screen.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        screen.addView(detail,new LinearLayout.LayoutParams(-1,-2));
        externalMap=new Button(this);
        externalMap.setText("Открыть выбранную точку в навигаторе");
        externalMap.setEnabled(false);
        externalMap.setOnClickListener(v->{
            TripStore.RoutePoint point=map.chosen;
            if(point!=null)GpsSettingsActivity.openMap(this,point.lat,point.lon);
        });
        screen.addView(externalMap,new LinearLayout.LayoutParams(-1,-2));
        setContentView(screen);
        select.setOnClickListener(v->choices(true));
        selected=state==null?0:state.getLong("selected");
        if(selected==0)choices(false);else load(selected);
    }

    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);
        state.putLong("selected",selected);
    }

    private void choices(boolean showPicker){
        reader.execute(()->{
            try(TripStore store=new TripStore(this)){
                Map<Long,String> choices=store.routeChoices();
                TripStore.Fix last=store.lastFix();
                runOnUiThread(()->{
                    if(isDestroyed())return;
                    if(choices.isEmpty()){
                        map.update(new TripStore.Route(),last);
                        detail.setText(last==null?"Нет записанных GPS-точек":"Последняя известная точка");
                        return;
                    }
                    List<Long> ids=new ArrayList<>(choices.keySet());
                    if(showPicker)new AlertDialog.Builder(this).setTitle("GPS-поездки")
                        .setItems(choices.values().toArray(new String[0]),(d,i)->load(ids.get(i))).show();
                    else load(ids.get(0));
                });
            }catch(RuntimeException e){showReadError();}
        });
    }

    private void load(long id){
        selected=id;
        reader.execute(()->{
            try(TripStore store=new TripStore(this)){
                TripStore.Route route=store.route(id);
                TripStore.Fix last=store.lastFix();
                runOnUiThread(()->{
                    if(isDestroyed()||selected!=id)return;
                    map.update(route,last);
                    detail.setText(String.format(Locale.getDefault(),
                        "%s · %.2f км · остановок: %d\n%s",
                        date(route.start),route.meters/1000,route.stops.size(),
                        route.truncated?"Показаны первые 12 000 точек":
                        "Коснитесь точки для просмотра времени и скорости."));
                });
            }catch(RuntimeException e){showReadError();}
        });
    }

    private void showReadError(){
        runOnUiThread(()->{if(!isDestroyed())detail.setText("Не удалось прочитать журнал маршрутов.");});
    }
    private String date(long time){
        return time==0?"—":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
            .format(new Date(time));
    }
    @Override protected void onDestroy(){reader.shutdownNow();super.onDestroy();}

    private final class RouteView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line=new Path();
        private final ScaleGestureDetector pinch;
        private final GestureDetector drag;
        private final float density=getResources().getDisplayMetrics().density;
        private TripStore.Route route=new TripStore.Route();
        private TripStore.Fix last;
        private double centerLat,centerLon,fit=1;
        private float zoom=1,offsetX,offsetY;
        private TripStore.RoutePoint chosen;
        RouteView(){
            super(RouteMapActivity.this);
            setContentDescription("Координатная схема поездки");
            pinch=new ScaleGestureDetector(getContext(),new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector detector){
                    float next=Math.min(50,Math.max(1,zoom*detector.getScaleFactor()));
                    float ratio=next/zoom;
                    float x=detector.getFocusX()-getWidth()/2f,y=detector.getFocusY()-getHeight()/2f;
                    offsetX=x+(offsetX-x)*ratio;offsetY=y+(offsetY-y)*ratio;
                    zoom=next;invalidate();return true;
                }
            });
            drag=new GestureDetector(getContext(),new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent e){return true;}
                @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){
                    if(!pinch.isInProgress()){offsetX-=dx;offsetY-=dy;invalidate();}
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e){
                    zoom=1;offsetX=0;offsetY=0;invalidate();return true;
                }
                @Override public boolean onSingleTapConfirmed(MotionEvent e){
                    choose(e.getX(),e.getY());return performClick();
                }
            });
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onTouchEvent(MotionEvent event){
            pinch.onTouchEvent(event);
            drag.onTouchEvent(event);
            return true;
        }
        void update(TripStore.Route value,TripStore.Fix fix){
            route=value;last=fix;chosen=null;externalMap.setEnabled(false);zoom=1;offsetX=0;offsetY=0;
            fitRoute();invalidate();
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){fitRoute();}
        private double longitudeDelta(double longitude,double origin){
            return ((longitude-origin+540)%360)-180;
        }
        private void fitRoute(){
            if(route.points.isEmpty()){
                centerLat=last==null?0:mercator(last.lat);centerLon=last==null?0:last.lon;fit=10000;
                return;
            }
            TripStore.RoutePoint first=route.points.get(0);
            double west=0,east=0,south=mercator(first.lat),north=south;
            for(TripStore.RoutePoint point:route.points){
                double longitude=longitudeDelta(point.lon,first.lon);
                west=Math.min(west,longitude);east=Math.max(east,longitude);
                double latitude=mercator(point.lat);
                south=Math.min(south,latitude);north=Math.max(north,latitude);
            }
            centerLat=(south+north)/2;centerLon=first.lon+(west+east)/2;
            fit=Math.min(.8*getWidth()/Math.max(.0001,east-west),
                .8*getHeight()/Math.max(.0001,north-south));
        }
        private double mercator(double latitude){
            double bounded=Math.max(-85.05112878,Math.min(85.05112878,latitude));
            return Math.toDegrees(Math.log(Math.tan(Math.PI/4+Math.toRadians(bounded)/2)));
        }
        private float x(double lon){return getWidth()/2f+offsetX+(float)(longitudeDelta(lon,centerLon)*fit*zoom);}
        private float y(double lat){return getHeight()/2f+offsetY+(float)((centerLat-mercator(lat))*fit*zoom);}
        private void marker(Canvas canvas,double lat,double lon,int color,float radius){
            paint.setStyle(Paint.Style.FILL);paint.setColor(color);
            canvas.drawCircle(x(lon),y(lat),radius*density,paint);
        }
        @Override protected void onDraw(Canvas canvas){
            canvas.drawColor(0xffeef3f8);
            paint.setColor(0xffd9e3ed);paint.setStrokeWidth(density);
            float step=48*density;
            for(float x=offsetX%step;x<getWidth();x+=step)canvas.drawLine(x,0,x,getHeight(),paint);
            for(float y=offsetY%step;y<getHeight();y+=step)canvas.drawLine(0,y,getWidth(),y,paint);

            line.reset();
            TripStore.RoutePoint previous=null;
            for(TripStore.RoutePoint point:route.points){
                float px=x(point.lon),py=y(point.lat);
                if(previous==null||point.time-previous.time>120000)line.moveTo(px,py);
                else line.lineTo(px,py);
                previous=point;
            }
            paint.setStyle(Paint.Style.STROKE);paint.setColor(0xff245ab3);
            paint.setStrokeWidth(3*density);canvas.drawPath(line,paint);
            if(!route.points.isEmpty()){
                TripStore.RoutePoint first=route.points.get(0);
                marker(canvas,first.lat,first.lon,0xff13764b,6);
                if(route.points.size()>1&&previous!=null)
                    marker(canvas,previous.lat,previous.lon,0xff963532,5);
            }
            for(TripStore.RoutePoint stop:route.stops)marker(canvas,stop.lat,stop.lon,0xffa4530c,5);
            if(last!=null)marker(canvas,last.lat,last.lon,0xff34445d,4);
            if(chosen!=null)marker(canvas,chosen.lat,chosen.lon,Color.BLACK,6);
        }
        private void choose(float x,float y){
            float best=24*density;
            TripStore.RoutePoint winner=null;
            for(List<TripStore.RoutePoint> group:java.util.Arrays.asList(route.stops,route.points)){
                for(TripStore.RoutePoint point:group){
                    float distance=(float)Math.hypot(x-x(point.lon),y-y(point.lat));
                    if(distance<best){best=distance;winner=point;}
                }
            }
            if(winner==null)return;
            chosen=winner;externalMap.setEnabled(true);invalidate();
            boolean stop=route.stops.contains(winner);
            String speed=stop?"остановка":Double.isFinite(winner.speed)?
                String.format(Locale.getDefault(),"%.1f км/ч",winner.speed*3.6):"скорость неизвестна";
            detail.setText(date(winner.time)+" · "+speed);
        }
    }
}
