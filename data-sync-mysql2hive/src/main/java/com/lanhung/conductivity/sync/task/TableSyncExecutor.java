package com.lanhung.conductivity.sync.task;

import com.lanhung.conductivity.sync.config.SyncConfig;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;

import java.util.Arrays;
import java.util.List;

/**
 * MySQL -> HDFS Parquet sync executor.
 * Reads MySQL tables via JDBC and writes them as Parquet files to HDFS.
 * Hive external tables should be created separately via DDL.
 */
public class TableSyncExecutor {

    private final SparkSession spark;
    private final SyncConfig config;

    private static final List<String> TABLES = Arrays.asList(
            "crystal_structure_dict",
            "synthesis_method_dict",
            "processing_route_dict",
            "material_samples",
            "sample_dopants",
            "sintering_steps",
            "sample_crystal_phases"
    );

    public TableSyncExecutor(SparkSession spark, SyncConfig config) {
        this.spark = spark;
        this.config = config;
    }

    public void syncAll() {
        for (String table : TABLES) {
            syncTable(table);
        }
    }

    private void syncTable(String tableName) {
        String outputPath = config.getHdfsOutputPath() + "/" + tableName;
        System.out.println("Syncing table: " + tableName + " -> " + outputPath);

        Dataset<Row> df = spark.read()
                .jdbc(config.getMysqlUrl(), tableName, config.getJdbcProperties());

        df.write()
                .mode(SaveMode.Overwrite)
                .parquet(outputPath);

        long count = spark.read().parquet(outputPath).count();
        System.out.println("Table " + tableName + " synced, total rows: " + count);
    }
}
