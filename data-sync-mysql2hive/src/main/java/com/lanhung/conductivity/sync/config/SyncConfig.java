package com.lanhung.conductivity.sync.config;

import java.util.Properties;

public class SyncConfig {

    private final String mysqlUrl;
    private final String mysqlUser;
    private final String mysqlPassword;
    private final String hdfsOutputPath;

    public SyncConfig(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: MysqlToHiveSyncApp <mysql_url> <mysql_user> <mysql_password> [hdfs_output_path]");
            System.err.println("Example: MysqlToHiveSyncApp jdbc:mysql://192.168.0.134:3306/zirconia_conductivity_v2 root 123456 /data/material_conductivity_data/ods_zirconia_conductivity_v2");
            System.exit(1);
        }
        this.mysqlUrl = args[0];
        this.mysqlUser = args[1];
        this.mysqlPassword = args[2];
        this.hdfsOutputPath = args.length > 3 ? args[3] : "/data/material_conductivity_data/ods_zirconia_conductivity_v2";
    }

    public String getMysqlUrl() { return mysqlUrl; }

    public String getMysqlUser() { return mysqlUser; }

    public String getMysqlPassword() { return mysqlPassword; }

    public String getHdfsOutputPath() { return hdfsOutputPath; }

    public Properties getJdbcProperties() {
        Properties jdbcProps = new Properties();
        jdbcProps.setProperty("user", mysqlUser);
        jdbcProps.setProperty("password", mysqlPassword);
        jdbcProps.setProperty("driver", "com.mysql.cj.jdbc.Driver");
        return jdbcProps;
    }
}
