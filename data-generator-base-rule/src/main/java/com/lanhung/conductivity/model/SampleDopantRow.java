package com.lanhung.conductivity.model;

import java.io.Serializable;

public class SampleDopantRow implements Serializable {
    private long sampleId;
    private long recipeGroupId;
    private String dopantElement;
    private double dopantIonicRadius;
    private int dopantValence;
    private double dopantMolarFraction;

    public SampleDopantRow() {}

    public SampleDopantRow(long sampleId, long recipeGroupId, String dopantElement,
                           double dopantIonicRadius, int dopantValence,
                           double dopantMolarFraction) {
        this.sampleId = sampleId;
        this.recipeGroupId = recipeGroupId;
        this.dopantElement = dopantElement;
        this.dopantIonicRadius = dopantIonicRadius;
        this.dopantValence = dopantValence;
        this.dopantMolarFraction = dopantMolarFraction;
    }

    public long getSampleId() { return sampleId; }
    public void setSampleId(long sampleId) { this.sampleId = sampleId; }
    public long getRecipeGroupId() { return recipeGroupId; }
    public void setRecipeGroupId(long recipeGroupId) { this.recipeGroupId = recipeGroupId; }
    public String getDopantElement() { return dopantElement; }
    public void setDopantElement(String dopantElement) { this.dopantElement = dopantElement; }
    public double getDopantIonicRadius() { return dopantIonicRadius; }
    public void setDopantIonicRadius(double dopantIonicRadius) { this.dopantIonicRadius = dopantIonicRadius; }
    public int getDopantValence() { return dopantValence; }
    public void setDopantValence(int dopantValence) { this.dopantValence = dopantValence; }
    public double getDopantMolarFraction() { return dopantMolarFraction; }
    public void setDopantMolarFraction(double dopantMolarFraction) { this.dopantMolarFraction = dopantMolarFraction; }
}
