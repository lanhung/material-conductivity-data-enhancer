# Material Conductivity Data Enhancer

Based on real ZrO₂ (zirconia) ionic conductivity experimental data, this project generates billions of synthetic data records through physics-rule-driven methods, with hard constraint validation and fidelity assessment on the generated data.

## Data Source

Real experimental data comes from [material-conductivity-data-clean](https://github.com/lanhung/material-conductivity-data-clean), with the original table structure in the `zirconia_conductivity` database. This project further decomposes and normalizes the fields to form the `zirconia_conductivity_v2` database.

## Overall Workflow

```
Raw Data (zirconia_conductivity)
    │
    ├─ 1. Field Decomposition ── create_db_tb_v2.sql ──→ zirconia_conductivity_v2 (MySQL)
    │     Extract synthesis method, processing route, etc. into dictionary tables;
    │     sample table references them via foreign keys
    │
    ├─ 2. Data Migration ─────── etl_v1_to_v2.sql ─────→ ETL v1 data into v2 schema
    │
    ├─ 3. Sync to Hive ──────── data-sync-mysql2hive ──→ ods_zirconia_conductivity_v2 (Hive/HDFS Parquet)
    │     Spark job reads MySQL and writes to HDFS Parquet external tables
    │
    ├─ 4. Rule-Based Generation  data-generator-base-rule → ods_zirconia_rule_based (Hive/HDFS Parquet)
    │     Generates ~100 million synthetic records based on 14 physics rules
    │
    └─ 5. Filtering & Validation  data-validator ──────────→ Hard constraint validation + fidelity assessment + compliant data filtering
          Compares statistical distributions of real vs. generated data; outputs validation report
```

## Project Structure

```
material-conductivity-data-enhancer/
├── sql/
│   ├── mysql/
│   │   ├── create_db_tb_v2.sql          # v2 database & table creation (dictionary + sample + association tables)
│   │   └── etl_v1_to_v2.sql             # v1 → v2 data migration ETL
│   └── hive/
│       ├── create_external_tables.sql    # Hive external table definitions for real data
│       ├── create_external_tables_rule_based.sql  # Hive external table definitions for generated data
│       └── conductivity_compliant_rule_based.sql  # Hive external table definitions for compliant data
├── data-sync-mysql2hive/                 # MySQL → Hive sync Spark job
├── data-generator-base-rule/             # Physics-rule-based data generation Spark job
├── data-validator/                       # Data validation Spark job
├── scripts/
│   ├── submit.sh                         # Data generation submit script
│   ├── submit-mysql2hive.sh              # MySQL sync submit script
│   └── submit-data-validator.sh          # Data validation submit script
├── docs/                                 # Project documentation
└── pom.xml                               # Maven parent POM
```

## Database Design (v2)

The original single table is decomposed into the following normalized structure:

| Table Name | Description |
|------------|-------------|
| `crystal_structure_dict` | Crystal structure dictionary (Cubic, Tetragonal, etc.) |
| `synthesis_method_dict` | Synthesis method dictionary (Coprecipitation, Sol-gel, etc.) |
| `processing_route_dict` | Processing route dictionary (dry pressing, spin coating, etc.) |
| `material_samples` | Sample master table (foreign key references to dictionary tables) |
| `sample_dopants` | Dopant detail table (element, ionic radius, valence, mole fraction) |
| `sintering_steps` | Sintering step table (temperature, duration, step sequence) |
| `sample_crystal_phases` | Sample-crystal phase association table (primary/secondary phase flag) |

## Physics Rule Generation Engine

The `data-generator-base-rule` module includes 14 built-in physics rules to ensure the generated data is physically reasonable:

1. Arrhenius temperature dependence (segmented)
2. Ionic radius / valence from lookup tables
3. Crystal phase–dopant coupling
4. At least one +2/+3 valence dopant (oxygen vacancy requirement)
5. Solid solubility limits per element
6. Total dopant fraction ≤ 0.30
7. Concentration non-monotonicity (activation energy vs. optimal deviation)
8. Ionic radius–conductivity correlation
9. Sintering–synthesis method coupling
10. Grain boundary effect (low sintering temperature penalty)
11. ScSZ phase degradation (Sc < 10%, T < 650°C)
12. Co-doping non-additivity (≥ 3 dopants penalty)
13. Transition metal electronic conductivity (Fe/Mn enhancement)
14. Thin film process: RF sputtering without sintering

## Data Validation

The `data-validator` module provides two types of validation:

- **Hard Constraint Validation**: Checks whether the generated data 100% satisfies physical constraints (e.g., temperature range, dopant fraction upper limit, foreign key integrity, etc.)
- **Fidelity Validation**: Statistical comparison against real experimental data
  - Categorical distribution — Jensen-Shannon divergence (synthesis method, processing route, dopant elements, crystal phases)
  - Numerical distribution — Normalized RMSE of quantile vectors (conductivity, temperature, dopant fraction, sintering temperature)
  - Joint distribution — Primary dopant–conductivity correlation preservation
- **Compliant Data Filtering**: Outputs data passing hard constraints to a separate Hive database

## Tech Stack

- Java 8 + Apache Spark 3.5.8 (Scala 2.12)
- Apache Hive (external tables + Parquet format)
- HDFS (data storage)
- MySQL (raw data & v2 normalized data)
- Maven (multi-module build)

## Quick Start

### Build

```bash
mvn clean package -DskipTests
```

### 1. MySQL Table Creation & Data Migration

```sql
-- Create v2 database and tables
source sql/mysql/create_db_tb_v2.sql

-- Migrate v1 data to v2
source sql/mysql/etl_v1_to_v2.sql
```

### 2. Sync MySQL to Hive

Sync v2 data from MySQL to HDFS Parquet files:

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

After sync is complete, create external tables in Hive to register metadata:

```bash
hive -f sql/hive/create_external_tables.sql
```

### 3. Generate Synthetic Data

Generate ~100 million synthetic records based on physics rules, written to HDFS Parquet:

```bash
# 28,000,000 recipe groups × ~3.67 records/group ≈ 103 million material_samples
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

After generation is complete, create the corresponding Hive external tables:

```bash
hive -f sql/hive/create_external_tables_rule_based.sql
```

### 4. Validation & Filtering

Perform hard constraint validation, fidelity assessment, and filter compliant data:

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

After validation is complete, create Hive external tables for the compliant data:

```bash
hive -f sql/hive/conductivity_compliant_rule_based.sql
```

### Spark Resource Parameter Reference

The placeholders `<N>`, `<M>`, `<C>`, `<D>` in the above commands should be configured based on your actual cluster resources:

| Parameter | Description | Reference Value |
|-----------|-------------|-----------------|
| `--num-executors` | Number of executors | Adjust based on cluster nodes and available cores |
| `--executor-memory` | Memory per executor | 4g – 8g, depending on node memory |
| `--executor-cores` | Cores per executor | 2 – 6 |
| `--driver-memory` | Driver memory | 4g – 8g |
| `spark.default.parallelism` | Default parallelism | Typically 2–3× total cores |
| `spark.sql.shuffle.partitions` | Shuffle partition count | Keep consistent with parallelism |

## Hive Databases

| Database | Description |
|----------|-------------|
| `ods_zirconia_conductivity_v2` | Real experimental data (synced from MySQL) |
| `ods_zirconia_rule_based` | Rule-generated synthetic data |
| `conductivity_compliant_rule_based` | Compliant data passing hard constraint validation |
| `conductivity_validation_rule_based` | Validation report output |
