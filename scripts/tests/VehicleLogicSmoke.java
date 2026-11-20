package ru.ladatrip.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JDK-only contract tests of critical automotive data paths (no car required). */
public final class VehicleLogicSmoke {
    private static int checks;
    private static void assertEq(Object expected,Object actual,String context){
        checks++;
        if(!expected.equals(actual))
            throw new AssertionError(context+" expected "+expected+", got "+actual);
    }
    private static void assertTrue(boolean value,String context){
        checks++;
        if(!value)throw new AssertionError(context);
    }
    private static HumaxCanTextParser.TextCell cell(String text,int left,int top,int right){
        return new HumaxCanTextParser.TextCell(text,left,top,right,top+26);
    }
    private static void testSpeed(){
        assertEq(34.0,VehicleDisplayFormat.speedKmh(34.0,"property"),"R2 raw km/h no extra x3.6");
        assertTrue(Math.abs(VehicleDisplayFormat.speedKmh(9.45,"sensor")-34.02)<.01,
            "CarSensor fallback m/s to km/h");
        assertEq(0.0,VehicleDisplayFormat.speedKmh(0,"property"),"Stationary Humax");
        assertEq(0.0,VehicleDisplayFormat.speedKmh(0,"sensor"),"Stationary sensor");
        assertEq(72.0,VehicleDisplayFormat.speedKmh(20,"sensor"),"Sensor at highway speed");
        assertEq(72.0,VehicleDisplayFormat.speedKmh(72,"property"),"Same speed from property");
        assertEq(72.0,VehicleDisplayFormat.speedKmh(72,"ui"),"Same speed from OEM text");
        assertTrue(Double.isNaN(VehicleDisplayFormat.speedKmh(-1,"property")),"Negative reading rejected");
        assertTrue(Double.isNaN(VehicleDisplayFormat.speedKmh(40,"unknown")),"Unknown units rejected");
        assertEq(50.0,VehicleDisplayFormat.integratedMeters(50,50,3600),"CAN distance in meters");
        assertEq(0.0,VehicleDisplayFormat.integratedMeters(50,50,30000),"Long stale sample ignored");
        assertEq("Затянут",VehicleDisplayFormat.state("HANDBRAKE","1","sensor"),"Handbrake on");
        assertEq("Отпущен",VehicleDisplayFormat.state("HANDBRAKE","0","sensor"),"Handbrake off");
        assertEq("Включено",VehicleDisplayFormat.state("IGNITION","4","property"),"Ignition on");
        assertEq("Запуск",VehicleDisplayFormat.state("IGNITION","5","property"),"Ignition start");
        assertEq("Неизвестно",VehicleDisplayFormat.state("HOOD","Unknown (0)","ui"),"Unknown state");
        assertTrue(VehicleDisplayFormat.fresh(1000,12000,12000),"Fresh <12s");
        assertTrue(!VehicleDisplayFormat.fresh(1000,13500,12000),"Old data not live");
    }
    private static void testSpeedCalibration(){
        VehicleDisplayFormat.SpeedCalibration calibration=
            VehicleDisplayFormat.SpeedCalibration.parse("20 23\r\n40 45\r\n60 67");
        assertTrue(Math.abs(calibration.factor-1.1)<1e-9,"Fitted scale");
        assertTrue(Math.abs(calibration.offset-1)<1e-9,"Fitted offset");
        assertEq(0.0,calibration.correct(0),"No indicated motion at rest");
        assertTrue(Math.abs(calibration.correct(50)-56)<1e-9,"Corrected speed");
        boolean rejected=false;
        try{VehicleDisplayFormat.SpeedCalibration.parse("20 20\n20 25");}
        catch(IllegalArgumentException expected){rejected=true;}
        assertTrue(rejected,"Duplicate inputs alone cannot yield a calibration");
    }
    private static void testAccessibility(){
        List<HumaxCanTextParser.TextCell> nodes=new ArrayList<>();
        nodes.add(cell("Driver Door",20,100,135));
        nodes.add(cell("Close (1)",265,100,360));
        nodes.add(cell("Passenger Door",20,140,180));
        nodes.add(cell("Close (1)",265,140,360));
        nodes.add(cell("Rear Left Door",20,180,180));
        nodes.add(cell("Close (1)",265,180,360));
        nodes.add(cell("Rear Right Door",20,220,185));
        nodes.add(cell("Open (2)",265,220,360));
        nodes.add(cell("Tail Gate",20,260,130));
        nodes.add(cell("Close (1)",265,260,360));
        nodes.add(cell("CAN RX",20,300,200));
        nodes.add(cell("Vehicle Status",20,350,170));
        nodes.add(cell("Engine Running (7)",265,350,420));
        nodes.add(cell("Trigonometric sense rotation",20,390,245));
        nodes.add(cell("Unknown (-760)",265,390,400));
        nodes.add(cell("Counter trigonometric sense rotation",20,430,220));
        nodes.add(cell("Unknown (99)",265,430,400));
        nodes.add(cell("Parking Brake On",20,470,175));
        nodes.add(cell("Applied (2)",265,470,390));
        Map<String,String> data=HumaxCanTextParser.parseCells(nodes);
        assertEq("Close (1)",data.get("DRIVER_DOOR"),"driver door");
        assertEq("Close (1)",data.get("PASSENGER_DOOR"),"passenger door repeated text");
        assertEq("Close (1)",data.get("REAR_LEFT_DOOR"),"rear left repeated text");
        assertEq("Open (2)",data.get("REAR_RIGHT_DOOR"),"rear right");
        assertEq("Close (1)",data.get("TAIL_GATE"),"tailgate");
        assertEq("Engine Running (7)",data.get("ENGINE"),"engine");
        assertEq("-760",data.get("STEER_RAW"),"steering sign and exact row");
        assertEq("Applied (2)",data.get("HANDBRAKE"),"OEM brake");
        for(String value:data.values())assertTrue(!value.equals("CAN RX"),"No section headings as values");
        List<HumaxCanTextParser.TextCell> sameLine=new ArrayList<>();
        sameLine.add(cell("Speed: 22.0",20,20,200));
        sameLine.add(cell("Fuel Level: 42.0",20,40,200));
        assertEq("22.0",HumaxCanTextParser.parseCells(sameLine).get("SPEED"),"OEM speed inline");
        assertEq("42.0",HumaxCanTextParser.parseCells(sameLine).get("FUEL_L"),"OEM fuel inline");
    }
    private static void testBackgroundBody(){
        assertTrue(VehicleDisplayFormat.hasArea(new int[]{1,4},4),"Exact door zone");
        assertTrue(!VehicleDisplayFormat.hasArea(new int[]{0x50},0x10),"Combined zone is not an individual door");
        assertTrue(!VehicleDisplayFormat.hasArea(null,1),"Missing zone list");
        assertEq("Открыта",VehicleDisplayFormat.state("DRIVER_DOOR","2","property"),"Open door");
        assertEq("Закрыт",VehicleDisplayFormat.state("HOOD","1","property"),"Closed hood");
        assertEq("Неизвестно · 0",VehicleDisplayFormat.state("TAIL_GATE","0","property"),"Unknown tail gate");
        assertEq("Работает",VehicleDisplayFormat.state("ENGINE","7","property"),"Running engine");
        assertEq("Включена",VehicleDisplayFormat.state("REVERSE","2","property"),"Reverse engaged");
        assertEq("Выключена",VehicleDisplayFormat.state("REVERSE","1","property"),"Reverse disengaged");
        assertEq("Тревога",VehicleDisplayFormat.state("ALARM","7","property"),"Active alarm");
    }
    private static void testManualCorrection(){
        VehicleDisplayFormat.SpeedCalibration manual=VehicleDisplayFormat.SpeedCalibration.manual(1.05,-2);
        assertEq(40.0,manual.correct(40),"Manual factor and offset");
        assertEq(0.0,manual.correct(0),"Manual offset never raises zero");
        boolean rejected=false;
        try{VehicleDisplayFormat.SpeedCalibration.manual(3,0);}
        catch(IllegalArgumentException error){rejected=true;}
        assertTrue(rejected,"Reject out-of-range manual correction");
    }
    private static void testCruiseCorrection(){
        VehicleDisplayFormat.SpeedCalibration previous=VehicleDisplayFormat.SpeedCalibration.manual(1.1,2);
        VehicleDisplayFormat.SpeedCalibration next=VehicleDisplayFormat.SpeedCalibration.fromCruise(previous,50,60);
        assertTrue(Math.abs(next.correct(50)-60)<1e-9,"Cruise reference is reproduced");
        assertTrue(Math.abs(next.offset-2*60.0/57)<1e-9,"Cruise composes existing offset");
        assertEq(0.0,next.correct(0),"Cruise correction keeps stationary speed at zero");
    }
    private static void testPastCruiseCorrection(){
        VehicleDisplayFormat.SpeedCalibration current=VehicleDisplayFormat.SpeedCalibration.manual(1.1,2);
        double original=50;
        VehicleDisplayFormat.SpeedCalibration updated=
            VehicleDisplayFormat.SpeedCalibration.fromDisplayed(current,current.correct(original),60);
        assertTrue(Math.abs(updated.correct(original)-60)<1e-9,"Past readings compose the current profile");
        assertTrue(Math.abs(VehicleDisplayFormat.SpeedCalibration.fromDisplayed(null,48,50).correct(48)-50)<1e-9,
            "Past reading without a profile uses identity");
    }
    private static void testGpsStep(){
        assertTrue(VehicleDisplayFormat.plausibleGpsStep(110,1,35,35),"Realistic step within uncertainty");
        assertTrue(!VehicleDisplayFormat.plausibleGpsStep(600,1,150,150),"Reject large GPS jump");
        assertTrue(!VehicleDisplayFormat.plausibleGpsStep(1,0,5,5),"Reject duplicate GPS time");
    }
    public static void main(String[] args){
        testSpeed();testSpeedCalibration();testAccessibility();testBackgroundBody();testManualCorrection();testCruiseCorrection();testPastCruiseCorrection();testGpsStep();
        System.out.println("Vehicle contract tests: "+checks+" passed.");
    }
}