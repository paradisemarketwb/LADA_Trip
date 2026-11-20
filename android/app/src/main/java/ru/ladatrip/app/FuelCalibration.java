package ru.ladatrip.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Monotone, piecewise-linear tank calibration for one source. */
final class FuelCalibration {
    private final List<double[]> points;
    private FuelCalibration(List<double[]> points){this.points=points;}
    static FuelCalibration parse(String input,double capacity){
        if(!Double.isFinite(capacity)||capacity<=0||capacity>300)
            throw new IllegalArgumentException("Invalid capacity");
        List<double[]> points=new ArrayList<>();
        if(input.trim().isEmpty())return new FuelCalibration(points);
        for(String line:input.trim().split("\\r?\\n")){
            String[] values=line.trim().replace(',','.').split("\\s+");
            if(values.length!=2)throw new IllegalArgumentException("Expected two numbers");
            double raw=Double.parseDouble(values[0]),litres=Double.parseDouble(values[1]);
            if(!Double.isFinite(raw)||raw<0||!Double.isFinite(litres)||litres<0||litres>capacity)
                throw new IllegalArgumentException("Control point outside tank range");
            points.add(new double[]{raw,litres});
        }
        if(points.size()<2)throw new IllegalArgumentException("Two points required");
        points.sort(Comparator.comparingDouble(point->point[0]));
        for(int i=1;i<points.size();i++){
            double[] previous=points.get(i-1),next=points.get(i);
            if(next[0]<=previous[0]||next[1]<previous[1])
                throw new IllegalArgumentException("Expected a monotone curve");
        }
        return new FuelCalibration(points);
    }
    double litres(double raw){
        if(!Double.isFinite(raw)||raw<0)return Double.NaN;
        if(points.isEmpty())return raw;
        if(raw<points.get(0)[0]||raw>points.get(points.size()-1)[0])return Double.NaN;
        for(int i=1;i<points.size();i++){
            double[] a=points.get(i-1),b=points.get(i);
            if(raw<=b[0])return a[1]+(raw-a[0])*(b[1]-a[1])/(b[0]-a[0]);
        }
        return Double.NaN;
    }
    static double consumption(double km,double litres){
        if(!Double.isFinite(km)||!Double.isFinite(litres)||km<=0||litres<=0)
            throw new IllegalArgumentException("Expected positive distance and fuel");
        double result=100*litres/km;
        if(!Double.isFinite(result))throw new IllegalArgumentException("Invalid consumption");
        return result;
    }
}
