package ru.ladatrip.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict read-only parsing of the Humax CAN Information accessibility tree.
 *
 * OEM layout renders labels and values in separate TextViews. Previous versions
 * de-duplicated identical "Close (1)" nodes, losing independent door states,
 * and then paired labels with the next section title. Match text by row bounds.
 */
final class HumaxCanTextParser {
    static final class TextCell {
        final String text;
        final int left,top,right,bottom;
        TextCell(String text,int left,int top,int right,int bottom) {
            this.text=text==null?"":text.trim();this.left=left;this.top=top;
            this.right=right;this.bottom=bottom;
        }
        boolean located(){return right>left && bottom>top;}
        int centerY(){return (top+bottom)/2;}
        int height(){return bottom-top;}
    }
    private static final Pattern NUMBER=Pattern.compile(
        "^([+-]?\\d+(?:[.,]\\d+)?)\\s*(?:\\(\\s*Valid\\s*\\))?\\s*(?:°C|км/ч|km/h|km|км|л|L|rpm|об/мин)?$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STEERING=Pattern.compile(
        "^(?:Unknown|Valid|Invalid)?\\s*\\(?([+-]?\\d+(?:[.,]\\d+)?)\\)?$",
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

    static Map<String,String> parseCells(List<TextCell> cells){
        Map<String,String> values=new LinkedHashMap<>();
        if(cells==null)return values;
        for(int i=0;i<cells.size();i++){
            TextCell label=cells.get(i);
            Field field=find(label.text);
            if(field==null)continue;
            String suffix=label.text.substring(field.label.length())
                .replaceFirst("^[\\s:=]+","").trim();
            String value=validated(field.key,suffix);
            if(value==null && suffix.isEmpty()){
                TextCell best=null;
                int bestScore=Integer.MAX_VALUE;
                for(int j=0;j<cells.size();j++){
                    if(j==i)continue;
                    TextCell candidate=cells.get(j);
                    if(find(candidate.text)!=null)continue;
                    String parsed=validated(field.key,candidate.text);
                    if(parsed==null)continue;
                    if(!label.located()||!candidate.located())continue;
                    int maxDelta=Math.max(10,Math.max(label.height(),candidate.height())*3/4);
                    int deltaY=Math.abs(label.centerY()-candidate.centerY());
                    if(deltaY>maxDelta)continue;
                    if(candidate.left+8<label.right)continue;
                    int deltaX=Math.max(0,candidate.left-label.right);
                    int score=deltaY*6+deltaX;
                    if(score<bestScore){best=candidate;bestScore=score;}
                }
                if(best!=null)value=validated(field.key,best.text);
                // No reliable geometry? Only use an immediate next node, and only
                // if it is a valid value. Do not guess across intervening labels.
                if(value==null && !label.located() && i+1<cells.size()){
                    TextCell next=cells.get(i+1);
                    if(find(next.text)==null)value=validated(field.key,next.text);
                }
            }
            if(value!=null)values.put(field.key,value);
        }
        return values;
    }

    private static Field find(String text){
        String s=text.trim().toLowerCase(Locale.ROOT);
        if(s.startsWith("extra rear")||s.startsWith("counter trigonometric sense rotation"))
            return null;
        for(Field field:FIELDS){
            if(s.equals(field.label)||s.startsWith(field.label+" ")||
                s.startsWith(field.label+":")||s.startsWith(field.label+"="))
                return field;
        }
        return null;
    }

    private static String validated(String key,String raw){
        if(raw==null)return null;
        String s=raw.trim();
        if(s.isEmpty()||s.length()>80)return null;
        if("STEER_RAW".equals(key)){
            Matcher m=STEERING.matcher(s);
            if(!m.matches())return null;
            double value=parseNumber(m.group(1));
            return Double.isFinite(value) && value>= -10000 && value<=10000?
                m.group(1).replace(',','.'):null;
        }
        if("SPEED".equals(key)||"FUEL_L".equals(key)||"OUTSIDE_C".equals(key)
            ||"RPM".equals(key)||"ODOMETER".equals(key)){
            Matcher m=NUMBER.matcher(s);
            if(!m.matches())return null;
            double value=parseNumber(m.group(1));
            if(!Double.isFinite(value))return null;
            if("SPEED".equals(key) && (value<0||value>400))return null;
            if("FUEL_L".equals(key) && (value<0||value>300))return null;
            if("OUTSIDE_C".equals(key) && (value< -70||value>90))return null;
            if("RPM".equals(key) && (value<0||value>12000))return null;
            if("ODOMETER".equals(key) && (value<0||value>10000000))return null;
            return m.group(1).replace(',','.');
        }
        if("FUEL_LOW".equals(key))
            return s.matches("(?i)^(true|false|0|1)$")?s.toLowerCase(Locale.ROOT):null;
        if("REVERSE".equals(key))return REVERSE.matcher(s).matches()?s:null;
        if("HANDBRAKE".equals(key))return BRAKE.matcher(s).matches()?s:null;
        if("ENGINE".equals(key))return ENGINE.matcher(s).matches()?s:null;
        if("ALARM".equals(key))return ALARM.matcher(s).matches()?s:null;
        if(key.endsWith("_DOOR")||"HOOD".equals(key)||"TAIL_GATE".equals(key))
            return DOOR.matcher(s).matches()?s:null;
        return null;
    }
    private static double parseNumber(String s){
        try{return Double.parseDouble(s.replace(',','.'));}
        catch(Exception ex){return Double.NaN;}
    }
}
