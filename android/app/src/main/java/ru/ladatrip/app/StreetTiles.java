package ru.ladatrip.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Loads only requested viewport tiles. All disk and network I/O stays off the UI thread. */
final class StreetTiles {
    private static final long WEEK=7*24*60*60*1000L;
    private final File directory;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LruCache<String,Bitmap> bitmaps=new LruCache<>(48);
    private final LruCache<String,Long> retryAfter=new LruCache<>(128);
    private final Set<String> pending=new HashSet<>();
    private final Runnable redraw;
    private boolean closed;
    private long blockedUntil;

    StreetTiles(Context context,Runnable redraw){
        directory=new File(context.getCacheDir(),"street_tiles");
        this.redraw=redraw;
    }

    Bitmap tile(int zoom,int x,int y){
        String key=zoom+"-"+x+"-"+y;
        Bitmap bitmap=bitmaps.get(key);
        Long retry=retryAfter.get(key);
        long now=System.currentTimeMillis();
        if(bitmap==null&&!closed&&now>=blockedUntil&&(retry==null||now>=retry)&&pending.size()<12&&pending.add(key)){
            worker.execute(()->{
                Bitmap result=null;
                long pause=0;
                try{result=load(key,zoom,x,y);}
                catch(Throttled ignored){pause=60_000;}
                catch(IOException|RuntimeException ignored){}
                Bitmap loaded=result;
                long cooldown=pause;
                main.post(()->{
                    pending.remove(key);
                    if(closed)return;
                    if(cooldown>0)blockedUntil=System.currentTimeMillis()+cooldown;
                    if(loaded!=null)bitmaps.put(key,loaded);
                    else retryAfter.put(key,System.currentTimeMillis()+60_000);
                    redraw.run();
                });
            });
        }
        return bitmap;
    }

    private Bitmap load(String key,int zoom,int x,int y) throws IOException {
        File file=new File(directory,key+".png");
        long age=System.currentTimeMillis()-file.lastModified();
        Bitmap old=file.isFile()?BitmapFactory.decodeFile(file.getPath()):null;
        if(old!=null&&age>=0&&age<WEEK)return old;
        if(!directory.isDirectory()&&!directory.mkdirs())return old;
        HttpsURLConnection connection=(HttpsURLConnection)new URL(
            "https://tile.openstreetmap.org/"+zoom+"/"+x+"/"+y+".png").openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent","LADA-Trip/1.0 (+https://github.com/paradisemarketwb/LADA_Trip)");
        if(old!=null)connection.setIfModifiedSince(file.lastModified());
        File temporary=new File(directory,key+".tmp");
        try{
            int code=connection.getResponseCode();
            if(code==304&&old!=null){file.setLastModified(System.currentTimeMillis());return old;}
            if(code==429||code==503)throw new Throttled();
            if(code!=200)return old;
            try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(temporary)){
                byte[] buffer=new byte[8192];
                int total=0,n;
                while((n=input.read(buffer))!=-1){
                    if((total+=n)>512*1024)return old;
                    output.write(buffer,0,n);
                }
            }
            Bitmap result=BitmapFactory.decodeFile(temporary.getPath());
            if(result==null||result.getWidth()!=256||result.getHeight()!=256)return old;
            if(temporary.renameTo(file))trimCache();
            return result;
        }catch(Throttled limit){throw limit;}
        catch(IOException error){return old;}
        finally{
            temporary.delete();
            connection.disconnect();
        }
    }

    private void trimCache(){
        File[] files=directory.listFiles((dir,name)->name.endsWith(".png"));
        if(files==null||files.length<=800)return;
        java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));
        for(int i=0;i<files.length-800;i++)files[i].delete();
    }

    private static final class Throttled extends IOException {}
    void close(){closed=true;worker.shutdownNow();bitmaps.evictAll();}
}
