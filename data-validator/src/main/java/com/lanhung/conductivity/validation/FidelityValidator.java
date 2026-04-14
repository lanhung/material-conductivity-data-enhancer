package com.lanhung.conductivity.validation;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/**
 * 保真度验证器：将生成数据与真实实验数据进行统计对比，
 * 计算每个维度的相似度分数和综合置信度，并输出 CSV 报告。
 *
 * 真实数据从 Hive 外部表（HDFS Parquet）加载。
 * 真实数据置信度 = 1.0，生成数据的置信度基于与真实数据的统计距离。
 *
 * 评估维度：
 * 1. 类别分布 — Jensen-Shannon 散度（合成方法、加工路线、掺杂元素、晶相）
 * 2. 数值分布 — 分位数向量的归一化 RMSE（电导率、温度、掺杂分数、烧结温度）
 * 3. 联合分布 — 主掺杂-电导率关联的保持度
 */
public class FidelityValidator implements Serializable {

    private final SparkSession spark;
    private final String realDatabase;

    // 收集报告数据
    private final List<DimensionScore> scores = new ArrayList<>();
    private final List<CategoricalRow> categoricalRows = new ArrayList<>();
    private final List<NumericalRow> numericalRows = new ArrayList<>();
    private final List<CorrelationRow> correlationRows = new ArrayList<>();

    /**
     * 从 Hive 外部表加载真实数据。
     *
     * @param realDatabase Hive 数据库名（如 ods_zirconia_conductivity_v2）
     */
    public FidelityValidator(SparkSession spark, String realDatabase) {
        this.spark = spark;
        this.realDatabase = realDatabase;
    }

    List<DimensionScore> getScores() { return scores; }
    List<CategoricalRow> getCategoricalRows() { return categoricalRows; }
    List<NumericalRow> getNumericalRows() { return numericalRows; }
    List<CorrelationRow> getCorrelationRows() { return correlationRows; }

    /**
     * 执行保真度验证，打印控制台报告。
     */
    public double validate(Dataset<Row> genSamples, Dataset<Row> genDopants,
                           Dataset<Row> genSintering, Dataset<Row> genPhases) {
        return validate(genSamples, genDopants, genSintering, genPhases, null, null);
    }

    public double validate(Dataset<Row> genSamples, Dataset<Row> genDopants,
                           Dataset<Row> genSintering, Dataset<Row> genPhases,
                           Dataset<Row> synthesisMethodDict, Dataset<Row> processingRouteDict) {

        // 从 Hive 外部表加载真实数据
        Dataset<Row> realSamples = spark.table(realDatabase + ".material_samples");
        Dataset<Row> realDopants = spark.table(realDatabase + ".sample_dopants");
        Dataset<Row> realSintering = spark.table(realDatabase + ".sintering_steps");
        Dataset<Row> realPhases = spark.table(realDatabase + ".sample_crystal_phases");

        // 注册临时视图
        genSamples.createOrReplaceTempView("gen_samples");
        genDopants.createOrReplaceTempView("gen_dopants");
        genSintering.createOrReplaceTempView("gen_sintering");
        genPhases.createOrReplaceTempView("gen_phases");
        realSamples.createOrReplaceTempView("real_samples");
        realDopants.createOrReplaceTempView("real_dopants");
        realSintering.createOrReplaceTempView("real_sintering");
        realPhases.createOrReplaceTempView("real_phases");
        if (synthesisMethodDict != null) {
            synthesisMethodDict.createOrReplaceTempView("synthesis_method_dict");
        }
        if (processingRouteDict != null) {
            processingRouteDict.createOrReplaceTempView("processing_route_dict");
        }
        boolean hasSynthesisMethodDict = synthesisMethodDict != null;
        boolean hasProcessingRouteDict = processingRouteDict != null;
        if (!hasSynthesisMethodDict) {
            System.err.println("Fidelity warning: synthesis_method_dict not loaded; falling back to raw synthesis_method_id categories.");
        }
        if (!hasProcessingRouteDict) {
            System.err.println("Fidelity warning: processing_route_dict not loaded; falling back to raw processing_route_id categories.");
        }

        scores.clear();
        categoricalRows.clear();
        numericalRows.clear();
        correlationRows.clear();

        System.out.println(new String(new char[70]).replace('\0', '='));
        System.out.println("FIDELITY VALIDATION REPORT (Real vs Generated)");
        System.out.println(new String(new char[70]).replace('\0', '='));

        // ==================== 类别分布对比 ====================
        System.out.println("\n--- Categorical Distribution Fidelity ---\n");

        scores.add(evaluateDimension("Synthesis Method", () -> compareCategorical("Synthesis Method",
                buildNamedCategorySql("real_samples", "rs", "synthesis_method_id", "synthesis_method_dict", "sm", "name", hasSynthesisMethodDict),
                buildNamedCategorySql("gen_samples", "gs", "synthesis_method_id", "synthesis_method_dict", "sm", "name", hasSynthesisMethodDict),
                0.15)));

        scores.add(evaluateDimension("Processing Route", () -> compareCategorical("Processing Route",
                buildNamedCategorySql("real_samples", "rs", "processing_route_id", "processing_route_dict", "pr", "name", hasProcessingRouteDict),
                buildNamedCategorySql("gen_samples", "gs", "processing_route_id", "processing_route_dict", "pr", "name", hasProcessingRouteDict),
                0.10)));

        scores.add(evaluateDimension("Dopant Element", () -> compareCategorical("Dopant Element",
                "SELECT dopant_element AS category, COUNT(*) AS cnt FROM real_dopants GROUP BY dopant_element",
                "SELECT dopant_element AS category, COUNT(*) AS cnt FROM gen_dopants GROUP BY dopant_element",
                0.15)));

        scores.add(evaluateDimension("Crystal Phase (Major)", () -> compareCategorical("Crystal Phase (Major)",
                "SELECT CAST(crystal_id AS STRING) AS category, COUNT(*) AS cnt FROM real_phases WHERE CAST(is_major_phase AS INT) = 1 GROUP BY crystal_id",
                "SELECT CAST(crystal_id AS STRING) AS category, COUNT(*) AS cnt FROM gen_phases WHERE CAST(is_major_phase AS INT) = 1 GROUP BY crystal_id",
                0.10)));

        // ==================== 数值分布对比 ====================
        System.out.println("\n--- Numerical Distribution Fidelity ---\n");

        scores.add(evaluateDimension("log10(Conductivity)", () -> compareNumerical("log10(Conductivity)",
                "SELECT LOG10(conductivity) AS val FROM real_samples WHERE conductivity > 0",
                "SELECT LOG10(conductivity) AS val FROM gen_samples WHERE conductivity > 0",
                0.20)));

        scores.add(evaluateDimension("Operating Temperature", () -> compareNumerical("Operating Temperature",
                "SELECT CAST(operating_temperature AS DOUBLE) AS val FROM real_samples",
                "SELECT operating_temperature AS val FROM gen_samples",
                0.10)));

        scores.add(evaluateDimension("Dopant Molar Fraction", () -> compareNumerical("Dopant Molar Fraction",
                "SELECT CAST(dopant_molar_fraction AS DOUBLE) AS val FROM real_dopants",
                "SELECT dopant_molar_fraction AS val FROM gen_dopants",
                0.10)));

        scores.add(evaluateDimension("Sintering Temperature", () -> compareNumerical("Sintering Temperature",
                "SELECT CAST(sintering_temperature AS DOUBLE) AS val FROM real_sintering",
                "SELECT sintering_temperature AS val FROM gen_sintering",
                0.05)));

        // ==================== 联合分布对比 ====================
        System.out.println("\n--- Joint Distribution Fidelity ---\n");

        scores.add(evaluateDimension("Dopant-Conductivity Correlation",
                () -> compareDopantConductivityCorrelation(0.05)));

        // ==================== 综合置信度 ====================
        double totalWeight = 0;
        double weightedSum = 0;
        for (DimensionScore ds : scores) {
            weightedSum += ds.score * ds.weight;
            totalWeight += ds.weight;
        }
        double overallFidelity = totalWeight > 0 ? weightedSum / totalWeight : 0;

        System.out.println("\n" + new String(new char[70]).replace('\0', '='));
        System.out.println("FIDELITY SCORE SUMMARY");
        System.out.println(new String(new char[70]).replace('\0', '='));
        for (DimensionScore ds : scores) {
            System.out.printf("  %-35s  Score: %.4f  (weight: %.0f%%)%n",
                    ds.name, ds.score, ds.weight * 100);
        }
        System.out.println(new String(new char[70]).replace('\0', '-'));
        System.out.printf("  OVERALL FIDELITY (confidence):     %.4f  (real data = 1.0000)%n", overallFidelity);
        System.out.println(new String(new char[70]).replace('\0', '='));
        printInterpretation(overallFidelity);

        // 清理临时视图
        for (String view : new String[]{"gen_samples", "gen_dopants", "gen_sintering", "gen_phases",
                "real_samples", "real_dopants", "real_sintering", "real_phases",
                "synthesis_method_dict", "processing_route_dict"}) {
            spark.catalog().dropTempView(view);
        }

        return overallFidelity;
    }

    // ==================== 类别分布比较：Jensen-Shannon 散度 ====================

    private DimensionScore evaluateDimension(String name, Supplier<DimensionScore> evaluator) {
        try {
            return evaluator.get();
        } catch (Exception e) {
            throw new RuntimeException("Fidelity dimension failed [" + name + "]", e);
        }
    }

    private String buildNamedCategorySql(String sampleView, String sampleAlias,
                                         String idColumn, String dictView, String dictAlias,
                                         String nameColumn, boolean useDict) {
        if (useDict) {
            return "SELECT COALESCE(" + dictAlias + "." + nameColumn + ", CAST(" + sampleAlias + "." + idColumn + " AS STRING)) AS category, COUNT(*) AS cnt"
                    + " FROM " + sampleView + " " + sampleAlias
                    + " LEFT JOIN " + dictView + " " + dictAlias
                    + " ON " + sampleAlias + "." + idColumn + " = " + dictAlias + ".id"
                    + " GROUP BY COALESCE(" + dictAlias + "." + nameColumn + ", CAST(" + sampleAlias + "." + idColumn + " AS STRING))";
        }
        return "SELECT CAST(" + sampleAlias + "." + idColumn + " AS STRING) AS category, COUNT(*) AS cnt"
                + " FROM " + sampleView + " " + sampleAlias
                + " GROUP BY CAST(" + sampleAlias + "." + idColumn + " AS STRING)";
    }

    private DimensionScore compareCategorical(String name, String realSql, String genSql, double weight) {
        Map<String, Double> realDist = collectDistribution(name, "real", realSql);
        Map<String, Double> genDist = collectDistribution(name, "generated", genSql);

        Set<String> allCategories = new HashSet<>();
        allCategories.addAll(realDist.keySet());
        allCategories.addAll(genDist.keySet());

        double jsd = jensenShannonDivergence(realDist, genDist, allCategories);
        double normalizedJsd = jsd / Math.log(2);
        double similarity = 1.0 - normalizedJsd;

        System.out.printf("  %-30s  JSD: %.4f  Similarity: %.4f%n", name, jsd, similarity);

        List<String> sorted = allCategories.stream()
                .sorted(Comparator.comparingDouble(c -> -realDist.getOrDefault(c, 0.0)))
                .collect(Collectors.toList());
        System.out.printf("    %-25s %10s %10s %10s%n", "Category", "Real%", "Gen%", "Diff");
        for (String cat : sorted) {
            double rPct = realDist.getOrDefault(cat, 0.0) * 100;
            double gPct = genDist.getOrDefault(cat, 0.0) * 100;
            System.out.printf("    %-25s %9.2f%% %9.2f%% %+9.2f%n", cat, rPct, gPct, gPct - rPct);
            String catName = cat != null ? cat : "(unknown)";
            categoricalRows.add(new CategoricalRow(name, catName, rPct, gPct));
        }
        System.out.println();

        return new DimensionScore(name, similarity, weight);
    }

    private Map<String, Double> collectDistribution(String dimensionName, String side, String sql) {
        List<Row> rows = collectRows(
                "categorical distribution [" + dimensionName + "] (" + side + ")", sql);
        double total = rows.stream().mapToDouble(r -> r.getLong(1)).sum();
        Map<String, Double> dist = new LinkedHashMap<>();
        for (Row r : rows) {
            dist.put(r.getString(0), r.getLong(1) / total);
        }
        return dist;
    }

    private double jensenShannonDivergence(Map<String, Double> p, Map<String, Double> q, Set<String> keys) {
        double jsd = 0.0;
        for (String key : keys) {
            double pi = p.getOrDefault(key, 0.0);
            double qi = q.getOrDefault(key, 0.0);
            double mi = (pi + qi) / 2.0;
            if (pi > 0 && mi > 0) {
                jsd += 0.5 * pi * Math.log(pi / mi);
            }
            if (qi > 0 && mi > 0) {
                jsd += 0.5 * qi * Math.log(qi / mi);
            }
        }
        return jsd;
    }

    // ==================== 数值分布比较：分位数向量 RMSE ====================

    private DimensionScore compareNumerical(String name, String realSql, String genSql, double weight) {
        double[] percentiles = {0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95};
        String pctStr = Arrays.stream(percentiles)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(", "));

        String realStatSql = String.format(
                "SELECT MIN(val) AS min_val, MAX(val) AS max_val, AVG(val) AS mean_val, "
                + "STDDEV(val) AS std_val, PERCENTILE_APPROX(val, ARRAY(%s)) AS pcts, COUNT(*) AS cnt "
                + "FROM (%s)", pctStr, realSql);

        String genStatSql = String.format(
                "SELECT MIN(val) AS min_val, MAX(val) AS max_val, AVG(val) AS mean_val, "
                + "STDDEV(val) AS std_val, PERCENTILE_APPROX(val, ARRAY(%s)) AS pcts, COUNT(*) AS cnt "
                + "FROM (%s)", pctStr, genSql);

        Row realRow = collectSingleRow(
                "numerical statistics [" + name + "] (real)", realStatSql);
        Row genRow = collectSingleRow(
                "numerical statistics [" + name + "] (generated)", genStatSql);

        double realMin = realRow.getDouble(0), realMax = realRow.getDouble(1);
        double realMean = realRow.getDouble(2), realStd = realRow.isNullAt(3) ? 1.0 : realRow.getDouble(3);
        List<Double> realPcts = realRow.getList(4);
        long realCnt = realRow.getLong(5);

        double genMin = genRow.getDouble(0), genMax = genRow.getDouble(1);
        double genMean = genRow.getDouble(2), genStd = genRow.isNullAt(3) ? 1.0 : genRow.getDouble(3);
        List<Double> genPcts = genRow.getList(4);
        long genCnt = genRow.getLong(5);

        double range = Math.max(Math.abs(realMax - realMin), 1e-10);

        double sumSqDiff = 0;
        for (int i = 0; i < percentiles.length; i++) {
            double diff = (genPcts.get(i) - realPcts.get(i)) / range;
            sumSqDiff += diff * diff;
        }
        double pctRmse = Math.sqrt(sumSqDiff / percentiles.length);
        double meanDiff = Math.abs(genMean - realMean) / range;
        double stdRatio = realStd > 1e-10 ? Math.min(genStd, realStd) / Math.max(genStd, realStd) : 1.0;

        double similarity = Math.max(0, 0.6 * (1.0 - Math.min(pctRmse * 3, 1.0))
                                      + 0.2 * (1.0 - Math.min(meanDiff * 3, 1.0))
                                      + 0.2 * stdRatio);

        // 控制台输出
        System.out.printf("  %-30s  Similarity: %.4f%n", name, similarity);
        System.out.printf("    %-15s %12s %12s%n", "Statistic", "Real", "Generated");
        System.out.printf("    %-15s %12d %12d%n", "Count", realCnt, genCnt);
        System.out.printf("    %-15s %12.4f %12.4f%n", "Mean", realMean, genMean);
        System.out.printf("    %-15s %12.4f %12.4f%n", "Std", realStd, genStd);
        System.out.printf("    %-15s %12.4f %12.4f%n", "Min", realMin, genMin);
        String[] pctLabels = {"P5", "P10", "P25", "P50", "P75", "P90", "P95"};
        for (int i = 0; i < percentiles.length; i++) {
            System.out.printf("    %-15s %12.4f %12.4f%n", pctLabels[i], realPcts.get(i), genPcts.get(i));
        }
        System.out.printf("    %-15s %12.4f %12.4f%n", "Max", realMax, genMax);
        System.out.printf("    Percentile RMSE: %.4f, Mean Diff: %.4f, Std Ratio: %.4f%n",
                pctRmse, meanDiff, stdRatio);
        System.out.println();

        // 收集 CSV 数据
        numericalRows.add(new NumericalRow(name, "Count", realCnt, genCnt));
        numericalRows.add(new NumericalRow(name, "Mean", realMean, genMean));
        numericalRows.add(new NumericalRow(name, "Std", realStd, genStd));
        numericalRows.add(new NumericalRow(name, "Min", realMin, genMin));
        for (int i = 0; i < percentiles.length; i++) {
            numericalRows.add(new NumericalRow(name, pctLabels[i], realPcts.get(i), genPcts.get(i)));
        }
        numericalRows.add(new NumericalRow(name, "Max", realMax, genMax));
        numericalRows.add(new NumericalRow(name, "Pct_RMSE", pctRmse, pctRmse));
        numericalRows.add(new NumericalRow(name, "Std_Ratio", stdRatio, stdRatio));

        return new DimensionScore(name, similarity, weight);
    }

    // ==================== 联合分布：掺杂元素-电导率关联 ====================

    private DimensionScore compareDopantConductivityCorrelation(double weight) {
        String realSql =
                "SELECT d.dopant_element AS element, AVG(LOG10(CAST(m.conductivity AS DOUBLE))) AS avg_log_cond"
                + " FROM real_samples m JOIN ("
                + "   SELECT sample_id, dopant_element,"
                + "     ROW_NUMBER() OVER (PARTITION BY sample_id ORDER BY CAST(dopant_molar_fraction AS DOUBLE) DESC) AS rn"
                + "   FROM real_dopants"
                + " ) d ON CAST(m.sample_id AS LONG) = CAST(d.sample_id AS LONG) AND d.rn = 1"
                + " WHERE CAST(m.operating_temperature AS DOUBLE) BETWEEN 700 AND 900"
                + " AND CAST(m.conductivity AS DOUBLE) > 0"
                + " GROUP BY d.dopant_element HAVING COUNT(*) >= 3";

        String genSql =
                "SELECT d.dopant_element AS element, AVG(LOG10(m.conductivity)) AS avg_log_cond"
                + " FROM gen_samples m JOIN ("
                + "   SELECT sample_id, dopant_element,"
                + "     ROW_NUMBER() OVER (PARTITION BY sample_id ORDER BY dopant_molar_fraction DESC) AS rn"
                + "   FROM gen_dopants"
                + " ) d ON m.sample_id = d.sample_id AND d.rn = 1"
                + " WHERE m.operating_temperature BETWEEN 700 AND 900"
                + " AND m.conductivity > 0"
                + " GROUP BY d.dopant_element HAVING COUNT(*) >= 3";

        Map<String, Double> realMap = new LinkedHashMap<>();
        Map<String, Double> genMap = new LinkedHashMap<>();

        for (Row r : collectRows("dopant-conductivity correlation (real)", realSql)) {
            realMap.put(r.getString(0), r.getDouble(1));
        }
        for (Row r : collectRows("dopant-conductivity correlation (generated)", genSql)) {
            genMap.put(r.getString(0), r.getDouble(1));
        }

        Set<String> common = new HashSet<>(realMap.keySet());
        common.retainAll(genMap.keySet());

        double similarity;
        if (common.isEmpty()) {
            similarity = 0.5;
            System.out.println("  Dopant-Conductivity Correlation:   No overlapping dopants for comparison");
        } else {
            List<String> elements = new ArrayList<>(common);
            double[] realVals = elements.stream().mapToDouble(realMap::get).toArray();
            double[] genVals = elements.stream().mapToDouble(genMap::get).toArray();

            double pearson = pearsonCorrelation(realVals, genVals);
            double range = Arrays.stream(realVals).max().orElse(0) - Arrays.stream(realVals).min().orElse(0);
            range = Math.max(range, 1.0);
            double rmse = 0;
            for (int i = 0; i < realVals.length; i++) {
                double diff = (realVals[i] - genVals[i]) / range;
                rmse += diff * diff;
            }
            rmse = Math.sqrt(rmse / realVals.length);

            double corrScore = Math.max(0, (pearson + 1) / 2.0);
            double rmseScore = Math.max(0, 1.0 - rmse * 2);
            similarity = 0.5 * corrScore + 0.5 * rmseScore;

            System.out.printf("  Dopant-Conductivity Correlation:   Similarity: %.4f%n", similarity);
            System.out.printf("    Pearson r: %.4f, Normalized RMSE: %.4f, Common elements: %d%n",
                    pearson, rmse, common.size());
            System.out.printf("    %-8s %12s %12s%n", "Element", "Real log σ", "Gen log σ");
            for (String e : elements) {
                System.out.printf("    %-8s %12.4f %12.4f%n", e, realMap.get(e), genMap.get(e));
                correlationRows.add(new CorrelationRow(e, realMap.get(e), genMap.get(e)));
            }
        }
        System.out.println();

        return new DimensionScore("Dopant-Conductivity Correlation", similarity, weight);
    }

    private double pearsonCorrelation(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) return 0;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return denominator < 1e-10 ? 0 : numerator / denominator;
    }

    private List<Row> collectRows(String context, String sql) {
        try {
            return spark.sql(sql).collectAsList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute " + context + " SQL:\n" + sql, e);
        }
    }

    private Row collectSingleRow(String context, String sql) {
        List<Row> rows = collectRows(context, sql);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Query returned no rows for " + context + " SQL:\n" + sql);
        }
        return rows.get(0);
    }

    // ==================== 工具方法 ====================

    private void printInterpretation(double score) {
        System.out.println("\n  Interpretation:");
        if (score >= 0.90) {
            System.out.println("  [EXCELLENT] Generated data closely matches real experimental data.");
        } else if (score >= 0.75) {
            System.out.println("  [GOOD] Generated data reasonably matches real data with minor deviations.");
        } else if (score >= 0.60) {
            System.out.println("  [FAIR] Noticeable differences from real data; review distribution details.");
        } else {
            System.out.println("  [POOR] Significant deviations from real data; generator tuning recommended.");
        }
        System.out.println("  (Real data baseline = 1.0000)");
        System.out.println(new String(new char[70]).replace('\0', '='));
    }

    private static String grade(double score) {
        if (score >= 0.90) return "EXCELLENT";
        if (score >= 0.75) return "GOOD";
        if (score >= 0.60) return "FAIR";
        return "POOR";
    }

    // ==================== 数据类 ====================

    static class DimensionScore implements Serializable {
        final String name;
        final double score;
        final double weight;

        DimensionScore(String name, double score, double weight) {
            this.name = name;
            this.score = score;
            this.weight = weight;
        }
    }

    static class CategoricalRow implements Serializable {
        final String dimension, category;
        final double realPct, genPct;

        CategoricalRow(String dimension, String category, double realPct, double genPct) {
            this.dimension = dimension;
            this.category = category;
            this.realPct = realPct;
            this.genPct = genPct;
        }
    }

    static class NumericalRow implements Serializable {
        final String dimension, statistic;
        final double realValue, genValue;

        NumericalRow(String dimension, String statistic, double realValue, double genValue) {
            this.dimension = dimension;
            this.statistic = statistic;
            this.realValue = realValue;
            this.genValue = genValue;
        }
    }

    static class CorrelationRow implements Serializable {
        final String element;
        final double realVal, genVal;

        CorrelationRow(String element, double realVal, double genVal) {
            this.element = element;
            this.realVal = realVal;
            this.genVal = genVal;
        }
    }
}
