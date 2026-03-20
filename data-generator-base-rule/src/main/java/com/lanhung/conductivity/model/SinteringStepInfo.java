package com.lanhung.conductivity.model;

import java.io.Serializable;

public class SinteringStepInfo implements Serializable {
    public final int stepOrder;
    public final double temperature;
    public final double duration;

    public SinteringStepInfo(int stepOrder, double temperature, double duration) {
        this.stepOrder = stepOrder;
        this.temperature = temperature;
        this.duration = duration;
    }
}
