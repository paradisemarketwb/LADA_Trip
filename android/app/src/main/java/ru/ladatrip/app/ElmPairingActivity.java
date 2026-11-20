package ru.ladatrip.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

public final class ElmPairingActivity extends Activity {
    private final LinkedHashMap<String,BluetoothDevice> devices=new LinkedHashMap<>();
    private final ArrayList<BluetoothDevice> rows=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothAdapter bluetooth;
    private BluetoothDevice pairing;
    private byte[] pin;
    private TextView status;
    private Button scan;
    private ArrayAdapter<String> labels;
    private boolean registered,scanning,pinMode;
    private final Runnable timeout=()->finishPairing("Сопряжение не завершилось. Проверьте питание адаптера и повторите попытку.");
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())){
                if(scanning){
                    scanning=false;
                    scan.setEnabled(pairing==null);
                    status.setText(devices.isEmpty()?"Адаптеры не найдены. Проверьте питание и режим обнаружения.":
                        "Выберите ваш Bluetooth Classic ELM327.");
                }
                return;
            }
            BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if(device==null)return;
            if(BluetoothDevice.ACTION_FOUND.equals(intent.getAction())){
                if(scanning&&device.getType()!=BluetoothDevice.DEVICE_TYPE_LE)add(device);
                return;
            }
            if(pairing==null||!pairing.equals(device))return;
            if(BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())){
                int variant=intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,-1);
                if(variant==BluetoothDevice.PAIRING_VARIANT_PIN&&pin!=null){
                    pinMode=true;
                    try{
                        boolean accepted=device.setPin(pin);
                        status.setText(accepted?"PIN передан Android · ожидаем сопряжение":
                            "Android не принял PIN из приложения. Проверьте системный запрос.");
                    }catch(SecurityException blocked){
                        status.setText("Магнитола разрешает ввод PIN только в системном окне.");
                    }finally{clearPin();}
                }else if(variant!=BluetoothDevice.PAIRING_VARIANT_PIN){
                    clearPin();
                    status.setText("Адаптер и магнитола выбрали системное подтверждение. Проверьте запрос Android.");
                }
                return;
            }
            if(BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())){
                int state=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.ERROR);
                if(state==BluetoothDevice.BOND_BONDED){
                    finishPairing("Сопряжение выполнено. Подключаем ELM327…");
                    add(device);connect(device);
                }else if(state==BluetoothDevice.BOND_NONE){
                    finishPairing(pinMode?"Сопряжение не удалось. Проверьте PIN и повторите попытку.":
                        "Сопряжение не завершилось. Проверьте системное подтверждение.");
                }
            }
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setTitle("Подключение ELM327");
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(16*getResources().getDisplayMetrics().density);
        page.setPadding(pad,pad,pad,pad);
        status=new TextView(this);status.setText("Выберите сопряжённый адаптер или начните поиск.");
        page.addView(status);
        scan=new Button(this);scan.setText("Найти Bluetooth Classic ELM327");
        scan.setOnClickListener(v->discover());page.addView(scan);
        Button settings=new Button(this);settings.setText("Настройки Bluetooth Android");
        settings.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        page.addView(settings);
        ListView list=new ListView(this);
        labels=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>());
        list.setAdapter(labels);
        list.setOnItemClickListener((parent,row,position,id)->select(rows.get(position)));
        page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(page);
        bluetooth=BluetoothAdapter.getDefaultAdapter();
        if(bluetooth==null){
            status.setText("Bluetooth недоступен");
            scan.setEnabled(false);settings.setVisibility(View.GONE);
            return;
        }
        IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(receiver,filter);
        registered=true;
    }

    @Override protected void onResume(){
        super.onResume();
        if(bluetooth==null)return;
        try{
            for(BluetoothDevice device:bluetooth.getBondedDevices())
                if(device.getType()!=BluetoothDevice.DEVICE_TYPE_LE)add(device);
        }catch(SecurityException blocked){status.setText("Android не предоставил доступ к Bluetooth.");}
    }

    private void add(BluetoothDevice device){
        devices.put(device.getAddress(),device);
        rows.clear();rows.addAll(devices.values());
        labels.clear();
        for(BluetoothDevice item:rows){
            String name=item.getName();
            if(name==null||name.isEmpty())name="Устройство без имени";
            labels.add(name+" · "+item.getAddress()+
                (item.getBondState()==BluetoothDevice.BOND_BONDED?" · сопряжено":""));
        }
    }

    private void discover(){
        try{
            if(!bluetooth.isEnabled()){
                status.setText("Сначала включите Bluetooth в настройках Android.");return;
            }
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},1);return;
            }
            stopDiscovery();
            scanning=bluetooth.startDiscovery();
            scan.setEnabled(!scanning);
            status.setText(scanning?"Ищем устройства Bluetooth Classic…":
                "Поиск не запустился. Проверьте Bluetooth и системную геолокацию.");
        }catch(SecurityException blocked){status.setText("Android заблокировал поиск Bluetooth.");}
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==1&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)discover();
        else if(code==1)status.setText("Для поиска Bluetooth на Android 9 требуется разрешить геолокацию.");
    }

    private void select(BluetoothDevice device){
        if(pairing!=null)return;
        stopDiscovery();
        if(device.getBondState()==BluetoothDevice.BOND_BONDED){connect(device);return;}
        EditText entry=new EditText(this);
        entry.setSingleLine(true);
        entry.setSaveEnabled(false);
        entry.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        entry.setHint("PIN из инструкции к адаптеру");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("PIN адаптера ELM327")
            .setView(entry).setNegativeButton("Отмена",null)
            .setPositiveButton("Сопрячь",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=entry.getText().toString();
            if(!value.matches("[0-9]{1,16}")){entry.setError("Введите от 1 до 16 цифр");return;}
            byte[] entered=value.getBytes(StandardCharsets.US_ASCII);
            entry.setText("");
            dialog.dismiss();
            beginPairing(device,entered);
        }));
        dialog.show();
    }

    private void beginPairing(BluetoothDevice device,byte[] entered){
        clearPin();pin=entered;pairing=device;pinMode=false;
        scan.setEnabled(false);
        status.setText("Ожидаем запрос сопряжения Android…");
        handler.postDelayed(timeout,45000);
        try{
            if(device.getBondState()!=BluetoothDevice.BOND_BONDING&&!device.createBond())
                finishPairing("Android не начал сопряжение. Проверьте состояние адаптера.");
        }catch(SecurityException blocked){finishPairing("Android не разрешил приложению начать сопряжение.");}
    }

    private void connect(BluetoothDevice device){
        try{
            startForegroundService(new Intent(this,EngineElmService.class)
                .putExtra(EngineElmService.EXTRA_ADDRESS,device.getAddress()));
            finish();
        }catch(RuntimeException failure){status.setText("Не удалось запустить диагностику ELM327.");}
    }

    private void stopDiscovery(){
        if(scanning&&bluetooth!=null){
            scanning=false;
            try{bluetooth.cancelDiscovery();}catch(SecurityException ignored){}
        }
        scan.setEnabled(pairing==null);
    }
    private void clearPin(){if(pin!=null){Arrays.fill(pin,(byte)0);pin=null;}}
    private void finishPairing(String result){
        handler.removeCallbacks(timeout);
        clearPin();pairing=null;scan.setEnabled(!scanning);status.setText(result);
    }
    @Override protected void onDestroy(){
        handler.removeCallbacks(timeout);clearPin();
        stopDiscovery();
        if(registered)unregisterReceiver(receiver);
        super.onDestroy();
    }
}
