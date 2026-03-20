package com.lanhung.conductivity.config;

import java.io.Serializable;

public class SinteringRange implements Serializable {
    public final double tempMin;
    public final double tempMax;
    public final double durMin;
    public final double durMax;

    public SinteringRange(double tempMin, double tempMax, double durMin, double durMax) {
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.durMin = durMin;
        this.durMax = durMax;
    }
}
