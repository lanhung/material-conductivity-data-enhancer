
spark-submit --class com.lanhung.conductivity.sync.MysqlToHiveSyncApp \
  --master yarn \
  --deploy-mode client \
  --num-executors 10 \
  --executor-memory 8g \
  --executor-cores 6 \
  --driver-memory 6g \
  data-sync-mysql2hive/target/data-sync-mysql2hive-1.0-SNAPSHOT.jar \
  jdbc:mysql://192.168.0.134:3306/zirconia_conductivity_v2 root 123456root \
  /data/material_conductivity_data/ods_zirconia_conductivity_v2