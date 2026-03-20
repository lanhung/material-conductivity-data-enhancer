-- ============================================================
-- data-validator: Hive external tables for validation results
-- Database: conductivity_validation_rule_based
-- HDFS base: /data/material_conductivity_data/conductivity_validation_rule_based.db
-- Format: Parquet
-- ============================================================

CREATE DATABASE IF NOT EXISTS conductivity_validation_rule_based;

USE conductivity_validation_rule_based;

-- ------------------------------------------------------------
-- 1. Validation run metadata (one row per validation execution)
-- ------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS validation_run (
    run_id          BIGINT          COMMENT 'Run ID (epoch millis)',
    run_type        STRING          COMMENT 'Validation type: HARD_CONSTRAINT or FIDELITY',
    database_name   STRING          COMMENT 'Hive database being validated',
    total_count     INT             COMMENT 'Total sample count before sampling',
    sampled_count   INT             COMMENT 'Actual sample count after sampling',
    sample_ratio    DOUBLE          COMMENT 'Sampling ratio (1.0 = no sampling)',
    passed          BOOLEAN         COMMENT 'Whether all hard constraints passed',
    overall_score   DOUBLE          COMMENT 'Overall fidelity score (0~1)',
    overall_grade   STRING          COMMENT 'Grade: EXCELLENT / GOOD / FAIR / POOR',
    run_at          STRING          COMMENT 'Run timestamp (yyyy-MM-dd HH:mm:ss)'
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_validation_rule_based.db/validation_run';

-- ------------------------------------------------------------
-- 2. Hard constraint check results (one row per constraint)
-- ------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS hard_constraint_result (
    run_id          BIGINT          COMMENT 'References validation_run.run_id',
    constraint_code STRING          COMMENT 'Constraint code: HC-1, HC-2a, ..., HC-10',
    constraint_name STRING          COMMENT 'Constraint description',
    passed          BOOLEAN         COMMENT 'Whether this constraint passed',
    total_checked   INT             COMMENT 'Number of records checked',
    violation_count INT             COMMENT 'Number of violations',
    pass_rate       DOUBLE          COMMENT 'Pass rate (0~100%)'
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_validation_rule_based.db/hard_constraint_result';

-- ------------------------------------------------------------
-- 3. Fidelity dimension summary
-- ------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS fidelity_summary (
    run_id          BIGINT          COMMENT 'References validation_run.run_id',
    dimension       STRING          COMMENT 'Evaluation dimension, e.g. synthesis_method, log10_conductivity',
    score           DOUBLE          COMMENT 'Dimension score (0~1)',
    weight          DOUBLE          COMMENT 'Dimension weight',
    weighted_score  DOUBLE          COMMENT 'Weighted score',
    grade           STRING          COMMENT 'Grade: EXCELLENT / GOOD / FAIR / POOR'
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_validation_rule_based.db/fidelity_summary';

-- ------------------------------------------------------------
-- 4. Fidelity categorical distribution comparison
-- ------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS fidelity_categorical (
    run_id          BIGINT          COMMENT 'References validation_run.run_id',
    dimension       STRING          COMMENT 'Dimension, e.g. synthesis_method, dopant_element',
    category        STRING          COMMENT 'Category value',
    real_pct        DOUBLE          COMMENT 'Real data percentage (%)',
    gen_pct         DOUBLE          COMMENT 'Generated data percentage (%)',
    diff_pct        DOUBLE          COMMENT 'Difference (%)'
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_validation_rule_based.db/fidelity_categorical';

-- ------------------------------------------------------------
-- 5. Fidelity numerical distribution comparison
-- ------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS fidelity_numerical (
    run_id          BIGINT          COMMENT 'References validation_run.run_id',
    dimension       STRING          COMMENT 'Dimension, e.g. log10_conductivity, temperature',
    statistic       STRING          COMMENT 'Statistic: mean, std, p25, p50, p75, min, max, etc.',
    real_value      DOUBLE          COMMENT 'Real data value',
    gen_value       DOUBLE          COMMENT 'Generated data value',
    diff            DOUBLE          COMMENT 'Difference'
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_validation_rule_based.db/fidelity_numerical';

-- ------------------------------------------------------------
-- 6. Fidelity dopant-conductivity correlation comparison
-- ------------------------------------------------------------
CREATE EXTERNAL TABLE IF NOT EXISTS fidelity_correlation (
    run_id                  BIGINT  COMMENT 'References validation_run.run_id',
    element                 STRING  COMMENT 'Dopant element symbol',
    real_log10_conductivity DOUBLE  COMMENT 'Real data mean log10(conductivity)',
    gen_log10_conductivity  DOUBLE  COMMENT 'Generated data mean log10(conductivity)',
    diff                    DOUBLE  COMMENT 'Difference'
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_validation_rule_based.db/fidelity_correlation';
