package com.lanhung.conductivity.model;

import java.io.Serializable;

public class SinteringStepRow implements Serializable {
    private long sampleId;
    private long recipeGroupId;
    private int stepOrder;
    private double sinteringTemperature;
    private double sinteringDuration;

    public SinteringStepRow() {}

    public SinteringStepRow(long sampleId, long recipeGroupId, int stepOrder,
                            double sinteringTemperature, double sinteringDuration) {
        this.sampleId = sampleId;
        this.recipeGroupId = recipeGroupId;
        this.stepOrder = stepOrder;
        this.sinteringTemperature = sinteringTemperature;
        this.sinteringDuration = sinteringDuration;
    }

    public long getSampleId() { return sampleId; }
    public void setSampleId(long sampleId) { this.sampleId = sampleId; }
    public long getRecipeGroupId() { return recipeGroupId; }
    public void setRecipeGroupId(long recipeGroupId) { this.recipeGroupId = recipeGroupId; }
    public int getStepOrder() { return stepOrder; }
    public void setStepOrder(int stepOrder) { this.stepOrder = stepOrder; }
    public double getSinteringTemperature() { return sinteringTemperature; }
    public void setSinteringTemperature(double sinteringTemperature) { this.sinteringTemperature = sinteringTemperature; }
    public double getSinteringDuration() { return sinteringDuration; }
    public void setSinteringDuration(double sinteringDuration) { this.sinteringDuration = sinteringDuration; }
}
