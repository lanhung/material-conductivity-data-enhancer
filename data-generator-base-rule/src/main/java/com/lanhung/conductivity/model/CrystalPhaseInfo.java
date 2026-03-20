package com.lanhung.conductivity.model;

import java.io.Serializable;

public class CrystalPhaseInfo implements Serializable {
    public final int crystalId;
    public final boolean isMajorPhase;

    public CrystalPhaseInfo(int crystalId, boolean isMajorPhase) {
        this.crystalId = crystalId;
        this.isMajorPhase = isMajorPhase;
    }
}
