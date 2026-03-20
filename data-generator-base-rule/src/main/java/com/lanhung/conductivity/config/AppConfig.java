package com.lanhung.conductivity.config;

import java.io.Serializable;

public class AppConfig implements Serializable {
    public final long totalRecipeGroups;
    public final int numPartitions;
    public final String outputPath;
    public final String sparkMaster;
    public final String hiveDatabase;

    public AppConfig(long totalRecipeGroups, int numPartitions, String outputPath, String sparkMaster,
                     String hiveDatabase) {
        this.totalRecipeGroups = totalRecipeGroups;
        this.numPartitions = numPartitions;
        this.outputPath = outputPath;
        this.sparkMaster = sparkMaster;
        this.hiveDatabase = hiveDatabase;
    }

    public static AppConfig parse(String[] args) {
        long totalRecipeGroups = 25000000L;
        int numPartitions = 1000;
        String outputPath = "./output";
        String sparkMaster = null;
        int positionalIndex = 0;
        String hiveDatabase = "ods_zirconia_rule_based";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--hive-db".equals(arg) && i + 1 < args.length) {
                hiveDatabase = args[++i];
                continue;
            }
            if ("--master".equals(arg) && i + 1 < args.length) {
                sparkMaster = args[++i];
                continue;
            }
            if (arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }

            if (positionalIndex == 0) {
                totalRecipeGroups = Long.parseLong(arg);
            } else if (positionalIndex == 1) {
                numPartitions = Integer.parseInt(arg);
            } else if (positionalIndex == 2) {
                outputPath = arg;
            } else {
                throw new IllegalArgumentException("Too many positional arguments: " + arg);
            }
            positionalIndex++;
        }

        if (sparkMaster == null || sparkMaster.trim().isEmpty()) {
            sparkMaster = System.getProperty("spark.master");
        }
        if (sparkMaster == null || sparkMaster.trim().isEmpty()) {
            sparkMaster = System.getenv("SPARK_MASTER");
        }
        if (sparkMaster == null || sparkMaster.trim().isEmpty()) {
            sparkMaster = "local[*]";
        }

        return new AppConfig(totalRecipeGroups, numPartitions, outputPath, sparkMaster, hiveDatabase);
    }
}
