package com.lanhung.conductivity.model;

import java.io.Serializable;

public class GeneratedGroup implements Serializable {
    public final MaterialSampleRow[] sampleRows;
    public final SampleDopantRow[] dopantRows;
    public final SinteringStepRow[] sinteringRows;
    public final CrystalPhaseRow[] crystalPhaseRows;

    public GeneratedGroup(MaterialSampleRow[] sampleRows, SampleDopantRow[] dopantRows,
                          SinteringStepRow[] sinteringRows, CrystalPhaseRow[] crystalPhaseRows) {
        this.sampleRows = sampleRows;
        this.dopantRows = dopantRows;
        this.sinteringRows = sinteringRows;
        this.crystalPhaseRows = crystalPhaseRows;
    }
}
