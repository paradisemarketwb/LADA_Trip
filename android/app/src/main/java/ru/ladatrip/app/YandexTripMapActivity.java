package ru.ladatrip.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.geometry.Geometry;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.geometry.Polyline;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.PolylineMapObject;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.mapkit.offline_cache.OfflineCacheManager;
import com.yandex.mapkit.offline_cache.Region;
import com.yandex.mapkit.offline_cache.RegionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class YandexTripMapActivity extends Activity {
    private static boolean initialized;
    private static final String[] PROVIDERS={"yandex","2gis","google"};
    private static final String[] PROVIDER_NAMES={"Яндекс Карты","2ГИС","Google Maps"};
    private int provider;
    private TripMapWebView web;
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private LinearLayout screen;
    private TextView status,downloadStatus;
    private MapView map;
    private OfflineCacheManager offline;
    private TripStore.Route route;
    private Button navigator,download;
    private long selected;
    private int observedRegion=-1;
    private boolean started;
    private final com.yandex.mapkit.offline_cache.RegionListener regionListener=
        new com.yandex.mapkit.offline_cache.RegionListener(){
            @Override public void onRegionStateChanged(int id){updateDownload(id);}
            @Override public void onRegionProgress(int id){updateDownload(id);}
        };
    private final OfflineCacheManager.ErrorListener errorListener=new OfflineCacheManager.ErrorListener(){
        @Override public void onError(com.yandex.runtime.Error error){
            downloadStatus.setText("Не удалось обновить офлайн-каталог. Проверьте доступ к Яндекс Картам.");
        }
        @Override public void onRegionError(com.yandex.runtime.Error error,int id){
            if(observedRegion==id)downloadStatus.setText("Ошибка загрузки города. Проверьте сеть, ключ и свободное место.");
        }
    };

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        setTitle("Карта поездок");
        selected=saved==null?0:saved.getLong("selected");
        screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(12*getResources().getDisplayMetrics().density);
        screen.setPadding(padding,padding,padding,padding);
        setContentView(screen);
        provider=Math.max(0,Math.min(PROVIDERS.length-1,getPreferences(MODE_PRIVATE).getInt("provider",0)));
        button("Карта: "+PROVIDER_NAMES[provider],this::chooseProvider);
        button("Выбрать поездку",()->selectTrip(true));
        String key=getPreferences(MODE_PRIVATE).getString(keyName(),"");
        if(provider==0&&!key.isEmpty()){
            try{
                if(!initialized){
                    MapKitFactory.setApiKey(key);
                    MapKitFactory.initialize(getApplicationContext());
                    initialized=true;
                }
                map=new MapView(this);
                offline=MapKitFactory.getInstance().getOfflineCacheManager();
            }catch(RuntimeException|LinkageError failure){map=null;offline=null;}
        }
        if(provider!=0&&!key.isEmpty()){
            try{web=new TripMapWebView(this,PROVIDERS[provider],key);}
            catch(RuntimeException|LinkageError ignored){}
        }
        if(map!=null){
            screen.addView(map,new LinearLayout.LayoutParams(-1,0,1));
            download=button("Скачать офлайн-карту города",this::selectRegion);
            download.setEnabled(offline!=null);
        }else if(web!=null){
            screen.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        }else{
            TextView empty=new TextView(this);
            empty.setGravity(Gravity.CENTER);
            empty.setText(key.isEmpty()?"Введите ключ для "+PROVIDER_NAMES[provider]:
                "Картографический компонент не запустился. Доступна локальная схема.");
            screen.addView(empty,new LinearLayout.LayoutParams(-1,0,1));
        }
        if(provider!=0||!initialized)button("Ключ карты и условия подключения",this::editKey);
        status=new TextView(this);screen.addView(status);
        downloadStatus=new TextView(this);screen.addView(downloadStatus);
        navigator=button("Построить маршрут в Яндекс Навигаторе",this::launchRoute);
        navigator.setEnabled(false);
        button("Локальная схема без MapKit",()->startActivity(new Intent(this,RouteMapActivity.class)));
        if(selected==0)selectTrip(false);else loadRoute(selected);
    }

    private String keyName(){return provider==0?"mapkit_key":"map_key_"+PROVIDERS[provider];}

    private void chooseProvider(){
        new AlertDialog.Builder(this).setTitle("Встроенная карта")
            .setSingleChoiceItems(PROVIDER_NAMES,provider,(dialog,index)->{
                if(index!=provider){
                    getPreferences(MODE_PRIVATE).edit().putInt("provider",index).apply();
                    dialog.dismiss();recreate();
                }else dialog.dismiss();
            }).setNegativeButton("Отмена",null).show();
    }

    private void editKey(){
        EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("Ключ: "+PROVIDER_NAMES[provider])
            .setMessage(provider==0?"Для загрузки городов нужен доступ к офлайн-картам Яндекса.":
                "Карта использует интернет и отправляет провайдеру запросы для области поездки.")
            .setView(input)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить",(dialog,message)->{
                String key=input.getText().toString().trim();
                if(key.isEmpty())return;
                getPreferences(MODE_PRIVATE).edit().putString(keyName(),key).apply();
                recreate();
            }).show();
    }

    private Button button(String label,Runnable action){
        Button control=new Button(this);control.setText(label);control.setAllCaps(false);
        control.setOnClickListener(v->action.run());screen.addView(control);return control;
    }

    @Override protected void onStart(){
        super.onStart();
        if(web!=null)web.onResume();
        if(map==null)return;
        MapKitFactory.getInstance().onStart();
        map.onStart();
        started=true;
        if(offline!=null){
            offline.addRegionListener(regionListener);
            offline.addErrorListener(errorListener);
        }
    }

    @Override protected void onStop(){
        if(web!=null)web.onPause();
        if(started){
            if(offline!=null){
                offline.removeRegionListener(regionListener);
                offline.removeErrorListener(errorListener);
            }
            map.onStop();
            MapKitFactory.getInstance().onStop();
            started=false;
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle saved){
        super.onSaveInstanceState(saved);saved.putLong("selected",selected);
    }
    @Override protected void onDestroy(){
        reader.shutdownNow();
        if(web!=null){web.stopLoading();screen.removeView(web);web.destroy();}
        super.onDestroy();
    }

    private void selectTrip(boolean showPicker){
        reader.execute(()->{
            try(TripStore store=new TripStore(this)){
                Map<Long,String> rows=store.routeChoices();
                TripStore.Fix last=store.lastFix();
                runOnUiThread(()->{
                    if(isDestroyed()||isFinishing())return;
                    List<Long> ids=new ArrayList<>(rows.keySet());
                    if(ids.isEmpty()){
                        status.setText("Записанных GPS-поездок нет");
                        if(web!=null)web.showRoute(null,last);
                        return;
                    }
                    if(showPicker)new AlertDialog.Builder(this).setTitle("GPS-поездки")
                        .setItems(rows.values().toArray(new String[0]),(dialog,index)->loadRoute(ids.get(index))).show();
                    else if(selected==0)loadRoute(ids.get(0));
                });
            }catch(RuntimeException failure){showTripError();}
        });
    }

    private void loadRoute(long id){
        selected=id;
        navigator.setEnabled(false);
        reader.execute(()->{
            try(TripStore store=new TripStore(this)){
                TripStore.Route result=store.route(id);
                TripStore.Fix last=store.lastFix();
                runOnUiThread(()->{
                    if(isDestroyed()||selected!=id)return;
                    route=result;
                    status.setText(String.format(Locale.getDefault(),"%.1f км · остановок: %d",
                        route.meters/1000,route.stops.size()));
                    navigator.setEnabled(route.points.size()>1&&!route.truncated);
                    if(map!=null)drawRoute();
                    if(web!=null)web.showRoute(result,last);
                });
            }catch(RuntimeException failure){showTripError();}
        });
    }

    private void showTripError(){
        runOnUiThread(()->{if(!isDestroyed())status.setText("Не удалось прочитать поездку");});
    }

    private void drawRoute(){
        com.yandex.mapkit.map.Map layer=map.getMapWindow().getMap();
        MapObjectCollection objects=layer.getMapObjects();
        objects.clear();
        List<Point> points=new ArrayList<>(),segment=new ArrayList<>();
        long previous=0;
        for(TripStore.RoutePoint saved:route.points){
            if(previous>0&&saved.time-previous>120000){
                line(objects,segment);
                segment=new ArrayList<>();
            }
            Point next=point(saved);
            points.add(next);segment.add(next);previous=saved.time;
        }
        line(objects,segment);
        for(TripStore.RoutePoint stop:route.stops)objects.addPlacemark(point(stop));
        if(points.size()==1)layer.move(new CameraPosition(points.get(0),15,0,0));
        if(points.size()>1){
            Geometry geometry=Geometry.fromPolyline(new Polyline(points));
            CameraPosition position=layer.cameraPosition(geometry);
            layer.move(new CameraPosition(position.getTarget(),Math.max(1,position.getZoom()-1),0,0));
        }
    }

    private void line(MapObjectCollection objects,List<Point> points){
        if(points.size()<2)return;
        PolylineMapObject line=objects.addPolyline(new Polyline(points));
        line.setStrokeColor(0xff2370dd);line.setStrokeWidth(5);
        line.setOutlineColor(0xfff8fbff);line.setOutlineWidth(1);
    }
    private Point point(TripStore.RoutePoint point){return new Point(point.lat,point.lon);}
    private void launchRoute(){
        if(route!=null&&route.points.size()>1&&!route.truncated)
            GpsSettingsActivity.openRoute(this,route.points.get(0),route.points.get(route.points.size()-1));
    }

    private void selectRegion(){
        if(offline==null)return;
        List<Region> regions=offline.regions();
        if(regions.isEmpty()){
            downloadStatus.setText("Каталог пока пуст. Проверьте сеть и доступ к офлайн-картам для этого ключа.");
            return;
        }
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Название города или региона");
        form.addView(search);
        ListView list=new ListView(this);
        int height=Math.round(300*getResources().getDisplayMetrics().density);
        form.addView(list,new LinearLayout.LayoutParams(-1,height));
        List<Region> visible=new ArrayList<>();
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>());
        list.setAdapter(adapter);
        Runnable filter=()->{
            String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            List<String> labels=new ArrayList<>();
            for(Region region:regions){
                String name=region.getName();
                if(!name.toLowerCase(Locale.ROOT).contains(query))continue;
                visible.add(region);
                labels.add(name+(region.getSize()==null?"":" · "+region.getSize().getText()));
            }
            adapter.clear();adapter.addAll(labels);
        };
        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence text,int start,int before,int count){filter.run();}
            @Override public void afterTextChanged(Editable text){}
        });
        filter.run();
        AlertDialog catalog=new AlertDialog.Builder(this).setTitle("Офлайн-карты Яндекса")
            .setView(form).setNegativeButton("Закрыть",null).create();
        list.setOnItemClickListener((items,row,index,id)->{
            Region region=visible.get(index);
            catalog.dismiss();
            chooseDownload(region);
        });
        catalog.show();
    }

    private void chooseDownload(Region region){
        observedRegion=region.getId();
        updateDownload(observedRegion);
        RegionState state=offline.getState(observedRegion);
        if(state==RegionState.COMPLETED){
            Toast.makeText(this,region.getName()+" уже доступен офлайн",Toast.LENGTH_LONG).show();
            return;
        }
        if(state==RegionState.DOWNLOADING)return;
        if(state==RegionState.UNSUPPORTED||offline.mayBeOutOfAvailableSpace(observedRegion)){
            downloadStatus.setText("Город недоступен для загрузки или недостаточно места.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Скачать карту: "+region.getName())
            .setMessage("Загрузка по Wi-Fi. Размер пакета зависит от города.")
            .setNegativeButton("Отмена",null).setPositiveButton("Скачать",(dialog,index)->{
                offline.allowUseCellularNetwork(false);
                offline.startDownload(region.getId());
                updateDownload(region.getId());
            }).show();
    }

    private void updateDownload(int id){
        if(id!=observedRegion||offline==null)return;
        switch(offline.getState(id)){
            case DOWNLOADING:
                downloadStatus.setText("Офлайн-карта: "+Math.round(offline.getProgress(id)*100)+"%");
                break;
            case COMPLETED:downloadStatus.setText("Офлайн-карта загружена");break;
            case PAUSED:downloadStatus.setText("Загрузка приостановлена");break;
            case OUTDATED:
            case NEED_UPDATE:downloadStatus.setText("Доступно обновление карты");break;
            default:downloadStatus.setText("Офлайн-карта не загружена");
        }
    }
}
