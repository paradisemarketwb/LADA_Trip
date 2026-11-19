package ru.ladatrip.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, read-only parser for accessible text on the Humax CAN Information page. */
final class HumaxCanTextParser {
    private static final Pattern DECIMAL=Pattern.compile(
        "^\\s*([+-]?\\d+(?:[.,]\\d+)?)\\s*(?:\\(\\s*Valid\\s*\\))?\\s*(?:°C|км/ч|km/h|км|km|л|L|rpm|об/мин)?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STEERING=Pattern.compile(
        "^\\s*(?:Unknown|Valid|Invalid)?\\s*\\(?([+-]?\\d+(?:[.,]\\d+)?)\\)?\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DOOR=Pattern.compile(
        "^(?:Close|Closed|Open|Unknown|Locked|Unlocked)(?:\\s*\\(\\d+\\))?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern REVERSE=Pattern.compile(
        "^(?:Engaged|Not Engaged|Unknown)(?:\\s*\\(\\d+\\))?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern BRAKE=Pattern.compile(
        "^(?:Applied|Not Applied|Unknown|On|Off)(?:\\s*\\(\\d+\\))?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ENGINE=Pattern.compile(
        "^(?:Engine Running|Engine Not Running|Engine Stopped|Engine Off|Ignition On|Ignition Off|Unknown|Vehicle Off)(?:\\s*\\(\\d+\\))?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ALARM=Pattern.compile(
        "^[\\p{L}][\\p{L}\\s_,/\\-]{1,60}\\s*\\(\\d+\\)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final class Field {
        final String key,label;
        Field(String key,String label){this.key=key;this.label=label;}
    }
    private static final Field[] FIELDS={
        new Field("STEER_RAW","trigonometric sense rotation"),
        new Field("REVERSE","rear gear engaged"),
        new Field("HANDBRAKE","parking brake on"),
        new Field("ENGINE","vehicle status"),
        new Field("ALARM","alarm status"),
        new Field("FUEL_LOW","fuel low"),
        new Field("FUEL_L","fuel level"),
        new Field("OUTSIDE_C","outside air temperature"),
        new Field("TAIL_GATE","tail gate"),
        new Field("HOOD","hood"),
        new Field("DRIVER_DOOR","driver door"),
        new Field("PASSENGER_DOOR","passenger door"),
        new Field("REAR_LEFT_DOOR","rear left door"),
        new Field("REAR_RIGHT_DOOR","rear right door"),
        new Field("SPEED","speed"),
        new Field("RPM","rpm"),
        new Field("ODOMETER","odometer"),
        new Field("ODOMETER","mileage")
    };
    private HumaxCanTextParser(){}
    static Map<String,String> parse(List<String> nodes){
        Map<String,String> values=new LinkedHashMap<>();
        for(int i=0;i<nodes.size();i++){
            String text=nodes.get(i);
            Field field=find(text);
            if(field==null)continue;
            String suffix=text.substring(field.label.length()).replaceFirst("^[\\s:=]+","").trim();
            String value=validated(field.key,suffix);
            if(value==null && suffix.isEmpty() && i+1<nodes.size()){
                String next=nodes.get(i+1);
                if(find(next)==null)value=validated(field.key,next);
            }
            // Reject non-values such as the following section title "CAN RX".
            if(value!=null)values.put(field.key,value);
        }
        return values;
    }
    private static Field find(String source){
        String t=source.trim().toLowerCase(Locale.ROOT);
        // These OEM lines are independent signals, not the standard rear doors or steering angle.
        if(t.startsWith("extra rear") || t.startsWith("counter trigonometric sense rotation"))return null;
        for(Field f:FIELDS){
            if(t.equals(f.label) || t.startsWith(f.label+" ") || t.startsWith(f.label+":")
                || t.startsWith(f.label+"="))return f;
        }
        return null;
    }
    private static String validated(String signal,String text){
        if(text==null)return null;
        String s=text.trim();
        if(s.isEmpty()||s.length()>70)return null;
        if("STEER_RAW".equals(signal)){
            Matcher match=STEERING.matcher(s);
            return match.matches()?match.group(1).replace(',','.'):null;
        }
        if("SPEED".equals(signal)||"FUEL_L".equals(signal)||"OUTSIDE_C".equals(signal)
            ||"RPM".equals(signal)||"ODOMETER".equals(signal)){
            Matcher match=DECIMAL.matcher(s);
            if(!match.matches())return null;
            double number;
            try{number=Double.parseDouble(match.group(1).replace(',','.'));}
            catch(NumberFormatException ex){return null;}
            if(!Double.isFinite(number))return null;
            if("SPEED".equals(signal)&&(number<0||number>400))return null;
            if("FUEL_L".equals(signal)&&(number<0||number>300))return null;
            if("OUTSIDE_C".equals(signal)&&(number< -70||number>90))return null;
            if("RPM".equals(signal)&&(number<0||number>12000))return null;
            if("ODOMETER".equals(signal)&&(number<0||number>9999999))return null;
            return match.group(1).replace(',','.');
        }
        if("FUEL_LOW".equals(signal)){
            return s.matches("(?i)^(true|false|0|1)$")?s.toLowerCase(Locale.ROOT):null;
        }
        if("REVERSE".equals(signal))return REVERSE.matcher(s).matches()?s:null;
        if("HANDBRAKE".equals(signal))return BRAKE.matcher(s).matches()?s:null;
        if("ENGINE".equals(signal))return ENGINE.matcher(s).matches()?s:null;
        if("ALARM".equals(signal))return ALARM.matcher(s).matches()?s:null;
        if(signal.endsWith("_DOOR")||"HOOD".equals(signal)||"TAIL_GATE".equals(signal))
            return DOOR.matcher(s).matches()?s:null;
        return null;
    }
}