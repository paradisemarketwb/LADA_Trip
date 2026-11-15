package ru.ladatrip.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class Settings {
    private static final String PREF="trip_prefs";
    private static final String ALIAS="lada_trip_auth_aes_v1";
    private Settings(){}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    public static boolean monitor(Context c){return prefs(c).getBoolean("monitor",false);}
    public static void monitor(Context c,boolean value){prefs(c).edit().putBoolean("monitor",value).apply();}
    public static boolean autoStart(Context c){return prefs(c).getBoolean("autostart",false);}
    public static void autoStart(Context c,boolean value){prefs(c).edit().putBoolean("autostart",value).apply();}
    public static String device(Context c){return prefs(c).getString("device_id","lada-vesta-01");}
    public static String server(Context c){return prefs(c).getString("server",null);}
    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        SecretKey k=(SecretKey)ks.getKey(ALIAS,null);
        if(k!=null)return k;
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return gen.generateKey();
    }
    public static void configure(Context c,String url,String deviceId,String token) throws Exception {
        url=url.trim();deviceId=deviceId.trim();
        if (!url.matches("https://[^\\s/]+(?::[0-9]+)?(?:/[^\\s]*)?")) throw new IllegalArgumentException("Адрес сервера должен начинаться с https://");
        if (!deviceId.matches("[A-Za-z0-9_-]{3,50}"))throw new IllegalArgumentException("Некорректный ID устройства");
        if (token!=null&&!token.isEmpty()&&token.length()<24)throw new IllegalArgumentException("Ключ устройства слишком короткий (нужно 24+ символа)");
        if (token!=null&&!token.isEmpty()){
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            byte[] iv=cipher.getIV(),out=cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] packed=new byte[iv.length+out.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(out,0,packed,iv.length,out.length);
            prefs(c).edit().putString("auth_ciphertext",Base64.encodeToString(packed,Base64.NO_WRAP)).apply();
        }
        prefs(c).edit().putString("server",url.replaceAll("/+$", "")).putString("device_id",deviceId).apply();
    }
    public static String token(Context c) {
        String encoded=prefs(c).getString("auth_ciphertext",null);
        if (encoded==null)return null;
        try {
            byte[] packed=Base64.decode(encoded,Base64.DEFAULT);
            if(packed.length<28)return null;
            byte[] iv=new byte[12];System.arraycopy(packed,0,iv,0,12);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(packed,12,packed.length-12),StandardCharsets.UTF_8);
        }catch(Exception e){return null;}
    }
}
