package ru.ladatrip.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class DeliveryJob extends JobService {
    private static final int JOB_ID=20261115;
    private volatile boolean cancelled;
    public static void schedule(Context c) {
        if(Settings.server(c)==null || Settings.token(c)==null)return;
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(js==null)return;
        JobInfo.Builder b=new JobInfo.Builder(JOB_ID,new ComponentName(c,DeliveryJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true);
        js.schedule(b.build());
    }
    public static void cancel(Context c) {
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(js!=null)js.cancel(JOB_ID);
    }
    @Override public boolean onStartJob(JobParameters params) {
        cancelled=false;
        new Thread(()->{
            try (TripStore db=new TripStore(this)) {
                String server=Settings.server(this),token=Settings.token(this),device=Settings.device(this);
                if(server!=null && token!=null && server.startsWith("https://")) {
                    List<TripStore.Pending> jobs=db.pending(40);
                    for(TripStore.Pending event:jobs) {
                        if(cancelled)break;
                        boolean delivered=false;
                        for(int attempt=1;attempt<=3 && !cancelled;attempt++) {
                            try {
                                if(send(server,token,device,event)) {db.ack(event.eventId);delivered=true;break;}
                                db.attempted(event.eventId,"No valid server ACK");
                            } catch(Exception e) {db.attempted(event.eventId,e.getClass().getSimpleName());}
                            if(attempt<3)try{Thread.sleep(attempt==1?2000:5000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();break;}
                        }
                        // Не повторяем постоянно при непрерывном соединении; ждём нового сетевого события.
                        if(!delivered)break;
                    }
                }
            } catch(Exception e){android.util.Log.w("LadaTrip","Delivery job error: "+e.getClass().getSimpleName());}
            new Handler(Looper.getMainLooper()).post(()->jobFinished(params,false));
        },"lada-trip-delivery").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){cancelled=true;return false;}
    private boolean send(String base,String token,String device,TripStore.Pending e)throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(base+"/v1/events").openConnection();
        try {
            conn.setConnectTimeout(7000);conn.setReadTimeout(15000);conn.setRequestMethod("POST");
            conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");
            conn.setRequestProperty("X-Device-ID",device);conn.setRequestProperty("Authorization","Bearer "+token);
            byte[] payload=e.payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try(java.io.OutputStream out=conn.getOutputStream()){out.write(payload);}
            int code=conn.getResponseCode();
            if(code!=200)return false;
            try(InputStream stream=conn.getInputStream();ByteArrayOutputStream buf=new ByteArrayOutputStream()) {
                byte[] block=new byte[1024];int n;
                while((n=stream.read(block))!=-1 && buf.size()<8192)buf.write(block,0,n);
                JSONObject ack=new JSONObject(buf.toString("UTF-8"));
                return ack.optBoolean("ok",false) && e.eventId.equals(ack.optString("event_id")) && "delivered".equals(ack.optString("delivery"));
            }
        } finally {conn.disconnect();}
    }
}
