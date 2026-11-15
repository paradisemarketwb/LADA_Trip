package ru.ladatrip.app;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Only inspects readable text in Humax EngineeringMode while the user opens it manually. */
public final class EngineeringAccessibilityService extends AccessibilityService {
    private static final String TARGET="com.humaxdigital.avtovaz.engineeringmode";
    private static String lastSnapshot="Инженерное меню ещё не открыто";
    private static long lastAt=0;
    public static synchronized String snapshot(){
        if(System.currentTimeMillis()-lastAt>10*60*1000L) return "Нет свежих данных инженерного меню";
        return lastSnapshot;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null || event.getPackageName()==null || !TARGET.contentEquals(event.getPackageName()))return;
        if(event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        try{
            List<String> values=new ArrayList<>();visit(root,0,values);
            if(!values.isEmpty()){
                synchronized(EngineeringAccessibilityService.class){
                    lastSnapshot=android.text.TextUtils.join("\n",values);lastAt=System.currentTimeMillis();
                }
            }
        }finally{root.recycle();}
    }
    private void visit(AccessibilityNodeInfo node,int depth,List<String> values){
        if(node==null||depth>12||values.size()>=40)return;
        CharSequence text=node.getText();
        if(text!=null){
            String str=text.toString().replace('\n',' ').trim();
            String lower=str.toLowerCase(Locale.ROOT);
            if(str.length()>0 && str.length()<140 &&
                (lower.contains("rpm")||lower.contains("speed")||lower.contains("скорост")||
                lower.contains("обороты")||lower.contains("can ")||lower.contains("gear")||
                lower.contains("ignition")||lower.contains("рул")||lower.contains("зажиган")||
                lower.contains("передач")))values.add(str);
        }
        for(int i=0;i<node.getChildCount() && values.size()<40;i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null){try{visit(child,depth+1,values);}finally{child.recycle();}}
        }
    }
    @Override public void onInterrupt(){}
}
