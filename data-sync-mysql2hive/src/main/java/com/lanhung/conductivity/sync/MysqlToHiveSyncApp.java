package com.lanhung.conductivity.sync;

import com.lanhung.conductivity.sync.config.SyncConfig;
import com.lanhung.conductivity.sync.task.TableSyncExecutor;
import org.apache.spark.sql.SparkSession;

public class MysqlToHiveSyncApp {

    public static void main(String[] args) {
        SyncConfig config = new SyncConfig(args);

        SparkSession spark = SparkSession.builder()
                .appName("MySQL to HDFS Parquet Sync")
                .getOrCreate();

        try {
            TableSyncExecutor executor = new TableSyncExecutor(spark, config);
            executor.syncAll();
            System.out.println("All tables synced successfully.");
        } catch (Exception e) {
            System.err.println("Sync failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            spark.stop();
        }
    }
}
