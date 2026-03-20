-- ============================================================
-- data-validator 验证结果 MySQL 建表语句
-- ============================================================

CREATE DATABASE IF NOT EXISTS conductivity_validation
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE conductivity_validation;

-- ------------------------------------------------------------
-- 1. 验证批次表（每次运行生成一条记录）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS validation_run (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_type        ENUM('HARD_CONSTRAINT', 'FIDELITY') NOT NULL COMMENT '验证类型',
    database_name   VARCHAR(128)   NOT NULL COMMENT '被验证的 Hive 数据库',
    total_samples   INT            DEFAULT NULL COMMENT '样本总数',
    passed          TINYINT(1)     DEFAULT NULL COMMENT '是否全部通过（硬约束用）',
    overall_score   DECIMAL(6, 4)  DEFAULT NULL COMMENT '综合保真度得分（保真度用）',
    overall_grade   VARCHAR(16)    DEFAULT NULL COMMENT '综合等级: EXCELLENT/GOOD/FAIR/POOR',
    run_at          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    extra           JSON           DEFAULT NULL COMMENT '扩展信息',
    INDEX idx_run_type (run_type),
    INDEX idx_run_at (run_at)
) ENGINE=InnoDB COMMENT='验证运行批次';

-- ------------------------------------------------------------
-- 2. 硬约束检查结果（HC-1 ~ HC-10，每条约束一行）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hard_constraint_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT         NOT NULL COMMENT '关联 validation_run.id',
    constraint_code VARCHAR(16)    NOT NULL COMMENT '约束编号: HC-1, HC-2a, HC-2b, ..., HC-10',
    constraint_name VARCHAR(256)   NOT NULL COMMENT '约束描述',
    passed          TINYINT(1)     NOT NULL COMMENT '是否通过',
    total_checked   INT            DEFAULT NULL COMMENT '检查的记录数',
    violation_count INT            DEFAULT 0  COMMENT '违反记录数',
    pass_rate       DECIMAL(7, 4)  DEFAULT NULL COMMENT '通过率 (0~100%)',
    detail          JSON           DEFAULT NULL COMMENT '违反明细/附加信息',
    INDEX idx_hc_run (run_id),
    CONSTRAINT fk_hc_run FOREIGN KEY (run_id) REFERENCES validation_run (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='硬约束检查结果';

-- ------------------------------------------------------------
-- 3. 保真度评估汇总（对应 fidelity_summary.csv）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fidelity_summary (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT         NOT NULL COMMENT '关联 validation_run.id',
    dimension       VARCHAR(64)    NOT NULL COMMENT '评估维度，如 synthesis_method, log10_conductivity 等',
    score           DECIMAL(6, 4)  NOT NULL COMMENT '维度得分 (0~1)',
    weight          DECIMAL(4, 2)  NOT NULL COMMENT '维度权重',
    weighted_score  DECIMAL(6, 4)  NOT NULL COMMENT '加权得分',
    grade           VARCHAR(16)    NOT NULL COMMENT '等级: EXCELLENT/GOOD/FAIR/POOR',
    INDEX idx_fs_run (run_id),
    CONSTRAINT fk_fs_run FOREIGN KEY (run_id) REFERENCES validation_run (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='保真度维度汇总';

-- ------------------------------------------------------------
-- 4. 分类维度对比（对应 fidelity_categorical.csv）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fidelity_categorical (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT         NOT NULL COMMENT '关联 validation_run.id',
    dimension       VARCHAR(64)    NOT NULL COMMENT '维度，如 synthesis_method, dopant_element 等',
    category        VARCHAR(128)   NOT NULL COMMENT '类别值',
    real_pct        DECIMAL(7, 4)  NOT NULL COMMENT '真实数据占比 (%)',
    gen_pct         DECIMAL(7, 4)  NOT NULL COMMENT '生成数据占比 (%)',
    diff_pct        DECIMAL(7, 4)  NOT NULL COMMENT '差值 (%)',
    INDEX idx_fc_run (run_id),
    INDEX idx_fc_dim (run_id, dimension),
    CONSTRAINT fk_fc_run FOREIGN KEY (run_id) REFERENCES validation_run (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='保真度分类维度对比';

-- ------------------------------------------------------------
-- 5. 数值维度对比（对应 fidelity_numerical.csv）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fidelity_numerical (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT         NOT NULL COMMENT '关联 validation_run.id',
    dimension       VARCHAR(64)    NOT NULL COMMENT '维度，如 log10_conductivity, temperature 等',
    statistic       VARCHAR(32)    NOT NULL COMMENT '统计量: mean, std, p25, p50, p75, min, max 等',
    real_value      DOUBLE         NOT NULL COMMENT '真实数据值',
    gen_value       DOUBLE         NOT NULL COMMENT '生成数据值',
    diff            DOUBLE         DEFAULT NULL COMMENT '差值',
    INDEX idx_fn_run (run_id),
    INDEX idx_fn_dim (run_id, dimension),
    CONSTRAINT fk_fn_run FOREIGN KEY (run_id) REFERENCES validation_run (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='保真度数值维度对比';

-- ------------------------------------------------------------
-- 6. 掺杂剂-电导率相关性对比（对应 fidelity_correlation.csv）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fidelity_correlation (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id                  BIGINT         NOT NULL COMMENT '关联 validation_run.id',
    element                 VARCHAR(16)    NOT NULL COMMENT '掺杂元素符号',
    real_log10_conductivity DOUBLE         NOT NULL COMMENT '真实数据 log10(电导率) 均值',
    gen_log10_conductivity  DOUBLE         NOT NULL COMMENT '生成数据 log10(电导率) 均值',
    diff                    DOUBLE         DEFAULT NULL COMMENT '差值',
    INDEX idx_fcr_run (run_id),
    CONSTRAINT fk_fcr_run FOREIGN KEY (run_id) REFERENCES validation_run (id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='保真度掺杂剂-电导率相关性对比';
