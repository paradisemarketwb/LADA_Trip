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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
        button(page,"Открыть последнюю GPS-точку",()->{
            try(TripStore store=new TripStore(this)){
                TripStore.Fix last=store.lastFix();
                if(last==null){message("GPS-точек пока нет");return;}
                openMap(this,last.lat,last.lon);
            }
        });
        button(page,"Карта поездок",()->startActivity(new Intent(this,YandexTripMapActivity.class)));
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
        launchNavigator(context,"yandexnavi://show_point_on_map?lat="+lat+"&lon="+lon);
    }

    static void openRoute(Context context,TripStore.RoutePoint from,TripStore.RoutePoint to){
        launchNavigator(context,"yandexnavi://build_route_on_map?lat_from="+from.lat+
            "&lon_from="+from.lon+"&lat_to="+to.lat+"&lon_to="+to.lon);
    }

    private static void launchNavigator(Context context,String link){
        Uri uri=Uri.parse(link);
        for(String app:new String[]{"ru.yandex.yandexnavi","ru.yandex.yandexnavi.auto"}){
            try{
                context.startActivity(new Intent(Intent.ACTION_VIEW,uri).setPackage(app));
                return;
            }catch(ActivityNotFoundException ignored){}
        }
        Toast.makeText(context,"Яндекс Навигатор не найден или не поддерживает эту ссылку.",Toast.LENGTH_LONG).show();
    }
    private void button(LinearLayout parent,String label,Runnable action){
        Button view=new Button(this);view.setText(label);view.setAllCaps(false);
        view.setOnClickListener(v->action.run());parent.addView(view);
    }
    private void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
