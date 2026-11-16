package ru.ladatrip.canprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/** Separate app: no LADA Trip database access, no GPS, no network. */
public final class ProbeActivity extends Activity {
    static final String[] PERMISSIONS={
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_POWER",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
        "android.car.permission.CAR_ENGINE_DETAILED",
        "android.car.permission.CAR_MILEAGE",
        "android.car.permission.CAR_VENDOR_EXTENSION"
    };
    private static final int ASK=501,SAVE=502;
    private static final String HUMAX="com.humaxdigital.avtovaz.engineeringmode";
    private TextView state,log;
    private String exportText;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refreshTick=new Runnable(){
        @Override public void run(){refresh();handler.postDelayed(this,1800);}
    };
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(245,248,251));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(18),dp(20),dp(30));
        scroll.addView(root);setContentView(scroll);
        label(root,"CAN PROBE · TEST",23,true);
        label(root,"Отдельный тест автомобильных сигналов. Только чтение, без интернета, GPS и команд автомобилю.",14,false);
        label(root,"1. Выдай разрешения и включи специальную возможность CAN Probe Test в настройках Android.",14,false);
        button(root,"Проверить и запросить разрешения",v->requestCarPermissions());
        button(root,"Открыть настройки специальных возможностей",v->{
            try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
            catch(Exception e){toast("Настройки недоступны: "+e.getClass().getSimpleName());}
        });
        label(root,"2. Начни тест. Приложение запишет прямые данные Android Automotive и изменения текста CAN Information.",14,false);
        button(root,"НАЧАТЬ НОВЫЙ ТЕСТ",v->startTest());
        button(root,"Открыть штатное инженерное меню",v->openHome());
        label(root,"На стоящем автомобиле открой CAN Information. Поверни руль влево и вправо, проверь ручник и заднюю передачу при безопасной фиксации автомобиля. Изменения запишутся автоматически.",13,false);
        button(root,"Завершить тест",v->stopTest());
        button(root,"Сохранить JSON-отчёт на флешку",v->save());
        state=label(root,"Состояние",13,true);
        label(root,"ПОСЛЕДНИЕ СОБЫТИЯ",15,true);
        log=label(root,"Нет данных",12,false);
        refresh();
    }
    private TextView label(LinearLayout parent,String text,int size,boolean bold){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);
        v.setTextColor(Color.rgb(29,44,64));
        if(bold)v.setTypeface(null,1);
        v.setPadding(0,dp(8),0,dp(8));
        parent.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;
    }
    private void button(LinearLayout root,String label,View.OnClickListener click){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(15);
        b.setOnClickListener(click);root.addView(b,new LinearLayout.LayoutParams(-1,-2));
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    private void requestCarPermissions(){
        ArrayList<String> req=new ArrayList<>();
        StringBuilder status=new StringBuilder();
        for(String p:PERMISSIONS){
            String name=p.substring(p.lastIndexOf('.')+1);
            try{
                PermissionInfo info=getPackageManager().getPermissionInfo(p,0);
                boolean granted=checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;
                int protection=info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE;
                status.append(name).append(": ").append(granted?"выдано":"не выдано").append('\n');
                if(!granted&&protection==PermissionInfo.PROTECTION_DANGEROUS)req.add(p);
            }catch(PackageManager.NameNotFoundException e){
                status.append(name).append(": не объявлено прошивкой\n");
            }
        }
        toast(req.isEmpty()?"Новых разрешений dangerous нет":"Проверь системный диалог разрешений");
        if(!req.isEmpty())requestPermissions(req.toArray(new String[0]),ASK);
        if(state!=null)state.setText(status.toString());
    }
    private void startTest(){
        try{
            stopService(new Intent(this,ProbeService.class));
            CaptureStore.begin(this);
            startForegroundService(new Intent(this,ProbeService.class));
            toast("Запись начата. Открой CAN Information.");
            refresh();
        }catch(Exception e){toast("Не удалось запустить тест: "+e.getClass().getSimpleName());}
    }
    private void stopTest(){
        CaptureStore.end(this);
        stopService(new Intent(this,ProbeService.class));
        refresh();toast("Тест завершён. Сохрани JSON.");
    }
    private void openHome(){
        try{
            PackageInfo pkg=getPackageManager().getPackageInfo(HUMAX,PackageManager.GET_ACTIVITIES);
            ActivityInfo home=null;
            if(pkg.activities!=null)for(ActivityInfo a:pkg.activities){
                if(a.name.endsWith(".HomeActivity")){home=a;break;}
            }
            if(home==null){toast("HomeActivity не найдена");return;}
            boolean granted=home.permission==null||
                checkSelfPermission(home.permission)==PackageManager.PERMISSION_GRANTED;
            if(!home.exported||!granted){toast("HomeActivity запрещена Android");return;}
            startActivity(new Intent().setComponent(new ComponentName(home.packageName,home.name)));
        }catch(Exception e){toast("Открытие недоступно: "+e.getClass().getSimpleName());}
    }
    private boolean accessibilityEnabled(){
        try{
            String all=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName component=new ComponentName(this,MenuReaderService.class);
            if(all!=null)for(String part:all.split(":")){
                if(component.equals(ComponentName.unflattenFromString(part)))return true;
            }
        }catch(Exception ignored){}
        return false;
    }
    private void refresh(){
        if(state==null)return;
        boolean running=CaptureStore.active(this);
        android.content.SharedPreferences p=getSharedPreferences("can_probe_state",MODE_PRIVATE);
        String status="Запись: "+(running?"ИДЁТ":"ОСТАНОВЛЕНА")+
            "\nЧтение экрана: "+(accessibilityEnabled()?"РАЗРЕШЕНО":"НЕ ВКЛЮЧЕНО")+
            "\nСканирований экрана: "+p.getInt("accessibility_scans",0)+
            "\nОпросов CarService: "+p.getInt("direct_polls",0)+
            "\nНачало: "+(CaptureStore.started(this)==0?"не запускался":new java.text.SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new java.util.Date(CaptureStore.started(this))));
        state.setText(status);
        log.setText(CaptureStore.recent(this,25));
    }
    private void save(){
        try{exportText=CaptureStore.report(this).toString(2);}
        catch(Exception e){toast("Ошибка формирования отчёта");return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"can-probe-test.json");
        try{startActivityForResult(i,SAVE);}catch(Exception e){toast("Нет системного окна сохранения");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=SAVE||result!=RESULT_OK||data==null||data.getData()==null||exportText==null)return;
        try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
            if(out==null)throw new IllegalStateException("Нет доступа к файлу");
            out.write(exportText.getBytes(StandardCharsets.UTF_8));out.flush();
            toast("Отчёт сохранён");
        }catch(Exception e){toast("Ошибка сохранения: "+e.getClass().getSimpleName());}
        finally{exportText=null;}
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refreshTick);handler.post(refreshTick);}
    @Override protected void onPause(){handler.removeCallbacks(refreshTick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refreshTick);super.onDestroy();}
}
