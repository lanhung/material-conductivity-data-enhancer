package com.lanhung.conductivity.model;

import java.io.Serializable;

public class MaterialSampleRow implements Serializable {
    private long sampleId;
    private long recipeGroupId;
    private String reference;
    private String materialSourceAndPurity;
    private int synthesisMethodId;
    private int processingRouteId;
    private double operatingTemperature;
    private double conductivity;

    public MaterialSampleRow() {}

    public MaterialSampleRow(long sampleId, long recipeGroupId, String reference,
                             String materialSourceAndPurity,
                             int synthesisMethodId, int processingRouteId,
                             double operatingTemperature, double conductivity) {
        this.sampleId = sampleId;
        this.recipeGroupId = recipeGroupId;
        this.reference = reference;
        this.materialSourceAndPurity = materialSourceAndPurity;
        this.synthesisMethodId = synthesisMethodId;
        this.processingRouteId = processingRouteId;
        this.operatingTemperature = operatingTemperature;
        this.conductivity = conductivity;
    }

    public long getSampleId() { return sampleId; }
    public void setSampleId(long sampleId) { this.sampleId = sampleId; }
    public long getRecipeGroupId() { return recipeGroupId; }
    public void setRecipeGroupId(long recipeGroupId) { this.recipeGroupId = recipeGroupId; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getMaterialSourceAndPurity() { return materialSourceAndPurity; }
    public void setMaterialSourceAndPurity(String materialSourceAndPurity) { this.materialSourceAndPurity = materialSourceAndPurity; }
    public int getSynthesisMethodId() { return synthesisMethodId; }
    public void setSynthesisMethodId(int synthesisMethodId) { this.synthesisMethodId = synthesisMethodId; }
    public int getProcessingRouteId() { return processingRouteId; }
    public void setProcessingRouteId(int processingRouteId) { this.processingRouteId = processingRouteId; }
    public double getOperatingTemperature() { return operatingTemperature; }
    public void setOperatingTemperature(double operatingTemperature) { this.operatingTemperature = operatingTemperature; }
    public double getConductivity() { return conductivity; }
    public void setConductivity(double conductivity) { this.conductivity = conductivity; }
}
