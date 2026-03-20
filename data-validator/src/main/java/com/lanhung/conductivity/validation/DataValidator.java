package com.lanhung.conductivity.validation;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用 Spark SQL 进行生成后验证。
 * 检查硬约束（必须 100% 通过）并打印统计分布。
 */
public class DataValidator implements Serializable {

    // 与 data-generator-base-rule 的 sample_id 编码保持一致:
    // sample_id = 10000001 + recipe_group_id * 8 + temperature_index
    private static final long SYNTHETIC_BASE_SAMPLE_ID = 10000001L;
    private static final long SYNTHETIC_SAMPLE_ID_BLOCK_SIZE = 8L;

    private final SparkSession spark;
    private final List<HardConstraintResult> results = new ArrayList<>();

    public DataValidator(SparkSession spark) {
        this.spark = spark;
    }

    public List<HardConstraintResult> validate(Dataset<Row> samples, Dataset<Row> dopants,
                         Dataset<Row> sintering, Dataset<Row> phases) {
        return validate(samples, dopants, sintering, phases, null, null, null);
    }

    public List<HardConstraintResult> validate(Dataset<Row> samples, Dataset<Row> dopants,
                         Dataset<Row> sintering, Dataset<Row> phases,
                         Dataset<Row> synthesisMethodDict, Dataset<Row> processingRouteDict) {
        return validate(samples, dopants, sintering, phases, synthesisMethodDict, processingRouteDict, null);
    }

    public List<HardConstraintResult> validate(Dataset<Row> samples, Dataset<Row> dopants,
                         Dataset<Row> sintering, Dataset<Row> phases,
                         Dataset<Row> synthesisMethodDict, Dataset<Row> processingRouteDict,
                         Dataset<Row> crystalStructureDict) {

        results.clear();

        samples.createOrReplaceTempView("material_samples");
        dopants.createOrReplaceTempView("sample_dopants");
        sintering.createOrReplaceTempView("sintering_steps");
        phases.createOrReplaceTempView("sample_crystal_phases");
        if (synthesisMethodDict != null) {
            synthesisMethodDict.createOrReplaceTempView("synthesis_method_dict");
        }
        if (processingRouteDict != null) {
            processingRouteDict.createOrReplaceTempView("processing_route_dict");
        }
        if (crystalStructureDict != null) {
            crystalStructureDict.createOrReplaceTempView("crystal_structure_dict");
        }

        long totalSamples = spark.sql("SELECT COUNT(*) FROM material_samples").collectAsList().get(0).getLong(0);
        long totalDopants = spark.sql("SELECT COUNT(*) FROM sample_dopants").collectAsList().get(0).getLong(0);
        long totalDopantSamples = spark.sql("SELECT COUNT(DISTINCT sample_id) FROM sample_dopants").collectAsList().get(0).getLong(0);
        long totalSinteringSamples = spark.sql("SELECT COUNT(DISTINCT sample_id) FROM sintering_steps").collectAsList().get(0).getLong(0);
        long totalPhaseSamples = spark.sql("SELECT COUNT(DISTINCT sample_id) FROM sample_crystal_phases").collectAsList().get(0).getLong(0);

        System.out.println(new String(new char[70]).replace('\0', '='));
        System.out.println("DATA VALIDATION REPORT (per-record)");
        System.out.println(new String(new char[70]).replace('\0', '='));

        System.out.println("\n--- Hard Constraints (per-record validation) ---\n");

        checkHard("HC-1", "All conductivity > 0", totalSamples,
                "SELECT COUNT(*) AS violations FROM material_samples WHERE conductivity <= 0");

        checkHard("HC-2a", "All molar_fraction > 0", totalDopants,
                "SELECT COUNT(*) AS violations FROM sample_dopants WHERE dopant_molar_fraction <= 0");

        checkHard("HC-2b", "Element-specific solubility limits", totalDopants,
                "SELECT COUNT(*) AS violations FROM sample_dopants WHERE"
                + " (dopant_element = 'Sc' AND dopant_molar_fraction > 0.12)"
                + " OR (dopant_element = 'Ce' AND dopant_molar_fraction > 0.18)"
                + " OR (dopant_element = 'Y' AND dopant_molar_fraction > 0.25)"
                + " OR (dopant_element = 'Ca' AND dopant_molar_fraction > 0.20)");

        checkHard("HC-3", "Total dopant fraction <= 0.30 per sample", totalSamples,
                "SELECT COUNT(*) AS violations FROM ("
                + "  SELECT sample_id, SUM(dopant_molar_fraction) AS total_frac"
                + "  FROM sample_dopants GROUP BY sample_id"
                + "  HAVING total_frac > 0.3005"
                + ")");

        checkHard("HC-4", "At least one +2 or +3 valence dopant per sample", totalSamples,
                "SELECT COUNT(*) AS violations FROM ("
                + "  SELECT sample_id FROM sample_dopants"
                + "  GROUP BY sample_id"
                + "  HAVING SUM(CASE WHEN dopant_valence IN (2, 3) THEN 1 ELSE 0 END) = 0"
                + ")");

        checkHard("HC-5", "Exactly one major phase per sample", totalSamples,
                "SELECT COUNT(*) AS violations FROM ("
                + "  SELECT sample_id, SUM(CAST(is_major_phase AS INT)) AS major_count"
                + "  FROM sample_crystal_phases GROUP BY sample_id"
                + "  HAVING major_count != 1"
                + ")");

        checkHard("HC-6a", "All dopant sample_ids exist in material_samples", totalDopantSamples,
                "SELECT COUNT(*) AS violations FROM ("
                + "  SELECT DISTINCT d.sample_id FROM sample_dopants d"
                + "  LEFT JOIN material_samples m ON d.sample_id = m.sample_id"
                + "  WHERE m.sample_id IS NULL"
                + ")");

        checkHard("HC-6b", "All sintering sample_ids exist in material_samples", totalSinteringSamples,
                "SELECT COUNT(*) AS violations FROM ("
                + "  SELECT DISTINCT s.sample_id FROM sintering_steps s"
                + "  LEFT JOIN material_samples m ON s.sample_id = m.sample_id"
                + "  WHERE m.sample_id IS NULL"
                + ")");

        checkHard("HC-6c", "All phase sample_ids exist in material_samples", totalPhaseSamples,
                "SELECT COUNT(*) AS violations FROM ("
                + "  SELECT DISTINCT p.sample_id FROM sample_crystal_phases p"
                + "  LEFT JOIN material_samples m ON p.sample_id = m.sample_id"
                + "  WHERE m.sample_id IS NULL"
                + ")");

        boolean hasRecipeGroupId = hasColumn(samples, "recipe_group_id");
        boolean deriveRecipeGroupFromSampleId = !hasRecipeGroupId
                && canDeriveRecipeGroupFromSampleId(samples, "material_samples");
        String hc7RecipeGroupExpr = buildHc7RecipeGroupExpr(
                hasRecipeGroupId, deriveRecipeGroupFromSampleId, "m");

        if (hc7RecipeGroupExpr != null) {
            checkHard("HC-7", "Monotonicity within recipe group (temp up -> cond up)", totalSamples,
                    buildHc7ViolationCountSql("material_samples", "sample_dopants", hc7RecipeGroupExpr));
        } else {
            checkHard("HC-7", "Monotonicity within derived recipe group (temp up -> cond up)", totalSamples,
                    buildHc7ViolationCountSql("material_samples", "sample_dopants", null));
        }

        checkHard("HC-8", "Phase-dopant coupling constraints", totalSamples,
                "SELECT COUNT(*) AS violations FROM ("
                + "  SELECT sd.sample_id FROM ("
                + "    SELECT sample_id, SUM(dopant_molar_fraction) AS total_frac"
                + "    FROM sample_dopants GROUP BY sample_id"
                + "  ) sd"
                + "  JOIN ("
                + "    SELECT cp.sample_id, COUNT(*) AS phase_count,"
                + "      MAX(CASE WHEN cs.code = 'c' AND CAST(cp.is_major_phase AS INT) = 1 THEN 1 ELSE 0 END) AS cubic_major,"
                + "      MAX(CASE WHEN cs.code = 'm' AND CAST(cp.is_major_phase AS INT) = 1 THEN 1 ELSE 0 END) AS mono_major"
                + "    FROM sample_crystal_phases cp"
                + "    JOIN crystal_structure_dict cs ON cp.crystal_id = cs.id"
                + "    GROUP BY cp.sample_id"
                + "  ) ph ON sd.sample_id = ph.sample_id"
                + "  WHERE (sd.total_frac < 0.05 AND ph.phase_count = 1 AND ph.cubic_major = 1)"
                + "     OR (sd.total_frac > 0.12 AND ph.phase_count = 1 AND ph.mono_major = 1)"
                + ")");

        checkHard("HC-9", "Conductivity within bounds [1e-8, 1.0]", totalSamples,
                "SELECT COUNT(*) AS violations FROM material_samples WHERE conductivity < 1e-8 OR conductivity > 1.0");

        checkHard("HC-10", "Operating temperature within [300, 1400]", totalSamples,
                "SELECT COUNT(*) AS violations FROM material_samples WHERE operating_temperature < 300 OR operating_temperature > 1400");

        System.out.println("\n--- Statistical Distributions ---\n");

        showStat("Total sample count:",
                "SELECT COUNT(*) AS total_samples FROM material_samples");

        showStat("Total dopant rows:",
                "SELECT COUNT(*) AS total_dopant_rows FROM sample_dopants");

        showStat("Dopant element frequency:",
                "SELECT dopant_element, COUNT(*) AS cnt,"
                + "  ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) AS pct"
                + " FROM sample_dopants GROUP BY dopant_element ORDER BY cnt DESC",
                20);

        showStat("Dopant count per sample:",
                "SELECT num_dopants, COUNT(*) AS cnt FROM ("
                + "  SELECT sample_id, COUNT(*) AS num_dopants"
                + "  FROM sample_dopants GROUP BY sample_id"
                + ") GROUP BY num_dopants ORDER BY num_dopants");

        showStat("Synthesis method distribution:",
                "SELECT COALESCE(sm.name, CAST(ms.synthesis_method_id AS STRING)) AS synthesis_method,"
                + "  COUNT(*) AS cnt,"
                + "  ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) AS pct"
                + " FROM material_samples ms"
                + " LEFT JOIN synthesis_method_dict sm ON ms.synthesis_method_id = sm.id"
                + " GROUP BY COALESCE(sm.name, CAST(ms.synthesis_method_id AS STRING))"
                + " ORDER BY cnt DESC");

        showStat("Processing route distribution:",
                "SELECT COALESCE(pr.name, CAST(ms.processing_route_id AS STRING)) AS processing_route,"
                + "  COUNT(*) AS cnt,"
                + "  ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) AS pct"
                + " FROM material_samples ms"
                + " LEFT JOIN processing_route_dict pr ON ms.processing_route_id = pr.id"
                + " GROUP BY COALESCE(pr.name, CAST(ms.processing_route_id AS STRING))"
                + " ORDER BY cnt DESC");

        showStat("Conductivity statistics:",
                "SELECT"
                + "  MIN(conductivity) AS min_cond, MAX(conductivity) AS max_cond,"
                + "  AVG(conductivity) AS avg_cond,"
                + "  PERCENTILE_APPROX(conductivity, 0.5) AS median_cond,"
                + "  AVG(LOG10(conductivity)) AS avg_log10_cond"
                + " FROM material_samples");

        showStat("Operating temperature statistics:",
                "SELECT"
                + "  MIN(operating_temperature) AS min_temp, MAX(operating_temperature) AS max_temp,"
                + "  AVG(operating_temperature) AS avg_temp,"
                + "  PERCENTILE_APPROX(operating_temperature, 0.5) AS median_temp"
                + " FROM material_samples");

        showStat("Average conductivity by primary dopant at 800C:",
                "SELECT d.dopant_element, AVG(m.conductivity) AS avg_cond,"
                + "  AVG(LOG10(m.conductivity)) AS avg_log10_cond, COUNT(*) AS cnt"
                + " FROM material_samples m"
                + " JOIN ("
                + "  SELECT sample_id, dopant_element,"
                + "    ROW_NUMBER() OVER (PARTITION BY sample_id ORDER BY dopant_molar_fraction DESC) AS rn"
                + "  FROM sample_dopants"
                + " ) d ON m.sample_id = d.sample_id AND d.rn = 1"
                + " WHERE m.operating_temperature BETWEEN 750 AND 850"
                + " GROUP BY d.dopant_element ORDER BY avg_cond DESC",
                20);

        showStat("Crystal phase distribution:",
                "SELECT crystal_id, is_major_phase, COUNT(*) AS cnt"
                + " FROM sample_crystal_phases"
                + " GROUP BY crystal_id, is_major_phase"
                + " ORDER BY crystal_id, is_major_phase DESC");

        // 清理临时视图
        for (String view : new String[]{"material_samples", "sample_dopants",
                "sintering_steps", "sample_crystal_phases",
                "synthesis_method_dict", "processing_route_dict",
                "crystal_structure_dict"}) {
            spark.catalog().dropTempView(view);
        }

        System.out.println(new String(new char[70]).replace('\0', '='));
        System.out.println("VALIDATION COMPLETE");
        System.out.println(new String(new char[70]).replace('\0', '='));

        return new ArrayList<>(results);
    }

    public long getTotalSamples() {
        return results.isEmpty() ? 0 : results.get(0).totalChecked;
    }

    private void checkHard(String code, String name, long totalChecked, String sql) {
        try {
            long violations = spark.sql(sql).collectAsList().get(0).getLong(0);
            long passed = totalChecked - violations;
            double passRate = totalChecked > 0 ? passed * 100.0 / totalChecked : 100.0;
            String status;
            if (violations == 0) {
                status = String.format("PASS  %d/%d (%.2f%%)", passed, totalChecked, passRate);
            } else {
                status = String.format("%d/%d (%.2f%%) [%d violations]", passed, totalChecked, passRate, violations);
            }
            System.out.printf("  %-50s %s%n", code + ": " + name, status);

            results.add(new HardConstraintResult(code, name,
                    (int) totalChecked, (int) violations, passRate));
        } catch (Exception e) {
            System.out.printf("  %-50s %s%n", code + ": " + name, "ERROR (" + e.getMessage() + ")");
            e.printStackTrace();
            results.add(new HardConstraintResult(code, name,
                    (int) totalChecked, -1, 0.0));
        }
    }

    private static boolean hasColumn(Dataset<Row> df, String colName) {
        for (String name : df.columns()) {
            if (name.equalsIgnoreCase(colName)) return true;
        }
        return false;
    }

    private boolean canDeriveRecipeGroupFromSampleId(Dataset<Row> samples, String materialSamplesView) {
        if (!hasColumn(samples, "sample_id") || hasColumn(samples, "recipe_group_id")) {
            return false;
        }

        try {
            long totalCount = spark.sql("SELECT COUNT(*) AS total_count FROM " + materialSamplesView)
                    .collectAsList().get(0).getLong(0);
            if (totalCount == 0) {
                return false;
            }

            StringBuilder sql = new StringBuilder()
                    .append("SELECT COUNT(*) AS matched_count FROM ")
                    .append(materialSamplesView)
                    .append(" WHERE sample_id >= ")
                    .append(SYNTHETIC_BASE_SAMPLE_ID);
            if (hasColumn(samples, "reference")) {
                sql.append(" AND reference = 'RULE_BASED_SYNTHETIC'");
            }

            long matchedCount = spark.sql(sql.toString())
                    .collectAsList().get(0).getLong(0);
            return matchedCount == totalCount;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildHc7RecipeGroupExpr(boolean hasRecipeGroupId,
                                           boolean deriveRecipeGroupFromSampleId,
                                           String tableAlias) {
        if (hasRecipeGroupId) {
            return tableAlias + ".recipe_group_id";
        }
        if (deriveRecipeGroupFromSampleId) {
            return "CAST(FLOOR((CAST(" + tableAlias + ".sample_id AS DOUBLE) - "
                    + SYNTHETIC_BASE_SAMPLE_ID + ") / "
                    + SYNTHETIC_SAMPLE_ID_BLOCK_SIZE + ") AS BIGINT)";
        }
        return null;
    }

    private String buildHc7ViolationCountSql(String materialSamplesView,
                                             String dopantsView,
                                             String recipeGroupExpr) {
        return "SELECT COUNT(*) AS violations FROM ("
                + buildHc7ViolationIdsSql(materialSamplesView, dopantsView, recipeGroupExpr)
                + ")";
    }

    private String buildHc7ViolationIdsSql(String materialSamplesView,
                                           String dopantsView,
                                           String recipeGroupExpr) {
        if (recipeGroupExpr != null) {
            return "SELECT sample_id FROM ("
                    + "  SELECT m.sample_id, m.conductivity,"
                    + "    LAG(m.conductivity) OVER ("
                    + "      PARTITION BY " + recipeGroupExpr
                    + "      ORDER BY m.operating_temperature"
                    + "    ) AS prev_conductivity"
                    + "  FROM " + materialSamplesView + " m"
                    + ") WHERE prev_conductivity IS NOT NULL AND conductivity <= prev_conductivity";
        }

        return "SELECT sample_id FROM ("
                + "  SELECT m.sample_id, m.conductivity,"
                + "    LAG(m.conductivity) OVER ("
                + "      PARTITION BY m.synthesis_method_id, m.processing_route_id, d.dopant_key"
                + "      ORDER BY m.operating_temperature"
                + "    ) AS prev_conductivity"
                + "  FROM " + materialSamplesView + " m"
                + "  JOIN ("
                + "    SELECT sample_id,"
                + "      CONCAT_WS(',', SORT_ARRAY(COLLECT_LIST(CONCAT(dopant_element, ':', CAST(dopant_molar_fraction AS STRING))))) AS dopant_key"
                + "    FROM " + dopantsView + " GROUP BY sample_id"
                + "  ) d ON m.sample_id = d.sample_id"
                + ") WHERE prev_conductivity IS NOT NULL AND conductivity <= prev_conductivity";
    }

    private void showStat(String label, String sql) {
        showStat(label, sql, 20);
    }

    private void showStat(String label, String sql, int numRows) {
        try {
            System.out.println(label);
            spark.sql(sql).show(numRows);
        } catch (Exception e) {
            System.out.println("  (skipped: " + e.getMessage() + ")");
        }
    }

    /**
     * 过滤出所有满足硬约束的合规数据，写入 HDFS。
     */
    public long filterCompliant(Dataset<Row> samples, Dataset<Row> dopants,
                                Dataset<Row> sintering, Dataset<Row> phases,
                                Dataset<Row> synthesisMethodDict, Dataset<Row> processingRouteDict,
                                Dataset<Row> crystalStructureDict,
                                String outputPath) {
        samples.createOrReplaceTempView("material_samples");
        dopants.createOrReplaceTempView("sample_dopants");
        sintering.createOrReplaceTempView("sintering_steps");
        phases.createOrReplaceTempView("sample_crystal_phases");
        if (crystalStructureDict != null) {
            crystalStructureDict.createOrReplaceTempView("crystal_structure_dict");
        }

        boolean hasRecipeGroupId = hasColumn(samples, "recipe_group_id");
        boolean deriveRecipeGroupFromSampleId = !hasRecipeGroupId
                && canDeriveRecipeGroupFromSampleId(samples, "material_samples");
        String hc7RecipeGroupExpr = buildHc7RecipeGroupExpr(
                hasRecipeGroupId, deriveRecipeGroupFromSampleId, "m");

        // 收集所有违规 sample_id
        List<String> vqs = new ArrayList<>();

        // HC-1
        vqs.add("SELECT sample_id FROM material_samples WHERE conductivity <= 0");
        // HC-2a
        vqs.add("SELECT DISTINCT sample_id FROM sample_dopants WHERE dopant_molar_fraction <= 0");
        // HC-2b
        vqs.add("SELECT DISTINCT sample_id FROM sample_dopants WHERE"
                + " (dopant_element = 'Sc' AND dopant_molar_fraction > 0.12)"
                + " OR (dopant_element = 'Ce' AND dopant_molar_fraction > 0.18)"
                + " OR (dopant_element = 'Y' AND dopant_molar_fraction > 0.25)"
                + " OR (dopant_element = 'Ca' AND dopant_molar_fraction > 0.20)");
        // HC-3
        vqs.add("SELECT sample_id FROM sample_dopants GROUP BY sample_id"
                + " HAVING SUM(dopant_molar_fraction) > 0.3005");
        // HC-4
        vqs.add("SELECT sample_id FROM sample_dopants GROUP BY sample_id"
                + " HAVING SUM(CASE WHEN dopant_valence IN (2, 3) THEN 1 ELSE 0 END) = 0");
        // HC-5
        vqs.add("SELECT sample_id FROM sample_crystal_phases GROUP BY sample_id"
                + " HAVING SUM(CAST(is_major_phase AS INT)) != 1");
        // HC-7
        vqs.add(buildHc7ViolationIdsSql("material_samples", "sample_dopants", hc7RecipeGroupExpr));
        // HC-8 (需要 crystal_structure_dict)
        if (crystalStructureDict != null) {
            vqs.add("SELECT sd.sample_id FROM ("
                    + "  SELECT sample_id, SUM(dopant_molar_fraction) AS total_frac"
                    + "  FROM sample_dopants GROUP BY sample_id"
                    + ") sd JOIN ("
                    + "  SELECT cp.sample_id, COUNT(*) AS phase_count,"
                    + "    MAX(CASE WHEN cs.code = 'c' AND CAST(cp.is_major_phase AS INT) = 1 THEN 1 ELSE 0 END) AS cubic_major,"
                    + "    MAX(CASE WHEN cs.code = 'm' AND CAST(cp.is_major_phase AS INT) = 1 THEN 1 ELSE 0 END) AS mono_major"
                    + "  FROM sample_crystal_phases cp"
                    + "  JOIN crystal_structure_dict cs ON cp.crystal_id = cs.id"
                    + "  GROUP BY cp.sample_id"
                    + ") ph ON sd.sample_id = ph.sample_id"
                    + " WHERE (sd.total_frac < 0.05 AND ph.phase_count = 1 AND ph.cubic_major = 1)"
                    + "    OR (sd.total_frac > 0.12 AND ph.phase_count = 1 AND ph.mono_major = 1)");
        }
        // HC-9
        vqs.add("SELECT sample_id FROM material_samples WHERE conductivity < 1e-8 OR conductivity > 1.0");
        // HC-10
        vqs.add("SELECT sample_id FROM material_samples WHERE operating_temperature < 300 OR operating_temperature > 1400");

        String unionSql = String.join(" UNION ", vqs);
        Dataset<Row> violatingIds = spark.sql(unionSql).distinct()
                .persist(StorageLevel.MEMORY_AND_DISK());
        // 触发 materialization，确保后续所有操作使用同一份违规集合
        long initialViolatingCount = violatingIds.count();
        violatingIds.createOrReplaceTempView("_violating_ids");

        long totalCount = samples.count();
        System.out.printf("Compliant filter: %d/%d samples violate constraints in the first pass%n",
                initialViolatingCount, totalCount);

        // LEFT ANTI JOIN 过滤 — 持久化 compliantIds 保证四张表一致
        Dataset<Row> compliantSamples = spark.sql(
                "SELECT m.* FROM material_samples m LEFT ANTI JOIN _violating_ids v ON m.sample_id = v.sample_id")
                .persist(StorageLevel.MEMORY_AND_DISK());
        compliantSamples.count();

        long extraHc7Removed = 0L;
        int hc7ConvergencePasses = 0;
        while (true) {
            compliantSamples.createOrReplaceTempView("_current_compliant_samples");
            Dataset<Row> hc7ViolatingIds = spark.sql(
                    buildHc7ViolationIdsSql("_current_compliant_samples", "sample_dopants", hc7RecipeGroupExpr))
                    .distinct()
                    .persist(StorageLevel.MEMORY_AND_DISK());
            long hc7ViolatingCount = hc7ViolatingIds.count();

            if (hc7ViolatingCount == 0) {
                hc7ViolatingIds.unpersist();
                spark.catalog().dropTempView("_current_compliant_samples");
                break;
            }

            hc7ConvergencePasses++;
            extraHc7Removed += hc7ViolatingCount;
            System.out.printf("HC-7 convergence pass %d removed %d additional samples%n",
                    hc7ConvergencePasses, hc7ViolatingCount);

            hc7ViolatingIds.createOrReplaceTempView("_hc7_violating_ids");
            Dataset<Row> nextCompliantSamples = spark.sql(
                    "SELECT m.* FROM _current_compliant_samples m"
                            + " LEFT ANTI JOIN _hc7_violating_ids v ON m.sample_id = v.sample_id")
                    .persist(StorageLevel.MEMORY_AND_DISK());
            nextCompliantSamples.count();

            compliantSamples.unpersist();
            compliantSamples = nextCompliantSamples;

            hc7ViolatingIds.unpersist();
            spark.catalog().dropTempView("_hc7_violating_ids");
            spark.catalog().dropTempView("_current_compliant_samples");
        }

        Dataset<Row> compliantIds = compliantSamples.select("sample_id")
                .persist(StorageLevel.MEMORY_AND_DISK());
        compliantIds.count(); // 触发 materialization

        Dataset<Row> compliantDopants = dopants.join(compliantIds, "sample_id");
        Dataset<Row> compliantSintering = sintering.join(compliantIds, "sample_id");
        Dataset<Row> compliantPhases = phases.join(compliantIds, "sample_id");

        String path = outputPath.endsWith("/") ? outputPath.substring(0, outputPath.length() - 1) : outputPath;
        compliantSamples.write().mode("overwrite").parquet(path + "/material_samples");
        compliantDopants.write().mode("overwrite").parquet(path + "/sample_dopants");
        compliantSintering.write().mode("overwrite").parquet(path + "/sintering_steps");
        compliantPhases.write().mode("overwrite").parquet(path + "/sample_crystal_phases");

        // 字典表原样拷贝
        if (synthesisMethodDict != null) {
            synthesisMethodDict.write().mode("overwrite").parquet(path + "/synthesis_method_dict");
        }
        if (processingRouteDict != null) {
            processingRouteDict.write().mode("overwrite").parquet(path + "/processing_route_dict");
        }
        if (crystalStructureDict != null) {
            crystalStructureDict.write().mode("overwrite").parquet(path + "/crystal_structure_dict");
        }

        long totalRemoved = initialViolatingCount + extraHc7Removed;
        long compliantCount = totalCount - totalRemoved;
        if (extraHc7Removed > 0) {
            System.out.printf("HC-7 convergence removed %d extra samples across %d passes%n",
                    extraHc7Removed, hc7ConvergencePasses);
        }
        System.out.printf("Compliant data saved: %d samples -> %s%n", compliantCount, path);

        // 释放缓存 & 清理临时视图
        compliantSamples.unpersist();
        compliantIds.unpersist();
        violatingIds.unpersist();
        spark.catalog().dropTempView("_violating_ids");
        for (String view : new String[]{"material_samples", "sample_dopants",
                "sintering_steps", "sample_crystal_phases", "crystal_structure_dict"}) {
            spark.catalog().dropTempView(view);
        }

        return compliantCount;
    }

    // ==================== 数据类 ====================

    public static class HardConstraintResult implements Serializable {
        final String constraintCode;
        final String constraintName;
        final boolean passed;
        final Integer totalChecked;
        final int violationCount;
        final double passRate;

        HardConstraintResult(String constraintCode, String constraintName,
                             Integer totalChecked, int violationCount, double passRate) {
            this.constraintCode = constraintCode;
            this.constraintName = constraintName;
            this.totalChecked = totalChecked;
            this.violationCount = violationCount;
            this.passRate = passRate;
            this.passed = violationCount == 0;
        }
    }
}
