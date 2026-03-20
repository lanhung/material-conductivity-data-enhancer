#!/usr/bin/env bash

# data-validator 提交脚本
# 用法:
#   默认硬约束验证:  bash scripts/submit-data-validator.sh
#   保真度验证:      bash scripts/submit-data-validator.sh --fidelity --real-database ods_zirconia_conductivity_v2
#   抽样20%验证:     bash scripts/submit-data-validator.sh --sample-ratio 0.2

spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  --master yarn \
  --deploy-mode cluster \
  --num-executors 12 \
  --executor-memory 8g \
  --executor-cores 6 \
  --driver-memory 6g \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based \
  --sample-ratio 1

spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  --master yarn --deploy-mode client \
  --num-executors 6 \
  --executor-memory 6g \
  --executor-cores 4 \
  --driver-memory 8g \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based \
  --compliant-output-path /data/material_conductivity_data/conductivity_compliant_rule_based


spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  --master yarn --deploy-mode cluster \
  --num-executors 6 \
  --executor-memory 6g \
  --executor-cores 4 \
  --driver-memory 8g \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based \
  --compliant-output-path /data/material_conductivity_data/conductivity_compliant_rule_based \
  --sample-ratio 1

spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  --master yarn --deploy-mode cluster \
  --num-executors 6 \
  --executor-memory 6g \
  --executor-cores 4 \
  --driver-memory 8g \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database conductivity_compliant_rule_based \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based

