package ru.ladatrip.app;

/** JDK-only checks of tank calibration and out-of-range handling. */
public final class FuelLogicSmoke {
    private static int checks;
    private static void assertTrue(boolean passed,String label){
        if(!passed)throw new AssertionError(label);
        checks++;
    }
    private static void assertEq(double expected,double actual,String label){
        assertTrue(expected==actual,label+" expected "+expected+", got "+actual);
    }
    private static void testFuelCalibration(){
        FuelCalibration calibration=FuelCalibration.parse("10 4\n35 20\n60 52",55);
        assertEq(4.0,calibration.litres(10),"Fuel low endpoint");
        assertEq(36.0,calibration.litres(47.5),"Non-linear fuel interpolation");
        assertEq(52.0,calibration.litres(60),"Fuel high endpoint");
        assertTrue(Double.isNaN(calibration.litres(61)),"Do not extrapolate tank capacity");
        assertEq(15.0,FuelCalibration.parse("",55).litres(15),"Uncalibrated litres");
        for(String invalid:new String[]{"10 20\n10 30","10 30\n20 10","10 20\n20 70"}){
            boolean rejected=false;
            try{FuelCalibration.parse(invalid,55);}
            catch(IllegalArgumentException expected){rejected=true;}
            assertTrue(rejected,"Invalid fuel curve rejected");
        }
    }
    public static void main(String[] args){
        testFuelCalibration();
        System.out.println("Fuel contract tests: "+checks+" passed.");
    }
}
