package ru.ladatrip.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class GpsSettingsActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            LocationManager manager=(LocationManager)getSystemService(LOCATION_SERVICE);
            status.setText("GPS Android: "+(manager!=null&&manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ?"включён":"выключен")+"\n"+GpsHealth.indicator(GpsSettingsActivity.this,System.currentTimeMillis())
                +"\n"+GpsHealth.summary(GpsSettingsActivity.this));
            handler.postDelayed(this,1500);
        }
    };

    static SharedPreferences preferences(Context context){
        return context.getSharedPreferences("gps_and_maps",MODE_PRIVATE);
    }

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setTitle("GPS и карты");
        ScrollView scroll=new ScrollView(this);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(16*getResources().getDisplayMetrics().density);
        page.setPadding(padding,padding,padding,padding);
        scroll.addView(page);setContentView(scroll);
        status=new TextView(this);
        page.addView(status);
        button(page,"Системные настройки геолокации",()->startActivity(
            new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        button(page,"Начать / переподключить GPS",this::restartGps);
        CheckBox strict=new CheckBox(this);
        strict.setText("Строгий фильтр: принимать точки с точностью до 30 м");
        strict.setChecked(preferences(this).getBoolean("strict",false));
        strict.setOnCheckedChangeListener((v,checked)->preferences(this).edit().putBoolean("strict",checked).apply());
        page.addView(strict);
        CheckBox frequent=new CheckBox(this);
        frequent.setText("Чаще запрашивать GPS: каждые 2 секунды");
        frequent.setChecked(preferences(this).getBoolean("frequent",false));
        frequent.setOnCheckedChangeListener((v,checked)->{
            preferences(this).edit().putBoolean("frequent",checked).apply();
            if(Settings.monitor(this)&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
                startForegroundService(new Intent(this,TripService.class).setAction(TripService.ACTION_RECONNECT));
        });
        page.addView(frequent);
        TextView mapLabel=new TextView(this);mapLabel.setText("Открывать точку в");page.addView(mapLabel);
        Spinner maps=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
            new String[]{"Яндекс Навигатор","2ГИС"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        maps.setAdapter(adapter);
        maps.setSelection(preferences(this).getInt("map",0)==1?1:0);page.addView(maps);
        button(page,"Сохранить выбор карты",()->{
            preferences(this).edit().putInt("map",maps.getSelectedItemPosition()).apply();
            message("Карта выбрана");
        });
        button(page,"Открыть последнюю GPS-точку",()->{
            try(TripStore store=new TripStore(this)){
                TripStore.Fix last=store.lastFix();
                if(last==null){message("GPS-точек пока нет");return;}
                openMap(this,last.lat,last.lon);
            }
        });
        button(page,"Офлайн-карта поездок",()->startActivity(new Intent(this,RouteMapActivity.class)));
    }

    private void restartGps(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},1);
            return;
        }
        Settings.monitor(this,true);
        startForegroundService(new Intent(this,TripService.class).setAction(TripService.ACTION_RECONNECT));
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==1&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)restartGps();
    }
    static void openMap(Context context,double lat,double lon){
        boolean gis=preferences(context).getInt("map",0)==1;
        Uri uri=Uri.parse(gis?"geo:"+lat+","+lon:"yandexnavi://show_point_on_map?lat="+lat+"&lon="+lon);
        Intent view=new Intent(Intent.ACTION_VIEW,uri);
        if(gis)view.setPackage("ru.dublgis.dgismobile");
        try{
            context.startActivity(view);
        }catch(ActivityNotFoundException e){
            if(!gis){
                try{
                    context.startActivity(new Intent(Intent.ACTION_VIEW,uri).setPackage("ru.yandex.yandexnavi.auto"));
                    return;
                }catch(ActivityNotFoundException ignored){}
            }
            Toast.makeText(context,"Приложение карты недоступно. Используйте офлайн-схему.",Toast.LENGTH_LONG).show();
        }
    }
    private void button(LinearLayout parent,String label,Runnable action){
        Button view=new Button(this);view.setText(label);view.setAllCaps(false);
        view.setOnClickListener(v->action.run());parent.addView(view);
    }
    private void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
