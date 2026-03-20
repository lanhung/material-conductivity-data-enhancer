
#!/usr/bin/env bash


# 1亿条 material_samples 记录
# 每个 recipe group 平均产生 ~3.67 条记录
# 28,000,000 groups × 3.67 ≈ 1.03 亿条
spark-submit \
  --class com.lanhung.conductivity.DataGeneratorBaseRuleApp \
  --master yarn \
  --deploy-mode client \
  --num-executors 6 \
  --executor-memory 6g \
  --executor-cores 4 \
  --driver-memory 8g \
  --conf spark.default.parallelism=2000 \
  --conf spark.sql.shuffle.partitions=2000 \
  --conf spark.serializer=org.apache.spark.serializer.KryoSerializer \
  --conf spark.kryoserializer.buffer.max=512m \
  data-generator-base-rule/target/data-generator-base-rule-1.0-SNAPSHOT.jar \
  28000000 2000 /data/material_conductivity_data \
  --hive-db ods_zirconia_rule_based




