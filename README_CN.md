# Material Conductivity Data Enhancer

基于真实 ZrO₂（氧化锆）离子电导率实验数据，通过物理规则驱动的方式批量生成亿级合成数据，并对生成数据进行硬约束验证与保真度评估。

## 数据来源

真实实验数据来自 [material-conductivity-data-clean](https://github.com/lanhung/material-conductivity-data-clean)，原始表结构为 `zirconia_conductivity` 数据库。本项目在此基础上进行了进一步的字段拆解与规范化，形成 `zirconia_conductivity_v2` 数据库。

## 整体流程

```
原始数据 (zirconia_conductivity)
    │
    ├─ 1. 字段拆解建表 ─── create_db_tb_v2.sql ──→ zirconia_conductivity_v2 (MySQL)
    │     将合成方法、加工路线等字符串字段抽取为字典表，样本表改为外键引用
    │
    ├─ 2. 数据迁移 ─────── etl_v1_to_v2.sql ─────→ 将 v1 数据 ETL 至 v2 表结构
    │
    ├─ 3. 同步至 Hive ──── data-sync-mysql2hive ──→ ods_zirconia_conductivity_v2 (Hive/HDFS Parquet)
    │     Spark 作业读取 MySQL，写入 HDFS Parquet 外部表
    │
    ├─ 4. 规则生成数据 ─── data-generator-base-rule → ods_zirconia_rule_based (Hive/HDFS Parquet)
    │     基于 14 条物理规则，生成 ~1 亿条合成数据
    │
    └─ 5. 过滤验证 ─────── data-validator ──────────→ 硬约束验证 + 保真度评估 + 合规数据过滤
          对比真实数据与生成数据的统计分布，输出验证报告
```

## 项目结构

```
material-conductivity-data-enhancer/
├── sql/
│   ├── mysql/
│   │   ├── create_db_tb_v2.sql          # v2 建库建表（字典表 + 样本表 + 关联表）
│   │   └── etl_v1_to_v2.sql             # v1 → v2 数据迁移 ETL
│   └── hive/
│       ├── create_external_tables.sql    # 真实数据 Hive 外部表定义
│       ├── create_external_tables_rule_based.sql  # 生成数据 Hive 外部表定义
│       └── conductivity_compliant_rule_based.sql  # 合规数据 Hive 外部表定义
├── data-sync-mysql2hive/                 # MySQL → Hive 同步 Spark 作业
├── data-generator-base-rule/             # 基于物理规则的数据生成 Spark 作业
├── data-validator/                       # 数据验证 Spark 作业
├── scripts/
│   ├── submit.sh                         # 数据生成提交脚本
│   ├── submit-mysql2hive.sh              # MySQL 同步提交脚本
│   └── submit-data-validator.sh          # 数据验证提交脚本
├── docs/
│   └── run_result/                       # 导出的验证运行结果（TSV）
└── pom.xml                               # Maven 父 POM
```

## 数据库设计 (v2)

在原始单表基础上拆解为以下规范化结构：

| 表名 | 说明 |
|------|------|
| `crystal_structure_dict` | 晶体结构字典表（Cubic、Tetragonal 等） |
| `synthesis_method_dict` | 合成方法字典表（Coprecipitation、Sol-gel 等） |
| `processing_route_dict` | 加工路线字典表（dry pressing、spin coating 等） |
| `material_samples` | 样本主表（外键关联字典表） |
| `sample_dopants` | 掺杂剂明细表（元素、离子半径、化合价、摩尔分数） |
| `sintering_steps` | 烧结步骤表（温度、持续时间、步骤序号） |
| `sample_crystal_phases` | 样本-晶相关联表（主相/次相标记） |

## 物理规则生成引擎

`data-generator-base-rule` 模块内置 14 条物理规则，确保生成数据在物理上合理：

1. 阿伦尼乌斯温度依赖性（分段式）
2. 离子半径/化合价来自查找表
3. 晶相-掺杂剂耦合
4. 至少一个 +2/+3 价掺杂剂（氧空位条件）
5. 各元素的固溶度极限
6. 总掺杂分数 ≤ 0.30
7. 浓度非单调性（活化能与最优偏差）
8. 离子半径-电导率相关性
9. 烧结-合成方法耦合
10. 晶界效应（低烧结温度惩罚）
11. ScSZ 相退化（Sc<10%, T<650°C）
12. 共掺杂非加性（≥3 掺杂剂惩罚）
13. 过渡金属电子电导率（Fe/Mn 增益）
14. 薄膜工艺：RF 溅射无烧结

## 数据验证

`data-validator` 模块提供两类验证：

- **硬约束验证**：检查生成数据是否 100% 满足物理约束（如温度范围、掺杂分数上限、外键完整性等）
- **保真度验证**：与真实实验数据进行统计对比
  - 类别分布 — Jensen-Shannon 散度（合成方法、加工路线、掺杂元素、晶相）
  - 数值分布 — 分位数向量的归一化 RMSE（电导率、温度、掺杂分数、烧结温度）
  - 联合分布 — 主掺杂-电导率关联的保持度
- **合规数据过滤**：将通过硬约束的数据输出到独立的 Hive 库

## 运行结果

导出的运行结果位于 [docs/run_result/](docs/run_result/)。

### 数据概览

| 数据集 | 总记录数 | 采样比例 |
|--------|---------|---------|
| `ods_zirconia_rule_based`（原始生成） | 113,968,301 | 100% |
| `conductivity_compliant_rule_based`（过滤后） | 113,960,665 | 100% |

### 硬约束验证结果

原始生成数据（`ods_zirconia_rule_based`）：通过率 **99.993%**，共 7,636 条违规数据被过滤。
合规数据（`conductivity_compliant_rule_based`）：所有约束通过率 **100%**。

| 约束编号 | 说明 | 原始通过率 | 合规通过率 |
|---------|------|-----------|-----------|
| HC-1 | 电导率 > 0 | 100% | 100% |
| HC-2a | 摩尔分数 > 0 | 100% | 100% |
| HC-2b | 元素固溶度极限 | 100% | 100% |
| HC-3 | 总掺杂分数 ≤ 0.30 | 100% | 100% |
| HC-4 | 至少一个 +2/+3 价掺杂剂 | 100% | 100% |
| HC-5 | 每个样本恰好一个主相 | 100% | 100% |
| HC-6a/b/c | 外键完整性 | 100% | 100% |
| HC-7 | 单调性（温度↑ → 电导率↑） | 99.993% | 100% |
| HC-8 | 晶相-掺杂剂耦合 | 99.999% | 100% |
| HC-9 | 电导率范围 [1e-8, 1.0] | 100% | 100% |
| HC-10 | 温度范围 [300, 1400] | 100% | 100% |

### 保真度评估

总体保真度评分：**0.847（GOOD）**

| 维度 | 得分 | 等级 | 权重 |
|------|------|------|------|
| 掺杂元素 | 0.984 | EXCELLENT | 15% |
| 烧结温度 | 0.907 | EXCELLENT | 5% |
| 合成方法 | 0.883 | GOOD | 15% |
| 工作温度 | 0.874 | GOOD | 10% |
| 加工路线 | 0.872 | GOOD | 10% |
| 晶相（主相） | 0.871 | GOOD | 10% |
| 掺杂摩尔分数 | 0.808 | GOOD | 10% |
| log10(电导率) | 0.768 | GOOD | 20% |
| 掺杂-电导率关联 | 0.503 | POOR | 5% |

## 技术栈

- Java 8 + Apache Spark 3.5.8 (Scala 2.12)
- Apache Hive（外部表 + Parquet 格式）
- HDFS（数据存储）
- MySQL（原始数据 & v2 规范化数据）
- Maven（多模块构建）

## 快速开始

### 构建

```bash
mvn clean package -DskipTests
```

### 1. MySQL 建表 & 数据迁移

```sql
-- 创建 v2 数据库和表
source sql/mysql/create_db_tb_v2.sql

-- 将 v1 数据迁移至 v2
source sql/mysql/etl_v1_to_v2.sql
```

### 2. 同步 MySQL 至 Hive

将 MySQL 中的 v2 数据同步为 HDFS Parquet 文件：

```bash
spark-submit \
  --class com.lanhung.conductivity.sync.MysqlToHiveSyncApp \
  --master yarn \
  --deploy-mode client \
  --num-executors <N> \
  --executor-memory <M>g \
  --executor-cores <C> \
  --driver-memory <D>g \
  data-sync-mysql2hive/target/data-sync-mysql2hive-1.0-SNAPSHOT.jar \
  jdbc:mysql://<host>:<port>/zirconia_conductivity_v2 <user> <password> \
  /data/material_conductivity_data/ods_zirconia_conductivity_v2
```

同步完成后，在 Hive 中创建外部表以注册元数据：

```bash
hive -f sql/hive/create_external_tables.sql
```

### 3. 生成合成数据

基于物理规则生成 ~1 亿条合成数据，写入 HDFS Parquet：

```bash
# 28,000,000 个配方组 × 平均 ~3.67 条/组 ≈ 1.03 亿条 material_samples
spark-submit \
  --class com.lanhung.conductivity.DataGeneratorBaseRuleApp \
  --master yarn \
  --deploy-mode client \
  --num-executors <N> \
  --executor-memory <M>g \
  --executor-cores <C> \
  --driver-memory <D>g \
  --conf spark.default.parallelism=2000 \
  --conf spark.sql.shuffle.partitions=2000 \
  --conf spark.serializer=org.apache.spark.serializer.KryoSerializer \
  --conf spark.kryoserializer.buffer.max=512m \
  data-generator-base-rule/target/data-generator-base-rule-1.0-SNAPSHOT.jar \
  <totalRecipeGroups> <numPartitions> /data/material_conductivity_data \
  --hive-db ods_zirconia_rule_based
```

生成完成后，创建对应的 Hive 外部表：

```bash
hive -f sql/hive/create_external_tables_rule_based.sql
```

### 4. 验证 & 过滤

对生成数据进行硬约束验证、保真度评估，并过滤输出合规数据：

```bash
spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  --master yarn \
  --deploy-mode client \
  --num-executors <N> \
  --executor-memory <M>g \
  --executor-cores <C> \
  --driver-memory <D>g \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based \
  --compliant-output-path /data/material_conductivity_data/conductivity_compliant_rule_based \
  --sample-ratio 1
```

验证完成后，创建合规数据的 Hive 外部表：

```bash
hive -f sql/hive/conductivity_compliant_rule_based.sql
```

### Spark 资源参数说明

以上命令中 `<N>`、`<M>`、`<C>`、`<D>` 等占位符需根据实际集群资源配置：

| 参数 | 说明 | 参考值 |
|------|------|--------|
| `--num-executors` | Executor 数量 | 根据集群节点数和可用核心数调整 |
| `--executor-memory` | 每个 Executor 内存 | 4g ~ 8g，视节点内存而定 |
| `--executor-cores` | 每个 Executor 核心数 | 2 ~ 6 |
| `--driver-memory` | Driver 内存 | 4g ~ 8g |
| `spark.default.parallelism` | 默认并行度 | 通常设为总核心数的 2~3 倍 |
| `spark.sql.shuffle.partitions` | Shuffle 分区数 | 与并行度保持一致 |

## Hive 数据库

| 数据库 | 说明 |
|--------|------|
| `ods_zirconia_conductivity_v2` | 真实实验数据（从 MySQL 同步） |
| `ods_zirconia_rule_based` | 规则生成的合成数据 |
| `conductivity_compliant_rule_based` | 通过硬约束验证的合规数据 |
| `conductivity_validation_rule_based` | 验证报告输出 |
