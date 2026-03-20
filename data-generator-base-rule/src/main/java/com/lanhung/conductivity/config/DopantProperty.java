package com.lanhung.conductivity.config;

import java.io.Serializable;

public class DopantProperty implements Serializable {
    public final String element;
    public final double radius;
    public final int valence;
    public final double frequency;
    public final double optimalFraction;
    public final double maxSolubility;
    public final double betaAlpha;
    public final double betaBeta;
    public final double betaScale;
    public final double eaHighMin;
    public final double eaHighMax;
    public final double eaLowMin;
    public final double eaLowMax;
    public final double baseSigmaLog10;

    public DopantProperty(String element, double radius, int valence, double frequency,
                          double optimalFraction, double maxSolubility,
                          double betaAlpha, double betaBeta, double betaScale,
                          double eaHighMin, double eaHighMax,
                          double eaLowMin, double eaLowMax,
                          double baseSigmaLog10) {
        this.element = element;
        this.radius = radius;
        this.valence = valence;
        this.frequency = frequency;
        this.optimalFraction = optimalFraction;
        this.maxSolubility = maxSolubility;
        this.betaAlpha = betaAlpha;
        this.betaBeta = betaBeta;
        this.betaScale = betaScale;
        this.eaHighMin = eaHighMin;
        this.eaHighMax = eaHighMax;
        this.eaLowMin = eaLowMin;
        this.eaLowMax = eaLowMax;
        this.baseSigmaLog10 = baseSigmaLog10;
    }
}
