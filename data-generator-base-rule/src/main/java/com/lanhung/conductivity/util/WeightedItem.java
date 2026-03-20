package com.lanhung.conductivity.util;

import java.io.Serializable;

/**
 * 加权项，用于分类采样。替代 Scala 中的 Array[(T, Double)] 元组。
 */
public class WeightedItem<T> implements Serializable {
    public final T value;
    public final double weight;

    public WeightedItem(T value, double weight) {
        this.value = value;
        this.weight = weight;
    }
}
