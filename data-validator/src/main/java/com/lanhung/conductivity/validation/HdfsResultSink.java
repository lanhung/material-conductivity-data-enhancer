package com.lanhung.conductivity.validation;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Write validation results to HDFS as Parquet files (append mode).
 */
public class HdfsResultSink {

    private final SparkSession spark;
    private final String basePath;

    public HdfsResultSink(SparkSession spark, String basePath) {
        this.spark = spark;
        this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
    }

    /**
     * Save hard constraint validation results.
     */
    public void saveHardConstraintResults(String databaseName, int totalCount, int sampledCount,
                                          double sampleRatio,
                                          List<DataValidator.HardConstraintResult> results) {
        // 逐条验证：基于各约束通过率的最小值判断整体是否全部通过
        boolean allPassed = results.stream().allMatch(r -> r.passRate >= 100.0);
        double minPassRate = results.stream()
                .mapToDouble(r -> r.passRate)
                .min().orElse(100.0);
        long runId = System.currentTimeMillis();
        String runAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // validation_run
        StructType runSchema = new StructType()
                .add("run_id", DataTypes.LongType)
                .add("run_type", DataTypes.StringType)
                .add("database_name", DataTypes.StringType)
                .add("total_count", DataTypes.IntegerType)
                .add("sampled_count", DataTypes.IntegerType)
                .add("sample_ratio", DataTypes.DoubleType)
                .add("passed", DataTypes.BooleanType)
                .add("overall_score", DataTypes.DoubleType)
                .add("overall_grade", DataTypes.StringType)
                .add("run_at", DataTypes.StringType);

        Row runRow = RowFactory.create(runId, "HARD_CONSTRAINT", databaseName,
                totalCount, sampledCount, sampleRatio,
                Boolean.valueOf(allPassed), Double.valueOf(minPassRate), null, runAt);
        spark.createDataFrame(Collections.singletonList(runRow), runSchema)
                .write().mode("append").parquet(basePath + "/validation_run");

        // hard_constraint_result
        StructType schema = new StructType()
                .add("run_id", DataTypes.LongType)
                .add("constraint_code", DataTypes.StringType)
                .add("constraint_name", DataTypes.StringType)
                .add("passed", DataTypes.BooleanType)
                .add("total_checked", DataTypes.IntegerType)
                .add("violation_count", DataTypes.IntegerType)
                .add("pass_rate", DataTypes.DoubleType);

        List<Row> rows = results.stream().map(r -> RowFactory.create(
                runId, r.constraintCode, r.constraintName,
                Boolean.valueOf(r.passed), r.totalChecked,
                Integer.valueOf(r.violationCount), Double.valueOf(r.passRate))
        ).collect(Collectors.toList());

        spark.createDataFrame(rows, schema).write().mode("append").parquet(basePath + "/hard_constraint_result");
        System.out.println("Hard constraint results saved to HDFS (run_id=" + runId + ")");
    }

    /**
     * Save fidelity validation results. Each sub-table is written independently.
     */
    public void saveFidelityResults(String databaseName, int totalCount, int sampledCount,
                                    double sampleRatio, double overallScore,
                                    FidelityValidator fv) {
        String grade = grade(overallScore);
        long runId = System.currentTimeMillis();
        String runAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // validation_run
        StructType runSchema = new StructType()
                .add("run_id", DataTypes.LongType)
                .add("run_type", DataTypes.StringType)
                .add("database_name", DataTypes.StringType)
                .add("total_count", DataTypes.IntegerType)
                .add("sampled_count", DataTypes.IntegerType)
                .add("sample_ratio", DataTypes.DoubleType)
                .add("passed", DataTypes.BooleanType)
                .add("overall_score", DataTypes.DoubleType)
                .add("overall_grade", DataTypes.StringType)
                .add("run_at", DataTypes.StringType);

        Row runRow = RowFactory.create(runId, "FIDELITY", databaseName,
                totalCount, sampledCount, sampleRatio,
                null, Double.valueOf(overallScore), grade, runAt);
        spark.createDataFrame(Collections.singletonList(runRow), runSchema)
                .write().mode("append").parquet(basePath + "/validation_run");

        // fidelity_summary
        try {
            StructType summarySchema = new StructType()
                    .add("run_id", DataTypes.LongType)
                    .add("dimension", DataTypes.StringType)
                    .add("score", DataTypes.DoubleType)
                    .add("weight", DataTypes.DoubleType)
                    .add("weighted_score", DataTypes.DoubleType)
                    .add("grade", DataTypes.StringType);

            List<Row> summaryRows = fv.getScores().stream().map(ds ->
                    RowFactory.create(runId, ds.name, ds.score, ds.weight, ds.score * ds.weight, grade(ds.score))
            ).collect(Collectors.toList());
            spark.createDataFrame(summaryRows, summarySchema)
                    .write().mode("append").parquet(basePath + "/fidelity_summary");
            System.out.println("  fidelity_summary saved (" + summaryRows.size() + " rows)");
        } catch (Exception e) {
            System.err.println("  Failed to save fidelity_summary: " + e.getMessage());
            e.printStackTrace();
        }

        // fidelity_categorical
        try {
            StructType catSchema = new StructType()
                    .add("run_id", DataTypes.LongType)
                    .add("dimension", DataTypes.StringType)
                    .add("category", DataTypes.StringType)
                    .add("real_pct", DataTypes.DoubleType)
                    .add("gen_pct", DataTypes.DoubleType)
                    .add("diff_pct", DataTypes.DoubleType);

            List<Row> catRows = fv.getCategoricalRows().stream().map(r -> {
                String cat = r.category != null ? r.category : "(unknown)";
                return RowFactory.create(runId, r.dimension, cat, r.realPct, r.genPct, r.genPct - r.realPct);
            }).collect(Collectors.toList());
            spark.createDataFrame(catRows, catSchema)
                    .write().mode("append").parquet(basePath + "/fidelity_categorical");
            System.out.println("  fidelity_categorical saved (" + catRows.size() + " rows)");
        } catch (Exception e) {
            System.err.println("  Failed to save fidelity_categorical: " + e.getMessage());
            e.printStackTrace();
        }

        // fidelity_numerical
        try {
            StructType numSchema = new StructType()
                    .add("run_id", DataTypes.LongType)
                    .add("dimension", DataTypes.StringType)
                    .add("statistic", DataTypes.StringType)
                    .add("real_value", DataTypes.DoubleType)
                    .add("gen_value", DataTypes.DoubleType)
                    .add("diff", DataTypes.DoubleType);

            List<Row> numRows = fv.getNumericalRows().stream().map(r ->
                    RowFactory.create(runId, r.dimension, r.statistic, r.realValue, r.genValue, r.genValue - r.realValue)
            ).collect(Collectors.toList());
            spark.createDataFrame(numRows, numSchema)
                    .write().mode("append").parquet(basePath + "/fidelity_numerical");
            System.out.println("  fidelity_numerical saved (" + numRows.size() + " rows)");
        } catch (Exception e) {
            System.err.println("  Failed to save fidelity_numerical: " + e.getMessage());
            e.printStackTrace();
        }

        // fidelity_correlation
        try {
            StructType corrSchema = new StructType()
                    .add("run_id", DataTypes.LongType)
                    .add("element", DataTypes.StringType)
                    .add("real_log10_conductivity", DataTypes.DoubleType)
                    .add("gen_log10_conductivity", DataTypes.DoubleType)
                    .add("diff", DataTypes.DoubleType);

            List<Row> corrRows = fv.getCorrelationRows().stream().map(r ->
                    RowFactory.create(runId, r.element, r.realVal, r.genVal, r.genVal - r.realVal)
            ).collect(Collectors.toList());
            spark.createDataFrame(corrRows, corrSchema)
                    .write().mode("append").parquet(basePath + "/fidelity_correlation");
            System.out.println("  fidelity_correlation saved (" + corrRows.size() + " rows)");
        } catch (Exception e) {
            System.err.println("  Failed to save fidelity_correlation: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Fidelity results saved to HDFS (run_id=" + runId + ")");
    }

    private static String grade(double score) {
        if (score >= 0.90) return "EXCELLENT";
        if (score >= 0.75) return "GOOD";
        if (score >= 0.60) return "FAIR";
        return "POOR";
    }
}
