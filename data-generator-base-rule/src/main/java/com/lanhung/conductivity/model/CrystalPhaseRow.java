package com.lanhung.conductivity.model;

import java.io.Serializable;

public class CrystalPhaseRow implements Serializable {
    private long sampleId;
    private long recipeGroupId;
    private int crystalId;
    private boolean majorPhase;

    public CrystalPhaseRow() {}

    public CrystalPhaseRow(long sampleId, long recipeGroupId, int crystalId, boolean majorPhase) {
        this.sampleId = sampleId;
        this.recipeGroupId = recipeGroupId;
        this.crystalId = crystalId;
        this.majorPhase = majorPhase;
    }

    public long getSampleId() { return sampleId; }
    public void setSampleId(long sampleId) { this.sampleId = sampleId; }
    public long getRecipeGroupId() { return recipeGroupId; }
    public void setRecipeGroupId(long recipeGroupId) { this.recipeGroupId = recipeGroupId; }
    public int getCrystalId() { return crystalId; }
    public void setCrystalId(int crystalId) { this.crystalId = crystalId; }
    public boolean isMajorPhase() { return majorPhase; }
    public void setMajorPhase(boolean majorPhase) { this.majorPhase = majorPhase; }
}
