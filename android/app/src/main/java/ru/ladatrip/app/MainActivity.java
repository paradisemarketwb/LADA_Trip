package ru.ladatrip.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Native vertically-oriented 960x1280 interface; works without WebView/Google dependencies. */
public final class MainActivity extends Activity {
    private static final int LOCATION_CODE=100;
    private static final int SAVE_FILE_CODE=101;
    private static final int NAVY=Color.rgb(19,31,50), BLUE=Color.rgb(58,103,205), MUTED=Color.rgb(103,116,136);
    private static final int BG=Color.rgb(244,247,251), WHITE=Color.WHITE;
    private TripStore store;
    private TextView positionView,tripView,pendingView,historyView,gpsHealthView;
    private String pendingExport;
    private Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refreshTimer=new Runnable(){@Override public void run(){refresh();handler.postDelayed(this,4000);}};
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new TripStore(this);getWindow().setStatusBarColor(NAVY);getWindow().setNavigationBarColor(NAVY);
        draw();
    }
    private GradientDrawable panel(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int top,int bottom) {LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(top),0,dp(bottom));return p;}
    private TextView text(String str,int sp,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(str);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private void draw() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=column();root.setPadding(dp(22),dp(20),dp(22),dp(28));
        scroll.addView(root);setContentView(scroll);

        LinearLayout hero=column();hero.setPadding(dp(22),dp(22),dp(22),dp(22));hero.setBackground(panel(NAVY,22));
        TextView label=text("LADA / TRIP",13,Color.rgb(150,182,240),true);label.setLetterSpacing(.16f);hero.addView(label);
        TextView big=text("Каждая поездка\nпод контролем",27,WHITE,true);big.setLineSpacing(dp(3),1f);hero.addView(big,lp(10,4));
        hero.addView(text("GPS работает без интернета",14,Color.rgb(199,212,229),false));
        root.addView(hero,lp(0,16));

        LinearLayout summary=card(root,"МОНИТОРИНГ И СВЯЗЬ");
        tripView=text("…",17,NAVY,true);summary.addView(tripView,lp(4,7));
        pendingView=text("…",14,MUTED,false);summary.addView(pendingView);
        LinearLayout buttons=row();summary.addView(buttons,lp(16,0));
        Button start=button("▶ Старт GPS",true);buttons.addView(start,new LinearLayout.LayoutParams(0,dp(52),1));
        Button stop=button("■ Стоп",false);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(52),.6f);bp.leftMargin=dp(8);buttons.addView(stop,bp);
        start.setOnClickListener(v->startMonitor());
        stop.setOnClickListener(v->{Settings.monitor(this,false);Intent i=new Intent(this,TripService.class);i.setAction(TripService.ACTION_STOP);startService(i);refresh();});

        LinearLayout gps=card(root,"ПОСЛЕДНЯЯ ГЕОПОЗИЦИЯ");
        positionView=text("Ожидаем координаты GPS",16,NAVY,true);gps.addView(positionView,lp(8,10));
        gpsHealthView=text("Состояние GPS…",12,MUTED,false);gps.addView(gpsHealthView,lp(0,10));
        Button locate=button("Открыть точку на карте  ↗",false);gps.addView(locate,new LinearLayout.LayoutParams(-1,dp(48)));
        locate.setOnClickListener(v->{TripStore.Fix f=store.lastFix();if(f==null){toast("GPS-координат пока нет");return;}
            Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(String.format(Locale.US,"https://www.google.com/maps?q=%.6f,%.6f",f.lat,f.lon)));
            try{startActivity(i);}catch(Exception e){toast("Нет приложения для карты");}
        });

        LinearLayout history=card(root,"НЕДАВНИЕ ПОЕЗДКИ");
        historyView=text("Загрузка…",14,NAVY,false);history.addView(historyView,lp(6,0));
        Button reload=button("Обновить историю",false);history.addView(reload,lp(15,0));reload.setOnClickListener(v->refresh());

        LinearLayout settings=card(root,"НАСТРОЙКИ");
        CheckBox auto=new CheckBox(this);auto.setText("Автозапуск мониторинга после включения");auto.setTextColor(NAVY);auto.setTextSize(14);auto.setChecked(Settings.autoStart(this));
        settings.addView(auto);auto.setOnCheckedChangeListener((buttonView,checked)->{Settings.autoStart(this,checked);toast(checked?"Автозапуск включён":"Автозапуск выключен");});
        Button backend=button("Сервер и Telegram",false);settings.addView(backend,lp(8,0));backend.setOnClickListener(v->showServerDialog());
        Button send=button("Отправить последнюю точку",false);settings.addView(send,lp(8,0));send.setOnClickListener(v->{
            TripStore.Fix f=store.lastFix();if(f==null){toast("Сначала нужен GPS-сигнал");return;}
            store.queue("location",f,0,0);DeliveryJob.schedule(this);toast("Сообщение поставлено в очередь");refresh();
        });

        LinearLayout car=card(root,"АВТОМОБИЛЬ · LIVE");
        car.addView(text("Скорость, топливо, наружная температура, зажигание и доступные CAN-статусы. Данные записываются локально и отображаются с указанием источника.",13,MUTED,false));
        Button live=button("Открыть автомобильную панель  →",true);
        car.addView(live,lp(12,0));
        live.setOnClickListener(v->startActivity(new Intent(this,VehicleDashboardActivity.class)));

        LinearLayout engineering=card(root,"ИНЖЕНЕРНАЯ ВЕРСИЯ");
        engineering.addView(text("Автомобильные свойства, разрешения, CAN-сервис и инженерное меню — только чтение.\nGPS и Telegram работают отдельно.",13,MUTED,false));
        Button probe=button("LADA Vehicle Probe  →",true);
        engineering.addView(probe,lp(12,0));
        probe.setOnClickListener(v->startActivity(new Intent(this,EngineeringActivity.class)));

        LinearLayout export=card(root,"ЭКСПОРТ НА USB И ДИАГНОСТИКА");
        export.addView(text("Нажмите экспорт, затем в системном окне Android выберите USB-накопитель. Доступ root не нужен.",13,MUTED,false),lp(4,12));
        Button exportTrips=button("Журнал поездок  ·  CSV",false);export.addView(exportTrips,lp(0,8));
        exportTrips.setOnClickListener(v->save("lada-trip-summary.csv","text/csv",store.exportTripsCsv()));
        Button stops=button("Журнал остановок  ·  CSV",false);export.addView(stops,lp(0,8));
        stops.setOnClickListener(v->save("lada-trip-stops.csv","text/csv",store.exportStopsCsv()));
        Button csv=button("Все GPS-точки  ·  CSV",false);export.addView(csv,lp(0,8));csv.setOnClickListener(v->save("lada-trip-points.csv","text/csv",store.exportCsv()));
        Button gpx=button("Маршруты  ·  GPX",false);export.addView(gpx,lp(0,8));gpx.setOnClickListener(v->save("lada-trip-routes.gpx","application/gpx+xml",store.exportGpx()));
        Button diagnostic=button("Диагностический отчёт  ·  JSON",false);export.addView(diagnostic);
        diagnostic.setOnClickListener(v->save("lada-trip-diagnostics.json","application/json",store.exportDiagnostics(this)));
        Button home=button("На главный экран LADA Trip",true);
        root.addView(home,lp(12,8));
        home.setOnClickListener(v->startActivity(new Intent(this,UnifiedDashboardActivity.class)));
        root.addView(text("LADA Trip v1.0 · CAN + GPS + ELM327 · Android 9",12,MUTED,false),lp(20,0));
        refresh();
    }
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout card(LinearLayout root,String header) {
        LinearLayout card=column();card.setPadding(dp(18),dp(17),dp(18),dp(18));card.setBackground(panel(WHITE,18));
        TextView title=text(header,12,BLUE,true);title.setLetterSpacing(.08f);card.addView(title);
        root.addView(card,lp(0,14));return card;
    }
    private Button button(String label,boolean primary) {
        Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setAllCaps(false);
        b.setTextColor(primary?WHITE:NAVY);b.setBackground(panel(primary?BLUE:Color.rgb(234,239,247),12));
        b.setPadding(dp(12),dp(8),dp(12),dp(8));return b;
    }
    private void refresh() {
        if(store==null || positionView==null)return;
        tripView.setText(Settings.monitor(this)?"●  Мониторинг включён":"○  Мониторинг выключен");
        boolean server=Settings.server(this)!=null && Settings.token(this)!=null;
        pendingView.setText("Ожидают отправки: "+store.countPending()+"   ·   Сервер: "+(server?"настроен":"не настроен"));
        TripStore.Fix f=store.lastFix();
        if(f==null)positionView.setText("Спутники ещё не обнаружены");
        else {
            String time=new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault()).format(new Date(f.time));
            positionView.setText(String.format(Locale.US,"%.6f, %.6f",f.lat,f.lon)+"\nТочность ±"+Math.round(f.accuracy)+" м\n"+time);
        }
        historyView.setText(store.recentTrips());
        if(gpsHealthView!=null)gpsHealthView.setText(GpsHealth.summary(this));
    }
    private void startMonitor() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_CODE);return;
        }
        LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
        if(lm==null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            new AlertDialog.Builder(this).setTitle("GPS выключен").setMessage("Включите определение местоположения Android, затем вернитесь в LADA Trip.")
                .setPositiveButton("Настройки",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("Отмена",null).show();return;
        }
        Settings.monitor(this,true);startForegroundService(new Intent(this,TripService.class));refresh();toast("GPS-мониторинг запущен");
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==LOCATION_CODE && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)startMonitor();
        else if(requestCode==LOCATION_CODE)toast("Для журнала поездок необходим доступ к GPS");
    }
    private void showServerDialog() {
        LinearLayout l=column();l.setPadding(dp(18),dp(10),dp(18),0);
        EditText url=new EditText(this);url.setSingleLine(true);url.setText(Settings.server(this)==null?"https://":Settings.server(this));url.setHint("https://trip.example.com");l.addView(text("HTTPS-адрес сервера",13,MUTED,false));l.addView(url);
        EditText id=new EditText(this);id.setSingleLine(true);id.setText(Settings.device(this));l.addView(text("ID магнитолы",13,MUTED,false),lp(8,0));l.addView(id);
        EditText token=new EditText(this);token.setSingleLine(true);token.setInputType(129);token.setHint(Settings.token(this)==null?"Ключ устройства":"Оставьте пустым, чтобы сохранить ключ");
        l.addView(text("Ключ устройства (не токен Telegram!)",13,MUTED,false),lp(8,0));l.addView(token);
        new AlertDialog.Builder(this).setTitle("Защищённая доставка").setView(l)
            .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(dialog,which)->{
                try{Settings.configure(this,url.getText().toString(),id.getText().toString(),token.getText().toString());
                    DeliveryJob.schedule(this);toast("Настройки сохранены");refresh();
                }catch(Exception e){new AlertDialog.Builder(this).setTitle("Не удалось сохранить").setMessage(e.getMessage()).setPositiveButton("ОК",null).show();}
            }).show();
    }
    private void save(String filename,String mime,String content) {
        pendingExport=content;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType(mime);i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,filename);
        try{startActivityForResult(i,SAVE_FILE_CODE);}catch(Exception e){toast("Системное окно выбора файлов недоступно");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==SAVE_FILE_CODE && resultCode==RESULT_OK && data!=null && data.getData()!=null && pendingExport!=null){
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                if(out==null)throw new IllegalStateException("Нет доступа к файлу");
                out.write(pendingExport.getBytes(StandardCharsets.UTF_8));out.flush();toast("Файл успешно сохранён");
            }catch(Exception e){toast("Ошибка экспорта: "+e.getClass().getSimpleName());}
            finally{pendingExport=null;}
        }
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refreshTimer);handler.post(refreshTimer);}
    @Override protected void onPause(){handler.removeCallbacks(refreshTimer);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refreshTimer);store.close();super.onDestroy();}
}
