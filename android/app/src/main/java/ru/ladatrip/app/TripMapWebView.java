package ru.ladatrip.app;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class TripMapWebView extends WebView {
    private final String provider,key;

    TripMapWebView(Context context,String provider,String key){
        super(context);
        this.provider=provider;this.key=key;
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(false);
        getSettings().setAllowContentAccess(false);
        getSettings().setDomStorageEnabled(true);
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                return true;
            }
        });
    }

    void showRoute(TripStore.Route route,TripStore.Fix last){
        try{
            JSONArray segments=new JSONArray(),markers=new JSONArray(),segment=new JSONArray();
            segments.put(segment);
            if(route!=null){
                long previous=0;
                for(TripStore.RoutePoint point:route.points){
                    if(previous>0&&point.time-previous>120000){
                        segment=new JSONArray();segments.put(segment);
                    }
                    segment.put(coordinates(point.lon,point.lat));previous=point.time;
                }
                if(!route.points.isEmpty()){
                    TripStore.RoutePoint first=route.points.get(0);
                    TripStore.RoutePoint end=route.points.get(route.points.size()-1);
                    markers.put(coordinates(first.lon,first.lat));
                    if(end!=first)markers.put(coordinates(end.lon,end.lat));
                }
                for(TripStore.RoutePoint stop:route.stops)markers.put(coordinates(stop.lon,stop.lat));
            }
            JSONObject data=new JSONObject().put("provider",provider).put("key",key)
                .put("segments",segments).put("markers",markers);
            if(last!=null)data.put("last",coordinates(last.lon,last.lat));
            String template;
            try(InputStream source=getContext().getAssets().open("trip-map.html");
                ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
                byte[] bytes=new byte[4096];int count;
                while((count=source.read(bytes))!=-1)buffer.write(bytes,0,count);
                template=new String(buffer.toByteArray(),StandardCharsets.UTF_8);
            }
            String json=data.toString().replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029");
            loadDataWithBaseURL("https://appassets.androidplatform.net/lada-trip-map/",
                template.replace("__LADA_MAP_DATA__",json),"text/html","UTF-8",null);
        }catch(Exception error){
            loadDataWithBaseURL(null,"<p>Не удалось показать карту этой поездки.</p>","text/html","UTF-8",null);
        }
    }

    private static JSONArray coordinates(double lon,double lat) throws org.json.JSONException {
        return new JSONArray().put(lon).put(lat);
    }
}
