package com.lanhung.conductivity.util;

import java.io.Serializable;
import java.util.Random;

/**
 * 随机采样工具类: Beta/Gamma 分布、加权分类采样和数值辅助方法。
 * 使用 Marsaglia-Tsang 方法实现 Gamma 分布，以避免对 Commons Math 的外部依赖。
 */
public final class RandomUtils implements Serializable {

    private RandomUtils() {}

    /** 将值限制在 [lo, hi] 范围内。 */
    public static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /** 四舍五入到 N 位小数。 */
    public static double roundTo(double value, int decimals) {
        double factor = Math.pow(10.0, decimals);
        return Math.round(value * factor) / factor;
    }

    /**
     * 使用 Marsaglia-Tsang 方法从 Gamma(alpha, 1) 分布中采样。
     * 当 alpha < 1 时: Gamma(alpha) = Gamma(alpha+1) * U^(1/alpha)。
     */
    public static double nextGamma(Random rng, double alpha) {
        if (alpha <= 0) throw new IllegalArgumentException("alpha must be > 0, got " + alpha);

        if (alpha < 1.0) {
            return nextGamma(rng, alpha + 1.0) * Math.pow(rng.nextDouble(), 1.0 / alpha);
        }

        double d = alpha - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);

        while (true) {
            double x = rng.nextGaussian();
            double v = 1.0 + c * x;
            while (v <= 0.0) {
                x = rng.nextGaussian();
                v = 1.0 + c * x;
            }
            v = v * v * v;
            double u = rng.nextDouble();
            double x2 = x * x;
            if (u < 1.0 - 0.0331 * x2 * x2) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * x2 + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    /**
     * 从 Beta(alpha, beta) 分布中采样。
     * X ~ Gamma(alpha), Y ~ Gamma(beta)，则 X/(X+Y) ~ Beta(alpha, beta)。
     */
    public static double nextBeta(Random rng, double alpha, double beta) {
        double x = nextGamma(rng, alpha);
        double y = nextGamma(rng, beta);
        return (x + y == 0.0) ? 0.5 : x / (x + y);
    }

    /**
     * 从 [lo, hi] 范围内的截断正态分布中采样。
     */
    public static double nextTruncatedGaussian(Random rng, double mean, double std,
                                                double lo, double hi) {
        double sample = mean + rng.nextGaussian() * std;
        int attempts = 0;
        while ((sample < lo || sample > hi) && attempts < 100) {
            sample = mean + rng.nextGaussian() * std;
            attempts++;
        }
        return clamp(sample, lo, hi);
    }

    /**
     * 加权分类采样，返回选中的值。
     * 概率无需归一化。
     */
    @SuppressWarnings("unchecked")
    public static <T> T sampleCategorical(Random rng, WeightedItem<T>[] dist) {
        int idx = sampleCategoricalIndex(rng, dist);
        return dist[idx].value;
    }

    /**
     * 返回加权数组中被选中项的索引。
     */
    public static <T> int sampleCategoricalIndex(Random rng, WeightedItem<T>[] dist) {
        double total = 0;
        for (WeightedItem<T> item : dist) {
            total += item.weight;
        }
        double r = rng.nextDouble() * total;
        double cumulative = 0.0;
        for (int i = 0; i < dist.length; i++) {
            cumulative += dist[i].weight;
            if (r <= cumulative) return i;
        }
        return dist.length - 1;
    }

    /**
     * 从加权数组中无放回采样 N 个不重复项。
     */
    @SuppressWarnings("unchecked")
    public static <T> T[] sampleWithoutReplacement(Random rng, WeightedItem<T>[] items, int n) {
        int take = Math.min(n, items.length);
        Object[] result = new Object[take];
        double[] weights = new double[items.length];
        for (int i = 0; i < items.length; i++) {
            weights[i] = items[i].weight;
        }

        int count = 0;
        while (count < take) {
            double total = 0;
            for (double w : weights) total += w;
            if (total <= 0.0) break;

            double r = rng.nextDouble() * total;
            double cumulative = 0.0;
            boolean found = false;
            for (int idx = 0; idx < items.length; idx++) {
                cumulative += weights[idx];
                if (r <= cumulative && weights[idx] > 0) {
                    result[count] = items[idx].value;
                    weights[idx] = 0.0;
                    count++;
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        @SuppressWarnings("unchecked")
        T[] out = (T[]) new Object[count];
        System.arraycopy(result, 0, out, 0, count);
        return out;
    }
}
