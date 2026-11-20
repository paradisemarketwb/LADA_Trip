package ru.ladatrip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional Bluetooth Classic ELM327 read-only fallback for OEM-protected engine properties.
 * Uses only AT initialization and Mode 01 diagnostic reads: 010C RPM, 0105 coolant.
 * Never sends reset/clear-DTC/service 04/actuator commands to vehicle.
 */
public final class EngineElmService extends Service {
    public static final String ACTION_STOP="ru.ladatrip.app.ENGINE_ELM_STOP";
    public static final String EXTRA_ADDRESS="device_address";
    private static final String CHANNEL="engine_elm";
    private static final int NOTIFICATION_ID=185;
    private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final Pattern RPM_REPLY=Pattern.compile("(?i)41\\s*0C\\s*([0-9A-F]{2})\\s*([0-9A-F]{2})");
    private static final Pattern COOLANT_REPLY=Pattern.compile("(?i)41\\s*05\\s*([0-9A-F]{2})");
    private final Object lock=new Object();
    private volatile boolean active;
    private volatile BluetoothSocket socket;
    private Thread worker;
    private String address;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"LADA Trip — ELM327",NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi=PendingIntent.getActivity(this,2,new Intent(this,VehicleDashboardActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(NOTIFICATION_ID,new Notification.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_trip).setContentTitle("LADA Trip · обороты и температура")
            .setContentText("Чтение PID 010C / 0105 · ELM327")
            .setContentIntent(pi).setOngoing(true).build());
    }
    private void status(String s){
        getSharedPreferences("engine_elm",MODE_PRIVATE).edit().putString("state",s).apply();
    }
    public static String state(android.content.Context c){
        return c.getSharedPreferences("engine_elm",MODE_PRIVATE).getString("state","Не подключён");
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){
            active=false;closeSocket();status("Отключено пользователем");stopSelf();return START_NOT_STICKY;
        }
        if(active)return START_NOT_STICKY;
        address=intent==null?null:intent.getStringExtra(EXTRA_ADDRESS);
        if(address==null||!BluetoothAdapter.checkBluetoothAddress(address)){
            status("Не выбран корректный ELM327");stopSelf();return START_NOT_STICKY;
        }
        active=true;status("Подключение к Bluetooth ELM327");
        worker=new Thread(this::work,"LadaTripElm327");worker.start();
        return START_NOT_STICKY;
    }
    private void work(){
        try{
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if(adapter==null||!adapter.isEnabled())throw new IllegalStateException("Bluetooth выключен или недоступен");
            // Pairing is handled by the connection screen before starting SPP.
            BluetoothDevice device=adapter.getRemoteDevice(address);
            if(device.getBondState()!=BluetoothDevice.BOND_BONDED)
                throw new IllegalStateException("Сначала сопрягите ELM327 на экране подключения");
            BluetoothSocket connection=device.createRfcommSocketToServiceRecord(SPP);
            socket=connection;
            connection.connect();
            InputStream in=connection.getInputStream();
            OutputStream out=connection.getOutputStream();
            for(String command:new String[]{"ATZ","ATE0","ATL0","ATS0","ATH0","ATSP0"}){
                String reply=execute(out,in,command,command.equals("ATZ")?5000:3000);
                if(reply.trim().isEmpty())throw new IllegalStateException("ELM327 не ответил на "+command);
                if(reply.toUpperCase(Locale.ROOT).contains("ERROR"))
                    throw new IllegalStateException("ELM327 отклонил "+command);
            }
            status("ELM327 подключён · чтение 010C и 0105");
            while(active&&!Thread.currentThread().isInterrupted()){
                String rpmReply=execute(out,in,"010C",3500);
                Double rpm=decodeRpm(rpmReply);
                if(rpm!=null){
                    VehicleLog.put(this,"elm","RPM",String.format(Locale.US,"%.0f",rpm),
                        "ELM327 Bluetooth · OBD-II Mode 01 PID 0C");
                    VehicleLog.put(this,"elm","ENGINE",rpm>=250?"Работает":"Выключен",
                        "Derived from confirmed ELM327 RPM; no assumption from ignition.");
                }else status("Нет корректного RPM в ответе ELM327 (010C)");
                String coolantReply=execute(out,in,"0105",3500);
                Double coolant=decodeCoolant(coolantReply);
                if(coolant!=null){
                    VehicleLog.put(this,"elm","COOLANT_C",String.format(Locale.US,"%.0f",coolant),
                        "ELM327 Bluetooth · OBD-II Mode 01 PID 05");
                }else status("Нет корректной температуры ОЖ в ответе ELM327 (0105)");
                if(rpm!=null&&coolant!=null)status("ELM327 активен · RPM и ОЖ поступают");
                long until=SystemClock.uptimeMillis()+750;
                while(active&&SystemClock.uptimeMillis()<until)Thread.sleep(50);
            }
        }catch(InterruptedException ignored){
            Thread.currentThread().interrupt();
        }catch(SecurityException e){
            status("Bluetooth запрещён системой: "+e.getClass().getSimpleName());
        }catch(Exception e){
            String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            status("Ошибка ELM327: "+message.substring(0,Math.min(110,message.length())));
        }finally{
            active=false;closeSocket();
            stopSelf();
        }
    }
    private String execute(OutputStream out,InputStream in,String command,long timeout)throws Exception{
        // Do not accumulate stale responses between PIDs.
        while(in.available()>0)in.read();
        out.write((command+"\r").getBytes(StandardCharsets.US_ASCII));out.flush();
        StringBuilder b=new StringBuilder();
        long deadline=SystemClock.uptimeMillis()+timeout;
        while(active && SystemClock.uptimeMillis()<deadline){
            if(in.available()>0){
                int next=in.read();
                if(next<0)throw new IllegalStateException("Bluetooth connection closed");
                if(next=='>')return b.toString();
                if(b.length()<4096)b.append((char)next);
            }else Thread.sleep(20);
        }
        return b.toString();
    }
    private static Double decodeRpm(String response){
        if(response==null)return null;
        Matcher m=RPM_REPLY.matcher(response);
        if(!m.find())m=RPM_REPLY.matcher(response.replaceAll("\\s+",""));
        if(!m.find(0))return null;
        int a=Integer.parseInt(m.group(1),16),b=Integer.parseInt(m.group(2),16);
        double rpm=((a*256)+b)/4.0;
        return rpm>=0&&rpm<=12000?rpm:null;
    }
    private static Double decodeCoolant(String response){
        if(response==null)return null;
        Matcher m=COOLANT_REPLY.matcher(response);
        if(!m.find())m=COOLANT_REPLY.matcher(response.replaceAll("\\s+",""));
        if(!m.find(0))return null;
        double c=Integer.parseInt(m.group(1),16)-40;
        return c>=-40&&c<=215?c:null;
    }
    private void closeSocket(){
        BluetoothSocket old=socket;socket=null;
        if(old!=null)try{old.close();}catch(Exception ignored){}
    }
    @Override public void onDestroy(){
        active=false;closeSocket();
        if(worker!=null)worker.interrupt();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
