package com.lanhung.conductivity.model;

import com.lanhung.conductivity.config.DopantProperty;

import java.io.Serializable;

public class DopantInfo implements Serializable {
    public final String element;
    public final double ionicRadius;
    public final int valence;
    public final double molarFraction;
    public final DopantProperty property;

    public DopantInfo(String element, double ionicRadius, int valence,
                      double molarFraction, DopantProperty property) {
        this.element = element;
        this.ionicRadius = ionicRadius;
        this.valence = valence;
        this.molarFraction = molarFraction;
        this.property = property;
    }
}
