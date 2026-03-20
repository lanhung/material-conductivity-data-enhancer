package com.lanhung.conductivity.validator;

import com.lanhung.conductivity.validation.DataValidator;
import com.lanhung.conductivity.validation.FidelityValidator;
import com.lanhung.conductivity.validation.HdfsResultSink;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.util.List;

/**
 * Data validation entry point.
 *
 * Usage:
 *   spark-submit --class com.lanhung.conductivity.validator.DataValidatorApp \
 *     data-validator.jar \
 *     --database <hiveDatabase> \
 *     [--validate] [--fidelity] \
 *     --real-database <hiveDatabase> \
 *     --output-path <hdfsPath> \
 *     [--compliant-output-path <hdfsPath>] \
 *     [--sample-ratio <ratio>]
 *
 * Real data source:
 *   --real-database : Hive database containing real data (e.g. ods_zirconia_conductivity_v2)
 *
 * HDFS output:
 *   --output-path   : HDFS base path for Parquet output (default: /user/hive/warehouse/conductivity_validation.db)
 *
 * Sampling:
 *   --sample-ratio  : Fraction of generated data to sample (0, 1.0], default 1.0 (no sampling)
 */
public class DataValidatorApp {

    public static void main(String[] args) {
        String database = "default";
        boolean runValidation = false;
        boolean runFidelity = false;
        String realDatabase = null;
        String outputPath = null;
        String compliantOutputPath = null;
        double sampleRatio = 1.0;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--database":
                    if (++i >= args.length) { System.err.println("Missing value for --database"); return; }
                    database = args[i];
                    break;
                case "--validate":
                    runValidation = true;
                    break;
                case "--fidelity":
                    runFidelity = true;
                    break;
                case "--real-database":
                    if (++i >= args.length) { System.err.println("Missing value for --real-database"); return; }
                    realDatabase = args[i];
                    break;
                case "--output-path":
                    if (++i >= args.length) { System.err.println("Missing value for --output-path"); return; }
                    outputPath = args[i];
                    break;
                case "--compliant-output-path":
                    if (++i >= args.length) { System.err.println("Missing value for --compliant-output-path"); return; }
                    compliantOutputPath = args[i];
                    break;
                case "--sample-ratio":
                    if (++i >= args.length) { System.err.println("Missing value for --sample-ratio"); return; }
                    sampleRatio = Double.parseDouble(args[i]);
                    if (sampleRatio <= 0 || sampleRatio > 1.0) {
                        System.err.println("--sample-ratio must be in (0, 1.0]");
                        return;
                    }
                    break;
            }
        }

        if (!runValidation && !runFidelity) {
            runValidation = true;
        }

        if (outputPath == null) {
            System.err.println("--output-path is required");
            return;
        }

        SparkSession spark = SparkSession.builder()
                .appName("ZrO2 Data Validator")
                .enableHiveSupport()
                .getOrCreate();

        HdfsResultSink hdfsSink = new HdfsResultSink(spark, outputPath);
        System.out.println("HDFS output path: " + outputPath);

        // Load generated data tables
        int totalCount = (int) spark.table(database + ".material_samples").count();
        Dataset<Row> genSamples = spark.table(database + ".material_samples");
        Dataset<Row> genDopants = spark.table(database + ".sample_dopants");
        Dataset<Row> genSintering = spark.table(database + ".sintering_steps");
        Dataset<Row> genPhases = spark.table(database + ".sample_crystal_phases");

        // Sampling
        if (sampleRatio < 1.0) {
            System.out.printf("Sampling %.0f%% of generated data...%n", sampleRatio * 100);
            genSamples = genSamples.sample(sampleRatio);
            Dataset<Row> sampledIds = genSamples.select("sample_id");
            genDopants = genDopants.join(sampledIds, "sample_id");
            genSintering = genSintering.join(sampledIds, "sample_id");
            genPhases = genPhases.join(sampledIds, "sample_id");
        }
        int sampledCount = (int) genSamples.count();
        System.out.printf("Total samples: %d, Sampled: %d (ratio: %.2f)%n", totalCount, sampledCount, sampleRatio);

        // Dict tables (may not exist, load if available)
        Dataset<Row> synthesisMethodDict = tryLoadTable(spark, database + ".synthesis_method_dict");
        Dataset<Row> processingRouteDict = tryLoadTable(spark, database + ".processing_route_dict");
        Dataset<Row> crystalStructureDict = tryLoadTable(spark, database + ".crystal_structure_dict");

        if (runValidation) {
            try {
                System.out.println("Running data validation...");
                DataValidator validator = new DataValidator(spark);
                List<DataValidator.HardConstraintResult> hcResults = validator.validate(
                        genSamples, genDopants, genSintering, genPhases,
                        synthesisMethodDict, processingRouteDict, crystalStructureDict);

                hdfsSink.saveHardConstraintResults(database, totalCount, sampledCount, sampleRatio, hcResults);
            } catch (Exception e) {
                System.err.println("Failed to run/save hard constraint validation: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (compliantOutputPath != null) {
            try {
                System.out.println("Filtering compliant data...");
                DataValidator filter = new DataValidator(spark);
                long compliantCount = filter.filterCompliant(
                        genSamples, genDopants, genSintering, genPhases,
                        synthesisMethodDict, processingRouteDict, crystalStructureDict,
                        compliantOutputPath);
                System.out.printf("Compliant samples: %d / %d%n", compliantCount, sampledCount);
            } catch (Exception e) {
                System.err.println("Failed to filter compliant data: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (runFidelity) {
            if (realDatabase == null) {
                System.err.println("Fidelity validation requires --real-database");
                spark.stop();
                return;
            }
            try {
                System.out.println("Running fidelity validation against real data...");
                System.out.println("Real data source: Hive database [" + realDatabase + "]");
                FidelityValidator fidelityValidator = new FidelityValidator(spark, realDatabase);
                double overallScore = fidelityValidator.validate(
                        genSamples, genDopants, genSintering, genPhases,
                        synthesisMethodDict, processingRouteDict);

                hdfsSink.saveFidelityResults(database, totalCount, sampledCount, sampleRatio,
                        overallScore, fidelityValidator);
            } catch (Exception e) {
                System.err.println("Failed to run/save fidelity validation: " + e.getMessage());
                e.printStackTrace();
            }
        }

        spark.stop();
        System.out.println("Done.");
    }

    private static Dataset<Row> tryLoadTable(SparkSession spark, String tableName) {
        try {
            return spark.table(tableName);
        } catch (Exception e) {
            return null;
        }
    }
}
