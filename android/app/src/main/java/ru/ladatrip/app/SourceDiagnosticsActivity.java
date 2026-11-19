package ru.ladatrip.app;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SourceDiagnosticsActivity extends Activity {
    private static final String[] SOURCES={"","property","sensor","ui","elm"};
    private static final String[] LABELS={"Все источники","Vehicle HAL","CarSensor",
        "Штатный экран","ELM327"};
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private int revision;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int margin=Math.round(16*getResources().getDisplayMetrics().density);
        content.setPadding(margin,margin,margin,margin);
        TextView system=new TextView(this);
        content.addView(system);
        Button status=new Button(this);status.setText("Обновить состояние");
        content.addView(status);
        Spinner sources=new Spinner(this);
        ArrayAdapter<String> labels=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item,LABELS);
        labels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sources.setAdapter(labels);content.addView(sources);
        EditText term=new EditText(this);term.setSingleLine();
        term.setHint("Поиск по сигналу, значению или описанию");
        content.addView(term);
        Button search=new Button(this);search.setText("Показать журнал");content.addView(search);
        TextView output=new TextView(this);output.setTextIsSelectable(true);
        ScrollView logPane=new ScrollView(this);logPane.addView(output);
        content.addView(logPane,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(content);
        system.setText(systemStatus());
        status.setOnClickListener(v->system.setText(systemStatus()));
        search.setOnClickListener(v->{
            String source=SOURCES[sources.getSelectedItemPosition()];
            String query=term.getText().toString().trim();
            showEntries(source,query,output);
        });
        showEntries("","",output);
    }

    private String systemStatus(){
        StringBuilder result=new StringBuilder("Android ").append(Build.VERSION.RELEASE)
            .append(" · API ").append(Build.VERSION.SDK_INT).append("\n").append(Build.DISPLAY)
            .append("\n").append(VehicleLog.state(this)).append("\n");
        result.append(VehicleLog.running(this)?"Автомонитор включён\n":"Автомонитор выключен\n");
        result.append(GpsHealth.summary(this)).append("\n\nРазрешения:\n");
        try{
            String[] permissions=getPackageManager().getPackageInfo(getPackageName(),
                PackageManager.GET_PERMISSIONS).requestedPermissions;
            for(String permission:permissions){
                if(permission.startsWith("android.car.permission.")||permission.endsWith("FINE_LOCATION")){
                    result.append(permission.substring(permission.lastIndexOf('.')+1)).append(" · ")
                        .append(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED?
                            "разрешено":"отклонено или недоступно").append("\n");
                }
            }
        }catch(PackageManager.NameNotFoundException ignored){}
        // Keep a concise overview above the log on small displays.
        long at=VehicleLog.at(this,"SPEED");
        if(at>0)result.append("Скорость: ").append(VehicleLog.value(this,"SPEED")).append(" км/ч · ")
            .append(DateFormat.getTimeInstance().format(new Date(at))).append("\n");
        return result.toString().trim();
    }

    private void showEntries(String source,String query,TextView output){
        int request=++revision;
        output.setText("Читаем локальный журнал…");
        reader.execute(()->{
            String value;
            try(VehicleLog log=new VehicleLog(this)){value=log.recentEntries(source,query);}
            catch(RuntimeException error){value="Не удалось открыть локальный журнал.";}
            String result=value;
            runOnUiThread(()->{if(!isDestroyed()&&revision==request)output.setText(result);});
        });
    }
    @Override protected void onDestroy(){reader.shutdownNow();super.onDestroy();}
}
