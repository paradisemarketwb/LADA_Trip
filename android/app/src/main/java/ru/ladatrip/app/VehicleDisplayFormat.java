package ru.ladatrip.app;

import java.util.Locale;

/** Device-facing display policy, independent of Android for deterministic tests. */
public final class VehicleDisplayFormat {
    private VehicleDisplayFormat(){}
    public static boolean fresh(long at,long now,long ageLimit){
        return at>0 && now>=at && now-at<=ageLimit;
    }
    public static boolean engineerOnly(String key){
        switch(key){
            case "STEER_RAW":case "REVERSE":case "ENGINE":
            case "ALARM":case "TAIL_GATE":case "HOOD":
            case "DRIVER_DOOR":case "PASSENGER_DOOR":
            case "REAR_LEFT_DOOR":case "REAR_RIGHT_DOOR":
                return true;
            default:return false;
        }
    }
    public static String state(String key,String raw,String source){
        if(raw==null||raw.isEmpty()||raw.equals("—"))return "—";
        if("HANDBRAKE".equals(key)){
            if("1".equals(raw)||"Затянут".equalsIgnoreCase(raw)||raw.startsWith("Applied"))return "Затянут";
            if("0".equals(raw)||"Отпущен".equalsIgnoreCase(raw)||raw.startsWith("Not Applied"))return "Отпущен";
        }
        if("IGNITION".equals(key)){
            switch(raw){
                case "1":return "LOCK";
                case "2":return "Выключено";
                case "3":return "ACC";
                case "4":return "Включено";
                case "5":return "Запуск";
                default:return "Код "+raw;
            }
        }
        if("REVERSE".equals(key)){
            if(raw.startsWith("Not Engaged"))return "Выключена";
            if(raw.startsWith("Engaged"))return "Включена";
        }
        if("ENGINE".equals(key)){
            if(raw.startsWith("Engine Running")||raw.equals("Работает"))return "Работает";
            if(raw.startsWith("Engine Off")||raw.startsWith("Engine Not Running")
                ||raw.startsWith("Engine Stopped")||raw.equals("Выключен"))return "Выключен";
        }
        if("FUEL_LOW".equals(key)){
            if(raw.equals("true")||raw.equals("1"))return "Мало";
            if(raw.equals("false")||raw.equals("0"))return "Норма";
        }
        if("STEER_RAW".equals(key)){
            try{
                double value=Double.parseDouble(raw.replace(',','.'));
                return String.format(Locale.US,"%+.0f",value);
            }catch(Exception ignored){}
        }
        if(key.endsWith("_DOOR")||"TAIL_GATE".equals(key)||"HOOD".equals(key)){
            boolean male="TAIL_GATE".equals(key)||"HOOD".equals(key);
            if(raw.startsWith("Close")||raw.startsWith("Locked"))return male?"Закрыт":"Закрыта";
            if(raw.startsWith("Open")||raw.startsWith("Unlocked"))return male?"Открыт":"Открыта";
            if(raw.startsWith("Unknown"))return "Неизвестно";
        }
        if("GEAR".equals(key)&&"sensor".equals(source))return "Код "+raw;
        return raw;
    }
    /**
     * In measured Humax R2 vehicle-property output speed is already km/h.
     * Sensor-manager speed is a separate path and uses Android's m/s contract.
     */
    /** Metres from consecutive CAN speeds in km/h and elapsed milliseconds. */
    public static double integratedMeters(double previous,double current,long elapsedMs){
        if(!Double.isFinite(previous)||!Double.isFinite(current)||
            previous<0||current<0||previous>240||current>240||
            elapsedMs<=0||elapsedMs>12000)return 0;
        return ((previous+current)/2.0)*elapsedMs/3600.0;
    }
    public static double speedKmh(double raw,String source){
        if(!Double.isFinite(raw)||raw<0)return Double.NaN;
        return "sensor".equals(source)?raw*3.6:raw;
    }
}