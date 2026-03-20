package com.lanhung.conductivity;

import com.lanhung.conductivity.config.AppConfig;
import com.lanhung.conductivity.config.PhysicsConstants;
import com.lanhung.conductivity.generator.RecipeGroupGenerator;
import com.lanhung.conductivity.model.*;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Spark 作业入口，用于生成 1 亿条物理一致的 ZrO2 离子电导率记录。
 *
 * 用法:
 *   spark-submit --class com.lanhung.conductivity.DataGeneratorBaseRuleApp \
 *     --master yarn \
 *     --num-executors 50 \
 *     --executor-memory 8g \
 *     --executor-cores 4 \
 *     data-generator-base-rule-1.0-SNAPSHOT.jar \
 *     [totalRecipeGroups] [numPartitions] [outputPath]
 *     [--master local[*]] [--hive-db db_name]
 *
 * 默认值: 25,000,000 组 / 1,000 分区 / ./output
 */
public class DataGeneratorBaseRuleApp {

    private static String tableLocation(String outputPath, String db, String table) {
        String base = outputPath == null ? "" : outputPath.trim();
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = ".";
        }
        return base + "/" + db + "/" + table;
    }

    private static String sqlString(String value) {
        return value.replace("'", "''");
    }

    private static void dropTableIfExists(SparkSession spark, String db, String table) {
        spark.sql("DROP TABLE IF EXISTS " + db + "." + table);
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.parse(args);

        SparkSession spark = SparkSession.builder()
                .appName("ZrO2 Conductivity Data Enhancer (" + config.totalRecipeGroups + " groups)")
                .master(config.sparkMaster)
                .enableHiveSupport()
                .getOrCreate();

        JavaSparkContext jsc = new JavaSparkContext(spark.sparkContext());

        System.out.println("Generating data for " + config.totalRecipeGroups + " recipe groups "
                + "across " + config.numPartitions + " partitions");
        System.out.println("Spark master: " + config.sparkMaster);
        System.out.println("Output path: " + config.outputPath);

        long startTime = System.currentTimeMillis();

        // 在 Executor 上直接生成 ID 序列，避免 Driver 内存压力
        JavaRDD<Long> recipeGroupIds = spark.range(0, config.totalRecipeGroups, 1, config.numPartitions)
                .as(org.apache.spark.sql.Encoders.LONG())
                .toJavaRDD();

        // 生成所有数据；每个分区使用确定性种子以保证可复现性。
        JavaRDD<GeneratedGroup> allGroups = recipeGroupIds.mapPartitionsWithIndex(
                (partitionId, iter) -> {
                    long seed = (long) partitionId * PhysicsConstants.PARTITION_SEED_MULTIPLIER
                            + PhysicsConstants.PARTITION_SEED_OFFSET;
                    Random rng = new Random(seed);
                    RecipeGroupGenerator generator = new RecipeGroupGenerator(rng);

                    List<GeneratedGroup> results = new ArrayList<>();
                    while (iter.hasNext()) {
                        long groupId = iter.next();
                        results.add(generator.generate(groupId));
                    }
                    return results.iterator();
                },
                false
        ).persist(StorageLevel.MEMORY_AND_DISK_SER());

        // 提取各个表并写入 Hive 外部表（Parquet + LOCATION）
        String db = config.hiveDatabase;
        spark.sql("CREATE DATABASE IF NOT EXISTS " + db);

        String crystalStructureDictPath = tableLocation(config.outputPath, db, "crystal_structure_dict");
        String synthesisMethodDictPath = tableLocation(config.outputPath, db, "synthesis_method_dict");
        String processingRouteDictPath = tableLocation(config.outputPath, db, "processing_route_dict");
        String materialSamplesPath = tableLocation(config.outputPath, db, "material_samples");
        String sampleDopantsPath = tableLocation(config.outputPath, db, "sample_dopants");
        String sinteringStepsPath = tableLocation(config.outputPath, db, "sintering_steps");
        String sampleCrystalPhasesPath = tableLocation(config.outputPath, db, "sample_crystal_phases");

        System.out.println("Writing crystal_structure_dict...");
        dropTableIfExists(spark, db, "crystal_structure_dict");
        spark.sql("CREATE EXTERNAL TABLE IF NOT EXISTS " + db + ".crystal_structure_dict ("
                + "id INT, code STRING, full_name STRING) USING parquet "
                + "LOCATION '" + sqlString(crystalStructureDictPath) + "'");
        spark.sql("INSERT OVERWRITE " + db + ".crystal_structure_dict VALUES "
                + "(1, 'c', 'Cubic'), "
                + "(2, 't', 'Tetragonal'), "
                + "(3, 'm', 'Monoclinic'), "
                + "(4, 'o', 'Orthogonal'), "
                + "(5, 'r', 'Rhombohedral'), "
                + "(6, 'β', 'Beta-phase')");

        System.out.println("Writing synthesis_method_dict...");
        dropTableIfExists(spark, db, "synthesis_method_dict");
        spark.sql("CREATE EXTERNAL TABLE IF NOT EXISTS " + db + ".synthesis_method_dict ("
                + "id INT, name STRING) USING parquet "
                + "LOCATION '" + sqlString(synthesisMethodDictPath) + "'");
        spark.sql("INSERT OVERWRITE " + db + ".synthesis_method_dict VALUES "
                + "(1, '/'), "
                + "(2, 'Commercialization'), "
                + "(3, 'Coprecipitation'), "
                + "(4, 'Coprecipitation method'), "
                + "(5, 'Directional melt crystallization'), "
                + "(6, 'Glycine method'), "
                + "(7, 'Hydrothermal synthesis'), "
                + "(8, 'Sol\u2013gel method'), "
                + "(9, 'Solid-state synthesis')");

        System.out.println("Writing processing_route_dict...");
        dropTableIfExists(spark, db, "processing_route_dict");
        spark.sql("CREATE EXTERNAL TABLE IF NOT EXISTS " + db + ".processing_route_dict ("
                + "id INT, name STRING) USING parquet "
                + "LOCATION '" + sqlString(processingRouteDictPath) + "'");
        spark.sql("INSERT OVERWRITE " + db + ".processing_route_dict VALUES "
                + "(1, '/'), "
                + "(2, '3D printing'), "
                + "(3, 'chemical vapor deposition'), "
                + "(4, 'cutting and polishing'), "
                + "(5, 'dry pressing'), "
                + "(6, 'isostatic pressing'), "
                + "(7, 'magnetron sputtering'), "
                + "(8, 'metal-organic chemical vapor deposition'), "
                + "(9, 'plasma spray deposition'), "
                + "(10, 'pulsed laser deposition'), "
                + "(11, 'RF sputtering'), "
                + "(12, 'spark plasma sintering'), "
                + "(13, 'spin coating'), "
                + "(14, 'spray pyrolysis'), "
                + "(15, 'tape casting'), "
                + "(16, 'ultrasonic atomization'), "
                + "(17, 'ultrasonic spray pyrolysis'), "
                + "(18, 'vacuum filtration'), "
                + "(19, 'vapor deposition')");

        System.out.println("Writing material_samples...");
        JavaRDD<MaterialSampleRow> samplesRDD = allGroups.flatMap(
                g -> Arrays.asList(g.sampleRows).iterator());
        Dataset<Row> samplesDF = spark.createDataFrame(samplesRDD, MaterialSampleRow.class)
                .withColumnRenamed("sampleId", "sample_id")
                .withColumnRenamed("recipeGroupId", "recipe_group_id")
                .withColumnRenamed("materialSourceAndPurity", "material_source_and_purity")
                .withColumnRenamed("synthesisMethodId", "synthesis_method_id")
                .withColumnRenamed("processingRouteId", "processing_route_id")
                .withColumnRenamed("operatingTemperature", "operating_temperature");
        dropTableIfExists(spark, db, "material_samples");
        samplesDF.write().mode("overwrite").format("parquet")
                .option("path", materialSamplesPath)
                .saveAsTable(db + ".material_samples");

        System.out.println("Writing sample_dopants...");
        JavaRDD<SampleDopantRow> dopantsRDD = allGroups.flatMap(
                g -> Arrays.asList(g.dopantRows).iterator());
        Dataset<Row> dopantsDF = spark.createDataFrame(dopantsRDD, SampleDopantRow.class)
                .withColumnRenamed("sampleId", "sample_id")
                .withColumnRenamed("recipeGroupId", "recipe_group_id")
                .withColumnRenamed("dopantElement", "dopant_element")
                .withColumnRenamed("dopantIonicRadius", "dopant_ionic_radius")
                .withColumnRenamed("dopantValence", "dopant_valence")
                .withColumnRenamed("dopantMolarFraction", "dopant_molar_fraction");
        dropTableIfExists(spark, db, "sample_dopants");
        dopantsDF.write().mode("overwrite").format("parquet")
                .option("path", sampleDopantsPath)
                .saveAsTable(db + ".sample_dopants");

        System.out.println("Writing sintering_steps...");
        JavaRDD<SinteringStepRow> sinteringRDD = allGroups.flatMap(
                g -> Arrays.asList(g.sinteringRows).iterator());
        Dataset<Row> sinteringDF = spark.createDataFrame(sinteringRDD, SinteringStepRow.class)
                .withColumnRenamed("sampleId", "sample_id")
                .withColumnRenamed("recipeGroupId", "recipe_group_id")
                .withColumnRenamed("stepOrder", "step_order")
                .withColumnRenamed("sinteringTemperature", "sintering_temperature")
                .withColumnRenamed("sinteringDuration", "sintering_duration");
        dropTableIfExists(spark, db, "sintering_steps");
        sinteringDF.write().mode("overwrite").format("parquet")
                .option("path", sinteringStepsPath)
                .saveAsTable(db + ".sintering_steps");

        System.out.println("Writing sample_crystal_phases...");
        JavaRDD<CrystalPhaseRow> phasesRDD = allGroups.flatMap(
                g -> Arrays.asList(g.crystalPhaseRows).iterator());
        Dataset<Row> phasesDF = spark.createDataFrame(phasesRDD, CrystalPhaseRow.class)
                .withColumnRenamed("sampleId", "sample_id")
                .withColumnRenamed("recipeGroupId", "recipe_group_id")
                .withColumnRenamed("crystalId", "crystal_id")
                .withColumnRenamed("majorPhase", "is_major_phase");
        dropTableIfExists(spark, db, "sample_crystal_phases");
        phasesDF.write().mode("overwrite").format("parquet")
                .option("path", sampleCrystalPhasesPath)
                .saveAsTable(db + ".sample_crystal_phases");

        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        System.out.printf("Data generation complete in %.1f seconds%n", elapsed);

        allGroups.unpersist();
        spark.stop();
        System.out.println("Done.");
    }
}
