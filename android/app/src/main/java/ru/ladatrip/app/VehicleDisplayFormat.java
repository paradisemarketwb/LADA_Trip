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
    static boolean hasArea(int[] areas,int requested){
        if(areas==null)return false;
        for(int area:areas)if(area==requested)return true;
        return false;
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
        if("property".equals(source)){
            if(key.endsWith("_DOOR")||"HOOD".equals(key)||"TAIL_GATE".equals(key)){
                boolean male="HOOD".equals(key)||"TAIL_GATE".equals(key);
                if("3".equals(raw))return "Не используется";
                if("1".equals(raw))return male?"Закрыт":"Закрыта";
                if("2".equals(raw))return male?"Открыт":"Открыта";
                return "Неизвестно · "+raw;
            }
            if("ENGINE".equals(key)&&"7".equals(raw))return "Работает";
            if("ENGINE".equals(key)&&"9".equals(raw))return "Выключен";
            if("REVERSE".equals(key)){
                if("2".equals(raw))return "Включена";
                if("1".equals(raw))return "Выключена";
                if("0".equals(raw))return "Не используется";
                return "Неизвестно · "+raw;
            }
            if("ALARM".equals(key)){
                switch(raw){
                    case "0":return "Снята с охраны";
                    case "1":return "Снята с охраны после тревоги";
                    case "2":return "Снята с охраны · ожидание двери";
                    case "3":return "Под охраной";
                    case "4":return "Под охраной · открыт периметр";
                    case "5":return "Задержка постановки на охрану";
                    case "6":return "Под охраной без багажника";
                    case "7":return "Тревога";
                    default:return "Неизвестно · "+raw;
                }
            }
        }
        if("GEAR".equals(key)&&"sensor".equals(source))return "Код "+raw;
        return raw;
    }
    /** Metres from consecutive CAN speeds in km/h and elapsed milliseconds. */
    public static double integratedMeters(double previous,double current,long elapsedMs){
        if(!Double.isFinite(previous)||!Double.isFinite(current)||
            previous<0||current<0||previous>240||current>240||
            elapsedMs<=0||elapsedMs>12000)return 0;
        return ((previous+current)/2.0)*elapsedMs/3600.0;
    }
    /**
     * In measured Humax R2 vehicle-property output speed is already km/h.
     * Sensor-manager speed is a separate path and uses Android's m/s contract.
     */
    public static double speedKmh(double raw,String source){
        if(!Double.isFinite(raw)||raw<0)return Double.NaN;
        switch(source){
            case "sensor": return raw*3.6;
            case "property":
            case "ui": return raw;
            default: return Double.NaN;
        }
    }

    public static final class SpeedCalibration {
        public final double factor, offset;
        private SpeedCalibration(double factor,double offset){
            if(!Double.isFinite(factor)||factor<=0||!Double.isFinite(offset))
                throw new IllegalArgumentException("Invalid calibration");
            this.factor=factor;this.offset=offset;
        }
        public double correct(double speed){
            if(!Double.isFinite(speed)||speed<0)return Double.NaN;
            return speed==0?0:Math.max(0,speed*factor+offset);
        }
        static SpeedCalibration manual(double factor,double offset){
            if(factor<0.5||factor>2||Math.abs(offset)>30)
                throw new IllegalArgumentException("Manual correction outside range");
            return new SpeedCalibration(factor,offset);
        }
        static SpeedCalibration fromCruise(SpeedCalibration current,double system,double reference){
            if(!Double.isFinite(system)||system<10||system>240||
                !Double.isFinite(reference)||reference<10||reference>240)
                throw new IllegalArgumentException("Cruise speed outside range");
            SpeedCalibration base=current==null?manual(1,0):current;
            double shown=base.correct(system);
            if(shown<1)throw new IllegalArgumentException("Invalid displayed speed");
            double ratio=reference/shown;
            return manual(base.factor*ratio,base.offset*ratio);
        }
        /** Least-squares fit for control points expressed in km/h. */
        public static SpeedCalibration fit(double[] measured,double[] reference){
            if(measured.length!=reference.length||measured.length<2)
                throw new IllegalArgumentException("Two distinct control points required");
            double x=0,y=0;
            for(int i=0;i<measured.length;i++){
                if(!Double.isFinite(measured[i])||!Double.isFinite(reference[i])||
                    measured[i]<0||reference[i]<0||measured[i]>240||reference[i]>240)
                    throw new IllegalArgumentException("Control point outside 0–240 km/h");
                x+=measured[i];y+=reference[i];
            }
            x/=measured.length;y/=measured.length;
            double variance=0,covariance=0;
            for(int i=0;i<measured.length;i++){
                double dx=measured[i]-x;
                variance+=dx*dx;covariance+=dx*(reference[i]-y);
            }
            double factor=covariance/variance;
            return new SpeedCalibration(factor,y-factor*x);
        }
        public static SpeedCalibration parse(String input){
            String[] lines=input.trim().split("\\r?\\n");
            double[] measured=new double[lines.length],reference=new double[lines.length];
            for(int i=0;i<lines.length;i++){
                String[] values=lines[i].trim().replace(',','.').split("\\s+");
                if(values.length!=2)throw new IllegalArgumentException("Expected two speeds");
                measured[i]=Double.parseDouble(values[0]);reference[i]=Double.parseDouble(values[1]);
            }
            return fit(measured,reference);
        }
    }
}