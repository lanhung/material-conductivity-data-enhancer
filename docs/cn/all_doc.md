# 氧化锆离子电导率数据增强系统 — 技术文档

> 说明：最新 v2 验证结果（`docs/result_v2`）及“两阶段验证/复验”分析已维护在 [all_doc_v2.md](./all_doc_v2.md)。本文保留较早批次的整体说明，若要查看最新运行结论，请优先阅读修订版。

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 系统架构](#2-系统架构)
- [3. 数据模型与表结构](#3-数据模型与表结构)
- [4. 数据生成模块 (data-generator-base-rule)](#4-数据生成模块-data-generator-base-rule)
  - [4.1 生成架构](#41-生成架构)
  - [4.2 生成流程](#42-生成流程)
  - [4.3 14 条物理约束规则](#43-14-条物理约束规则)
  - [4.4 电导率计算模型](#44-电导率计算模型)
  - [4.5 掺杂元素属性表](#45-掺杂元素属性表)
  - [4.6 合成方法与加工工艺](#46-合成方法与加工工艺)
- [5. 数据同步模块 (data-sync-mysql2hive)](#5-数据同步模块-data-sync-mysql2hive)
- [6. 数据验证模块 (data-validator)](#6-数据验证模块-data-validator)
  - [6.1 硬约束验证 (Hard Constraint)](#61-硬约束验证-hard-constraint)
  - [6.2 保真度评估 (Fidelity)](#62-保真度评估-fidelity)
  - [6.3 HC 与 Fidelity 的关系](#63-hc-与-fidelity-的关系)
- [7. 运行结果分析](#7-运行结果分析)
  - [7.1 验证运行概览](#71-验证运行概览)
  - [7.2 硬约束验证结果分析](#72-硬约束验证结果分析)
  - [7.3 保真度评估结果分析](#73-保真度评估结果分析)
  - [7.4 结果对比：原始数据 vs 合规数据](#74-结果对比原始数据-vs-合规数据)
- [8. 数据管道与部署](#8-数据管道与部署)
- [9. 代码结构索引](#9-代码结构索引)

---

## 1. 项目概述

本系统（material-conductivity-data-enhancer）是一个基于 Apache Spark 的大规模数据管道，用于**生成、同步与验证**氧化锆（ZrO₂）固体电解质离子电导率合成实验数据。

系统核心目标：基于 14 条材料科学物理约束，自动生成约 **1 亿条**高保真合成样本数据，并通过硬约束校验与统计保真度评估双重验证机制，确保生成数据既满足物理规则底线，又在分布特征上接近真实实验数据。

**技术栈：**

| 组件 | 技术 |
|------|------|
| 计算引擎 | Apache Spark 3.5.8 |
| 存储格式 | Parquet |
| 数据仓库 | Hive 外部表 |
| 数据源 | MySQL 8.0.33 |
| 编程语言 | Java 8 |
| 构建工具 | Maven |

---

## 2. 系统架构

项目由三个 Maven 子模块组成：

```
material-conductivity-data-enhancer/
├── data-generator-base-rule/    # 数据生成：基于物理规则的合成数据引擎
├── data-sync-mysql2hive/        # 数据同步：MySQL 真实数据 → HDFS Parquet
├── data-validator/              # 数据验证：硬约束校验 + 保真度评估
├── scripts/                     # Spark 提交脚本
├── sql/                         # 建表语句（MySQL / Hive）
├── docs/                        # 文档
└── pom.xml                      # 父 POM
```

**完整数据管道流程：**

```
┌─────────────────┐     ┌──────────────────┐     ┌───────────────────┐
│  MySQL 真实数据  │────▶│  data-sync-mysql  │────▶│ Hive: ods_zirconia│
│ (zirconia_v2)   │     │  2hive (Spark)    │     │ _conductivity_v2  │
└─────────────────┘     └──────────────────┘     └────────┬──────────┘
                                                          │ 作为 Fidelity
                                                          │ 参照基准
┌─────────────────┐     ┌──────────────────┐     ┌────────▼──────────┐
│  物理规则 +     │────▶│  data-generator-  │────▶│ Hive: ods_zirconia│
│  统计分布参数   │     │  base-rule (Spark)│     │ _rule_based       │
└─────────────────┘     └──────────────────┘     └────────┬──────────┘
                                                          │
                                                 ┌────────▼──────────┐
                                                 │  data-validator   │
                                                 │  (Spark)          │
                                                 │  ├─ HC 硬约束校验 │
                                                 │  └─ Fidelity 评估 │
                                                 └────────┬──────────┘
                                                          │
                                               ┌──────────▼───────────┐
                                               │ 合规数据集            │
                                               │ conductivity_        │
                                               │ compliant_rule_based │
                                               └──────────────────────┘
```

---

## 3. 数据模型与表结构

### 3.1 字典表

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| `crystal_structure_dict` | 晶体结构字典 | id, name (Cubic/Tetragonal/Monoclinic/Orthogonal/Rhombohedral/Beta-phase) |
| `synthesis_method_dict` | 合成方法字典 | id, name (9 种方法) |
| `processing_route_dict` | 加工工艺字典 | id, name (19 种工艺) |

### 3.2 核心数据表

**material_samples** — 材料样本主表

| 字段 | 类型 | 说明 |
|------|------|------|
| sample_id | BIGINT | 唯一标识（`10000001 + recipe_group_id * 8 + temp_index`；其中 `recipe_group_id` 物理存在于输出 Parquet，但当前 Hive 表结构没有暴露该列） |
| reference | STRING | 来源标识（生成数据为 `RULE_BASED_SYNTHETIC`） |
| material_source_and_purity | STRING | 材料来源与纯度描述 |
| synthesis_method_id | INT | 合成方法 ID（FK → synthesis_method_dict） |
| processing_route_id | INT | 加工工艺 ID（FK → processing_route_dict） |
| operating_temperature | DOUBLE | 测试温度（300–1400°C） |
| conductivity | DOUBLE | 离子电导率（1×10⁻⁸ – 1.0 S/cm） |

**sample_dopants** — 掺杂剂组成

| 字段 | 类型 | 说明 |
|------|------|------|
| sample_id | BIGINT | FK → material_samples |
| dopant_element | STRING | 掺杂元素符号（Y, Sc, Ce 等 20 种） |
| dopant_ionic_radius | DOUBLE | 离子半径 (pm) |
| dopant_valence | INT | 化合价 (+2, +3, +4, +5) |
| dopant_molar_fraction | DOUBLE | 掺杂摩尔分数 (0.001–0.30) |

**sintering_steps** — 烧结步骤

| 字段 | 类型 | 说明 |
|------|------|------|
| sample_id | BIGINT | FK → material_samples |
| step_order | INT | 烧结步骤序号 (1–4) |
| sintering_temperature | DOUBLE | 烧结温度 (°C) |
| sintering_duration | DOUBLE | 烧结持续时间 (min) |

**sample_crystal_phases** — 晶体相

| 字段 | 类型 | 说明 |
|------|------|------|
| sample_id | BIGINT | FK → material_samples |
| crystal_id | INT | FK → crystal_structure_dict |
| is_major_phase | BOOLEAN | 是否为主相 |

---

## 4. 数据生成模块 (data-generator-base-rule)

### 4.1 生成架构

生成单位为**配方组（Recipe Group）**，而非单条样本。

- 一个配方组 = 一组固定配方（掺杂剂、烧结条件、晶体相等）× 多个测试温度点
- 每个配方组产出 3–8 条 material_sample 记录（对应不同的测试温度）
- 同组样本共享掺杂剂、烧结参数、晶体相，仅温度和电导率不同

**规模参数：**

| 参数 | 默认值 |
|------|--------|
| 配方组总数 | 25,000,000 – 28,000,000 |
| 每组温度点数 | 3–8 |
| 总样本量 | ~100,000,000 – 113,000,000 |
| 分区数 | 1,000 – 2,000 |

### 4.2 生成流程

生成过程分为 6 步，按顺序执行：

```
Step 1: 掺杂剂生成
  ├─ 随机选择 1–5 种掺杂元素（加权抽样）
  ├─ 为每种元素生成摩尔分数（Beta 分布）
  └─ 校验：溶解度限制、总掺杂量 ≤ 0.30

Step 2: 晶体相生成
  ├─ 根据掺杂浓度及元素类型确定主相
  └─ 可选添加次相

Step 3: 烧结参数生成
  ├─ 根据合成方法确定烧结温度范围
  ├─ 生成 0–4 步烧结步骤
  └─ RF 溅射薄膜无烧结步骤

Step 4: 温度序列生成
  ├─ 生成 3–8 个测试温度点（300–1400°C）
  └─ 温度点排序

Step 5: 电导率计算
  ├─ 分段 Arrhenius 模型
  ├─ 物理修正因子叠加
  └─ 确保同组内温度单调递增 → 电导率单调递增

Step 6: 材料来源描述生成
  └─ 生成逼真的材料来源与纯度文本
```

### 4.3 14 条物理约束规则

以下为生成过程中严格遵循的 14 条物理定律与经验规则：

#### 规则 1：Arrhenius 温度依赖性

电导率随温度的变化服从 Arrhenius 方程：

```
σ(T) = σ_ref × exp[-Ea/kB × (1/T - 1/T_ref)]
```

采用**分段活化能模型**：600°C 为分界点，高温段和低温段使用不同的活化能参数（`Ea_high`、`Ea_low`），反映氧化锆在不同温度区间的离子传导机制差异。

- 参考温度 T_ref = 800°C (1073.15 K)
- 玻尔兹曼常数 kB = 8.617333 × 10⁻⁵ eV/K

#### 规则 2：离子半径与化合价固定映射

每种掺杂元素具有固定的离子半径 (pm) 和化合价，基于查找表硬编码。系统支持 20 种掺杂元素，每种元素的半径和化合价从 `DopantProperty` 查找表中获取。

#### 规则 3：晶体相-掺杂剂耦合

晶体相的选择取决于掺杂类型和浓度：

- 高浓度 Y 或 Sc 掺杂 → 倾向 Cubic 相
- 低浓度掺杂 → Tetragonal 或 Monoclinic 相
- 特定掺杂组合对应特定晶体相

#### 规则 4：氧空位创建（化合价约束）

每个样本必须包含至少一种 +2 或 +3 价的掺杂元素，以确保氧空位的产生。这是氧化锆离子传导的基本机制——低价阳离子替代 Zr⁴⁺ 产生氧空位，使氧离子能够迁移。

#### 规则 5：元素特定溶解度限制

每种元素有独立的最大溶解度上限：

| 元素 | 最大溶解度 | 元素 | 最大溶解度 |
|------|-----------|------|-----------|
| Y | 25% | Sc | 12% |
| Ce | 18% | Ca | 20% |
| Yb | 15% | Bi | 15% |
| Gd | 12% | Er | 12% |

其余元素也各有对应的溶解度限制（从 `DopantProperty.maxSolubility` 获取）。

#### 规则 6：总掺杂量限制

单个样本的掺杂总摩尔分数 ≤ 0.30（30%），超出后物理上不再是掺杂而是形成新的固溶体。

#### 规则 7：浓度非单调性

电导率与掺杂浓度之间存在非单调关系——偏离最优掺杂浓度（每种元素不同）会增加活化能，导致电导率下降。模型通过对偏离量施加活化能惩罚来实现这一效应。

#### 规则 8：离子半径-电导率相关性

不同元素的离子半径影响其在晶格中的配合程度，从而影响电导率。这通过元素特定的活化能参数（`Ea_high`、`Ea_low`）和基准电导率 (`baseConductivity`) 来体现。

#### 规则 9：烧结-合成方法耦合

烧结温度和持续时间范围取决于合成方法：

| 合成方法 | 烧结温度范围 (°C) |
|---------|-------------------|
| 固态合成 | 1200–1600 |
| 溶胶-凝胶法 | 1000–1500 |
| 共沉淀法 | 1100–1500 |
| 水热合成 | 800–1200 |
| 商业化产品 | 1300–1600 |

#### 规则 10：晶界效应

低烧结温度（< 1300°C）导致晶粒尺寸小、晶界密度高，对电导率产生衰减效应。模型对低温烧结样本施加额外的电导率惩罚因子。

#### 规则 11：ScSZ 相退化

Sc 掺杂量 < 10% 且测试温度 < 650°C 时，Sc 稳定的氧化锆（ScSZ）会发生相退化现象，电导率降低 20%–50%。

#### 规则 12：共掺杂非加和性

当掺杂元素超过 2 种时，多元掺杂的相互作用导致电导率低于单元素的简单加和。模型对第 3 种及以后的每种额外元素施加 5% 的电导率惩罚。

#### 规则 13：过渡金属电子电导

Fe 和 Mn 等过渡金属掺杂引入电子导电成分，使总电导率略有提升。模型对含过渡金属掺杂的样本施加电导率增益。

#### 规则 14：薄膜 RF 溅射特殊性

RF 溅射工艺制备的薄膜样本无烧结步骤，且具有不同的微观结构特征，对应调整电导率计算方式。

### 4.4 电导率计算模型

电导率生成采用**分段 Arrhenius + 物理修正**的复合模型：

```
σ(T) = σ_base × A(T) × C_grain × C_ScSZ × C_codope × C_TM
```

其中：

| 符号 | 含义 | 计算方式 |
|------|------|----------|
| σ_base | 基准电导率 | 由掺杂元素属性查表获得 |
| A(T) | Arrhenius 温度因子 | exp[-Ea/kB × (1/T - 1/T_ref)]，Ea 分段 |
| C_grain | 晶界修正 | 烧结温度 < 1300°C 时衰减 |
| C_ScSZ | ScSZ 退化修正 | Sc < 10% 且 T < 650°C 时衰减 20%–50% |
| C_codope | 共掺杂修正 | 每增加一种额外元素（超过 2 种）衰减 5% |
| C_TM | 过渡金属修正 | 含 Fe/Mn 时增益 |

最终电导率被截断到 [1×10⁻⁸, 1.0] S/cm 范围内，并确保同一配方组内温度递增对应电导率递增（单调性保证）。

### 4.5 掺杂元素属性表

系统支持 20 种掺杂元素，每种元素具备以下属性：

| 属性 | 说明 |
|------|------|
| 元素符号 | Y, Sc, Yb, Ce, Dy, Bi, Gd, Er, Lu, Pr, Ca, Fe, Mn, Zn, Al, In, Eu, Si, Nb, Ti |
| 离子半径 | 固定值 (pm) |
| 化合价 | +2 / +3 / +4 / +5 |
| 选择频率 | 加权抽样时的概率权重 |
| 最优掺杂浓度 | 电导率最大化时的摩尔分数 |
| 最大溶解度 | 该元素在 ZrO₂ 中的固溶极限 |
| Beta 分布参数 | α, β, scale — 用于生成摩尔分数 |
| 活化能 (高/低温) | Ea_high, Ea_low (eV) — Arrhenius 模型参数 |
| 基准电导率 | 800°C 参考条件下的电导率 |

掺杂元素频率分布中，Y 和 Sc 为最高频元素（占比分别约 38% 和 33%），反映真实研究中的重点方向。

### 4.6 合成方法与加工工艺

**9 种合成方法：**

| 合成方法 | 说明 |
|---------|------|
| Solid-state synthesis | 固态合成 |
| Commercialization | 商业化产品 |
| Sol–gel method | 溶胶-凝胶法 |
| Hydrothermal synthesis | 水热合成 |
| Coprecipitation method | 共沉淀法 |
| Glycine method | 甘氨酸法 |
| Coprecipitation | 共沉淀 |
| Directional melt crystallization | 定向熔融结晶 |
| (其他) | — |

**19 种加工工艺（部分列举）：**

| 加工工艺 | 说明 |
|---------|------|
| dry pressing | 干压 |
| isostatic pressing | 等静压 |
| tape casting | 流延成型 |
| spark plasma sintering | 放电等离子烧结 |
| RF sputtering | 射频溅射 |
| spray pyrolysis | 喷雾热分解 |
| 3D printing | 3D 打印 |
| spin coating | 旋涂 |
| magnetron sputtering | 磁控溅射 |
| pulsed laser deposition | 脉冲激光沉积 |

---

## 5. 数据同步模块 (data-sync-mysql2hive)

### 5.1 功能说明

将 MySQL 中的真实实验数据同步至 HDFS，以 Parquet 格式存储，供 Hive 外部表读取，作为保真度评估的参照基准。

本项目使用的真实数据来自仓库 [lanhung/material-conductivity-data-clean](https://github.com/lanhung/material-conductivity-data-clean)，并导入 MySQL 数据库 `zirconia_conductivity_v2` 后再执行同步。

### 5.2 同步表

共同步 7 张表：

1. `crystal_structure_dict`
2. `synthesis_method_dict`
3. `processing_route_dict`
4. `material_samples`
5. `sample_dopants`
6. `sintering_steps`
7. `sample_crystal_phases`

### 5.3 使用方式

```bash
spark-submit \
  --class com.lanhung.conductivity.sync.MysqlToHiveSyncApp \
  data-sync-mysql2hive-1.0-SNAPSHOT.jar \
  jdbc:mysql://<host>:<port>/zirconia_conductivity_v2 \
  <user> <password> \
  /data/material_conductivity_data/ods_zirconia_conductivity_v2
```

- 源数据库：`zirconia_conductivity_v2`（MySQL）
- 真实数据来源：`https://github.com/lanhung/material-conductivity-data-clean`
- 目标路径：HDFS Parquet，对应 Hive 数据库 `ods_zirconia_conductivity_v2`
- 写入模式：Overwrite

---

## 6. 数据验证模块 (data-validator)

验证模块提供两种独立的验证机制，管理不同层次的数据质量：

| 验证类型 | 核心问题 | 判定逻辑 |
|---------|---------|---------|
| **HC 硬约束** | 数据"能不能用" — 底线校验 | 一票否决制，任一约束不通过则失败 |
| **Fidelity 保真度** | 数据"像不像真的" — 分布相似度 | 加权评分制，给出综合评级 |

### 6.1 硬约束验证 (Hard Constraint)

共 13 项检查（HC-1 至 HC-10，部分细分）：

| 编号 | 约束名称 | 校验内容 |
|------|---------|---------|
| HC-1 | 电导率正值 | 所有 conductivity > 0 |
| HC-2a | 掺杂分数正值 | 所有 molar_fraction > 0 |
| HC-2b | 元素溶解度限制 | 每种元素不超过其固溶极限 |
| HC-3 | 总掺杂量限制 | 单样本总掺杂摩尔分数 ≤ 0.30 |
| HC-4 | 氧空位保证 | 每样本至少含一种 +2 或 +3 价掺杂 |
| HC-5 | 单一主相 | 每样本恰好有一个 is_major_phase = true |
| HC-6a | 引用完整性（掺杂） | 所有 dopant 的 sample_id 存在于 material_samples |
| HC-6b | 引用完整性（烧结） | 所有 sintering 的 sample_id 存在于 material_samples |
| HC-6c | 引用完整性（晶体相） | 所有 phase 的 sample_id 存在于 material_samples |
| HC-7 | 配方组内单调性 | 同组内温度升高 → 电导率升高 |
| HC-8 | 相-掺杂耦合 | 晶体相选择与掺杂剂类型/浓度一致 |
| HC-9 | 电导率范围 | conductivity ∈ [1×10⁻⁸, 1.0] |
| HC-10 | 温度范围 | operating_temperature ∈ [300, 1400] |

**判定规则：** 任一约束的 violation_count > 0 即判定整体 HC 验证 FAIL。

### 6.2 保真度评估 (Fidelity)

保真度评估将生成数据的统计特征与真实实验数据进行对比，涵盖 **9 个加权维度**。

#### 评估维度与权重

| 维度 | 权重 | 评分方法 | 类型 |
|------|------|---------|------|
| Synthesis Method | 15% | Jensen-Shannon 散度 | 分类 |
| Processing Route | 10% | Jensen-Shannon 散度 | 分类 |
| Dopant Element | 15% | Jensen-Shannon 散度 | 分类 |
| Crystal Phase (Major) | 10% | Jensen-Shannon 散度 | 分类 |
| log₁₀(Conductivity) | 20% | 分位数 RMSE 复合评分 | 数值 |
| Operating Temperature | 10% | 分位数 RMSE 复合评分 | 数值 |
| Dopant Molar Fraction | 10% | 分位数 RMSE 复合评分 | 数值 |
| Sintering Temperature | 5% | 分位数 RMSE 复合评分 | 数值 |
| Dopant-Conductivity Correlation | 5% | Pearson 相关 + 归一化 RMSE | 相关性 |

#### 评分方法

**分类维度** — Jensen-Shannon 散度：

```
JSD(P, Q) = ½ KL(P‖M) + ½ KL(Q‖M)，其中 M = ½(P + Q)
Score = 1 - √JSD    （归一化到 [0, 1]）
```

**数值维度** — 复合评分：

```
Score = 0.6 × (1 - Percentile_RMSE) + 0.2 × (1 - |mean_diff|/range) + 0.2 × min(std_gen/std_real, std_real/std_gen)
```

分位数 RMSE 基于 P5, P10, P25, P50, P75, P90, P95 七个分位数计算。

**相关性维度** — Dopant-Conductivity 相关性：

```
Score = 0.5 × |Pearson_r| + 0.5 × (1 - normalized_RMSE)
```

比较每种掺杂元素对应的平均 log₁₀(Conductivity)，在真实数据与生成数据之间的差异。

#### 评级标准

| 综合得分 | 评级 |
|---------|------|
| ≥ 0.90 | EXCELLENT |
| ≥ 0.75 | GOOD |
| ≥ 0.60 | FAIR |
| < 0.60 | POOR |

#### 输出文件

| 文件 | 内容 |
|------|------|
| `fidelity_summary` | 各维度得分、权重、加权得分、评级 |
| `fidelity_categorical` | 分类维度各类别的真实占比 vs 生成占比 |
| `fidelity_numerical` | 数值维度的统计量对比（Count, Mean, Std, 分位数等） |
| `fidelity_correlation` | 各掺杂元素的平均 log₁₀(Conductivity) 对比 |

### 6.3 HC 与 Fidelity 的关系

两种验证相互独立，可能出现以下组合：

| HC | Fidelity | 含义 |
|----|----------|------|
| PASS | GOOD+ | 理想状态：数据合规且逼真 |
| PASS | POOR | 数据不违规，但分布失真 |
| FAIL | GOOD | 分布相似但存在物理违规（如少量单调性违反） |
| FAIL | POOR | 数据既违规又失真 |

### 6.4 使用方式

```bash
# 仅跑 HC（仍然必须提供 --output-path）
spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based_v2 \
  --validate \
  --output-path /data/material_conductivity_v2/conductivity_validation_rule_based_v2

# HC + Fidelity
spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based_v2 \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_v2/conductivity_validation_rule_based_v2

# HC + Fidelity + 合规过滤
spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based_v2 \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_v2/conductivity_validation_rule_based_v2 \
  --compliant-output-path /data/material_conductivity_v2/ods_conductivity_compliant_rule_based_v2
```

如果既不传 `--validate` 也不传 `--fidelity`，程序会默认执行 `HC`；但 `--output-path` 依然是必填项。

---

## 7. 运行结果分析

以下分析基于 `docs/result_v2` 中导出的最新结果。共 4 条运行记录，但它们不是彼此孤立的 4 次任务，而是一个连续的两阶段流程：

- 第一阶段：对原始 v2 规则生成数据做“筛异常 + 生成合规库”
- 第二阶段：对合规库做“质量复验”，确认过滤后的结果可以作为稳定版本继续使用

### 7.1 验证运行概览

| run_id | 验证类型 | 目标数据库 | 样本总量 | 通过/评级 | 综合得分 | 运行时间 |
|--------|---------|-----------|---------|----------|---------|---------|
| 1776064352809 | HARD_CONSTRAINT | `ods_zirconia_rule_based_v2` | 100,007,040 | FAIL | 99.99873608897934 | 2026-04-13 15:12:32 |
| 1776065204878 | FIDELITY | `ods_zirconia_rule_based_v2` | 100,007,040 | GOOD | 0.8640317372280062 | 2026-04-13 15:26:44 |
| 1776130873472 | HARD_CONSTRAINT | `ods_conductivity_compliant_rule_based_v2` | 100,005,713 | PASS | 100 | 2026-04-14 09:41:13 |
| 1776131049790 | FIDELITY | `ods_conductivity_compliant_rule_based_v2` | 100,005,713 | GOOD | 0.8640322957399598 | 2026-04-14 09:44:09 |

**关键发现：**

- `2026-04-13`：先在原始库上发现仍有少量 `HC-7` / `HC-8` 尾部违规，同时确认原始库的整体 Fidelity 已经达到 `GOOD`
- `2026-04-14`：再对过滤后的合规库复验，结果变成 `HC = PASS`，而 Fidelity 仍保持 `GOOD`
- 原始 v2 规则生成数据 HC 仅在 `HC-7` 和 `HC-8` 上未通过，合规过滤后 HC 全部通过
- 合规过滤后样本量减少 `1,327` 条，占原始数据的 `0.0013269066%`
- 两轮 Fidelity 总分只上升 `5.5855e-7`；9 个维度单项分数变化都在 `4e-6` 量级以内，说明被过滤样本对整体统计分布几乎没有影响
- 从聚合结果看，过滤量 `1,327` 恰好等于 `HC-7 (1,264) + HC-8 (63)`；更稳妥的解读是：本轮被剔除样本几乎全部集中在这两类尾部违规，不过仅凭汇总表还不能证明两类违规完全没有重叠

### 7.2 硬约束验证结果分析

#### 合规数据集：全部通过

| 约束 | 状态 | 检查总量 | 违规数 | 通过率 |
|------|------|---------|--------|-------|
| HC-1 | PASS | 100,005,713 | 0 | 100% |
| HC-2a | PASS | 167,569,923 | 0 | 100% |
| HC-2b | PASS | 167,569,923 | 0 | 100% |
| HC-3 | PASS | 100,005,713 | 0 | 100% |
| HC-4 | PASS | 100,005,713 | 0 | 100% |
| HC-5 | PASS | 100,005,713 | 0 | 100% |
| HC-6a | PASS | 100,005,713 | 0 | 100% |
| HC-6b | PASS | 97,010,244 | 0 | 100% |
| HC-6c | PASS | 100,005,713 | 0 | 100% |
| HC-7 | PASS | 100,005,713 | 0 | 100% |
| HC-8 | PASS | 100,005,713 | 0 | 100% |
| HC-9 | PASS | 100,005,713 | 0 | 100% |
| HC-10 | PASS | 100,005,713 | 0 | 100% |

#### 原始数据集：两项约束存在违规

| 约束 | 状态 | 违规数 | 通过率 |
|------|------|--------|-------|
| HC-7 | FAIL | 1,264 | 99.99873608897934% |
| HC-8 | FAIL | 63 | 99.99993700443488% |
| 其余 11 项 | PASS | 0 | 100% |

**违规分析：**

- 原始 v2 数据当前只剩 `HC-7` 和 `HC-8` 两类尾部违规，其中 `HC-7` 为 `1,264` 条、`HC-8` 为 `63` 条，占比都非常低。
- 结果文件本身只能说明“最终落表数据里仍存在少量严格单调性违规”和“仍有极少量单相禁配边界样本”，无法单独定位到唯一根因，仍需结合违规 `sample_id`，并在必要时回查底层 Parquet 中的 `recipe_group_id` 或由 `sample_id` 反推的配方组信息。
- 合规过滤后 HC 全部 100% 通过，说明当前过滤链路已经足以把这部分尾部违规样本稳定剔除。

### 7.3 保真度评估结果分析

以下以合规数据集 `run_id = 1776131049790` 为主，因为这对应第二阶段“筛后质量复验”的最终结果。

#### 7.3.1 各维度得分总览

| 维度 | 得分 | 权重 | 加权得分 | 评级 |
|------|------|------|---------|------|
| Dopant Element | 0.983579854542093 | 15% | 0.14753697818131395 | EXCELLENT |
| Sintering Temperature | 0.9070683998357505 | 5% | 0.045353419991787526 | EXCELLENT |
| Synthesis Method | 0.8825280168513817 | 15% | 0.13237920252770724 | GOOD |
| Operating Temperature | 0.8739536607521723 | 10% | 0.08739536607521724 | GOOD |
| Processing Route | 0.8718796654473151 | 10% | 0.08718796654473152 | GOOD |
| Crystal Phase (Major) | 0.8705872327510655 | 10% | 0.08705872327510655 | GOOD |
| Dopant-Conductivity Correlation | 0.8446302026217843 | 5% | 0.04223151013108922 | GOOD |
| Dopant Molar Fraction | 0.8084145609916732 | 10% | 0.08084145609916732 | GOOD |
| log10(Conductivity) | 0.7702383645691955 | 20% | 0.1540476729138391 | GOOD |
| **综合得分** |  |  | **0.8640322957399598** | **GOOD** |

**评级分布：** 2 个 `EXCELLENT` + 7 个 `GOOD`

#### 7.3.2 分类维度详细分析

**Dopant Element：最稳定的维度**

| 元素 | 真实占比 | 生成占比 | 差异 |
|------|---------|---------|------|
| Y | 37.9022% | 34.0946% | -3.8075% |
| Sc | 32.9220% | 31.1573% | -1.7647% |
| Yb | 8.7704% | 10.5974% | +1.8270% |
| Ce | 7.0075% | 4.6830% | -2.3245% |
| Dy | 3.9665% | 3.7297% | -0.2368% |
| Bi | 3.1732% | 3.7207% | +0.5475% |

这一维度得分很高，说明当前掺杂元素频率权重已经比较接近真实数据。

**Synthesis Method：枚举体系与权重共同造成偏差**

| 方法 | 真实占比 | 生成占比 | 差异 |
|------|---------|---------|------|
| Solid-state synthesis | 45.3738% | 45.6069% | +0.2331% |
| Commercialization | 19.9112% | 19.9960% | +0.0848% |
| Hydrothermal synthesis | 17.4685% | 4.9982% | -12.4704% |
| Sol–gel method | 8.8823% | 11.9936% | +3.1113% |
| Coprecipitation method | 0% | 10.0054% | +10.0054% |
| Coprecipitation | 0% | 2.0006% | +2.0006% |
| / | 2.1466% | 0.9964% | -1.1502% |

这里的偏差和生成器当前枚举/权重完全一致：

- `Coprecipitation method` 在生成器中固定占约 `10%`
- `Coprecipitation` 还额外占约 `2%`

如果真实基准里这两个类别不存在或命名体系不同，JSD 会明显上升。

**Processing Route：当前生成器只采样 6 种工艺**

几个代表性偏差如下：

| 工艺 | 真实占比 | 生成占比 | 差异 |
|------|---------|---------|------|
| dry pressing | 80.0148% | 80.3938% | +0.3790% |
| 3D printing | 6.7358% | 0% | -6.7358% |
| vacuum filtration | 2.3686% | 0% | -2.3686% |
| RF sputtering | 0.1480% | 2.9953% | +2.8473% |
| spray pyrolysis | 0.0740% | 2.6006% | +2.5266% |
| spark plasma sintering | 0.0740% | 8.0041% | +7.9301% |

这个结果和代码完全一致：字典表虽然支持 19 个工艺，但生成分布只实际采样 6 个，因此很多真实类别必然缺失。

**Crystal Phase (Major)：当前相分布明显偏离真实数据**

| 晶体相 | 真实占比 | 生成占比 | 差异 |
|--------|---------|---------|------|
| 1 (Cubic) | 86.8891% | 53.0114% | -33.8776% |
| 2 (Tetragonal) | 6.2064% | 31.2935% | +25.0872% |
| 3 (Monoclinic) | 6.9046% | 9.2644% | +2.3598% |
| 4 (Orthogonal) | 0% | 2.3302% | +2.3302% |
| 5 (Rhombohedral) | 0% | 4.1004% | +4.1004% |

这说明当前 `PHASE_DIST_*` 参数更偏向生成 `tetragonal / orthogonal / rhombohedral`，而真实数据显著偏向 `cubic`。

#### 7.3.3 数值维度详细分析

**log10(Conductivity)**

| 统计量 | 真实数据 | 生成数据 | 差异 |
|--------|---------|---------|------|
| Count | 1,351 | 100,005,713 | — |
| Mean | -2.1133 | -2.4624 | -0.3490 |
| Std | 0.9584 | 1.2755 | +0.3172 |
| Min | -7.01 | -8.00 | -0.99 |
| P5 | -3.8711 | -5.0520 | -1.1809 |
| P50 | -1.9452 | -2.1735 | -0.2283 |
| P95 | -0.8675 | -0.8941 | -0.0266 |
| Pct_RMSE | 0.083374 | 0.083374 | — |
| Std_Ratio | 0.751359 | 0.751359 | — |

结论：

- `log10(Conductivity)` 仍然是当前最低分的维度，但已经稳定落在 `GOOD`
- 生成数据整体偏向更低的电导率，且低分位尾部偏低更明显
- 中高分位区间已经比较接近，说明当前主要差距集中在低尾和整体离散度

**Operating Temperature**

| 统计量 | 真实数据 | 生成数据 | 差异 |
|--------|---------|---------|------|
| Mean | 726.0264 | 711.3150 | -14.7114 |
| Std | 165.3772 | 187.6038 | +22.2266 |
| Min | 290 | 300 | +10 |
| P5 | 500 | 389 | -111 |
| P50 | 700 | 717 | +17 |
| P95 | 1000 | 1007 | +7 |
| Max | 1400 | 1363 | -37 |

这一维度整体表现不错。生成端温度下限被硬裁剪到 300°C，因此真实数据里的 `290°C` 不会出现。

**Dopant Molar Fraction**

| 统计量 | 真实数据 | 生成数据 | 差异 |
|--------|---------|---------|------|
| Mean | 0.0932518 | 0.0608552 | -0.0323966 |
| Std | 0.8658486 | 0.0418956 | -0.8240 |
| Min | 0.001 | 0.001 | 0 |
| P50 | 0.06 | 0.0527 | -0.0073 |
| P95 | 0.11 | 0.1337 | +0.0237 |
| Max | 35.0 | 0.2 | -34.8 |
| Std_Ratio | 0.0483867 | 0.0483867 | — |

这里最需要谨慎解读：

- 生成端严格受固溶度与总掺杂量约束，最大值只有 `0.2`
- 真实数据侧存在 `35.0` 这样的极端值，导致标准差显著放大

是否把这些极端值视为录入问题，不能仅凭结果文件下定论；更稳妥的表述是：**真实数据侧的数值口径和生成端物理约束口径并不一致，且包含明显离群点。**

**Sintering Temperature**

| 统计量 | 真实数据 | 生成数据 | 差异 |
|--------|---------|---------|------|
| Mean | 1417.5916 | 1425.7452 | +8.1536 |
| Std | 147.1596 | 133.0138 | -14.1458 |
| Min | 425 | 1000 | +575 |
| P5 | 1100 | 1200 | +100 |
| P50 | 1400 | 1450 | +50 |
| P95 | 1600 | 1650 | +50 |
| Max | 1800 | 1650 | -150 |

这和生成器当前烧结范围参数一致：

- 生成端没有低于 `1000°C` 的烧结温度
- 生成端也不会超过 `1650°C`

因此该维度虽然得分高，但本质上是“核心区间较接近”，并不是全区间完全一致。

#### 7.3.4 相关性维度分析

`Dopant-Conductivity Correlation` 这一维度已经提升到 `GOOD`，不再是当前主要短板：

| 元素 | 真实数据 | 生成数据 | 差异 |
|------|---------|---------|------|
| Sc | -1.5381 | -1.5377 | +0.0004 |
| Y | -1.8322 | -1.8323 | -0.0001 |
| Dy | -1.7652 | -1.8174 | -0.0522 |
| Ca | -2.2362 | -2.3292 | -0.0930 |
| Pr | -2.3614 | -2.5490 | -0.1876 |
| Ce | -1.5147 | -1.7503 | -0.2356 |
| Yb | -2.5684 | -2.3218 | +0.2466 |
| Lu | -0.8599 | -1.2003 | -0.3404 |
| Bi | -1.4872 | -1.9490 | -0.4619 |

这说明：

- `Sc` 和 `Y` 两个主元素已经几乎与真实值重合，说明核心元素排序基本稳定
- 仍然偏差较大的元素主要集中在 `Bi`、`Lu`、`Ce`、`Yb`
- 下一步若继续优化，更优先的是收敛整体电导率分布；相关性维度可以针对这些元素做定向校准

### 7.4 结果对比：原始数据 vs 合规数据

| 指标 | 原始数据 | 合规数据 |
|------|-----------------------------------|---------------------------------------------|
| 数据库 | `ods_zirconia_rule_based_v2` | `ods_conductivity_compliant_rule_based_v2` |
| 样本量 | 100,007,040 | 100,005,713 |
| 过滤数量 | — | 1,327 |
| HC 结果 | FAIL | PASS |
| HC 失败项 | HC-7: 1,264；HC-8: 63 | 无 |
| Fidelity 综合得分 | 0.8640317372280062 | 0.8640322957399598 |
| Fidelity 评级 | GOOD | GOOD |

结论：

- 合规过滤只移除了 `1,327` 条样本，数量级非常小
- 对整体 Fidelity 几乎没有影响，且综合分数还略有提升
- 这说明第一次运行主要是在把少量不合规尾部样本筛掉，第二次运行则是在验证“筛后数据依然像真实数据”
- 当前 v2 管线已经能在几乎不改变统计分布的前提下，把结果稳定提升到“规则上也合规”

---

## 8. 数据管道与部署

### 8.1 执行顺序

```
1. 构建项目
   mvn clean package -DskipTests

2. 同步真实数据（MySQL → Hive）
   scripts/submit-mysql2hive.sh

3. 为真实数据创建 Hive 外部表
   sql/hive/create_external_tables.sql

4. 生成规则数据
   scripts/submit-plan-e-final-original-layout.sh
   说明：这一步会直接创建并写入 ods_zirconia_rule_based_v2 下的外部表

5. 运行验证，并按需要输出合规数据
   scripts/submit-plan-e-final-original-layout-validator.sh
   scripts/submit-data-validator.sh

6. 若已产出合规数据目录，再为其创建 Hive 外部表
   sql/hive/conductivity_compliant_rule_based_v2.sql
```

补充说明：

- `sql/hive/create_external_tables_rule_based_v2.sql` 适合需要显式补建 v2 原始库外部表的场景；对当前 HDFS 提交脚本并不是必需步骤。
- `scripts/submit-data-validator.sh` 当前包含多段 `spark-submit`，分别对应原始数据验证、带过滤的验证，以及对合规库的复验。

### 8.2 Spark 资源配置（生成任务）

| 参数 | 值 |
|------|-----|
| num-executors | 6 |
| executor-memory | 6 GB |
| executor-cores | 4 |
| 配方组数 | 28,000,000 |
| 分区数 | 2,000 |

### 8.3 数据库映射

| Hive 数据库 | 内容 | 来源 |
|------------|------|------|
| `ods_zirconia_conductivity_v2` | 真实实验数据 | MySQL 同步 |
| `ods_zirconia_rule_based_v2` | 规则生成的原始数据 | data-generator-base-rule |
| `ods_conductivity_compliant_rule_based_v2` | 合规过滤后的数据 | data-validator 过滤输出 |
| `conductivity_validation_rule_based` | 验证结果 | data-validator 输出 |

---

## 9. 代码结构索引

### data-generator-base-rule

| 类 | 路径 | 职责 |
|----|------|------|
| `DataGeneratorBaseRuleApp` | .../DataGeneratorBaseRuleApp.java | 入口，Spark 作业编排 |
| `RecipeGroupGenerator` | .../generator/RecipeGroupGenerator.java | 核心生成逻辑，6 步生成流程 |
| `PhysicsConstants` | .../config/PhysicsConstants.java | 物理常量、掺杂元素查找表 |
| `DopantProperty` | .../config/DopantProperty.java | 掺杂元素属性定义 |
| `AppConfig` | .../config/AppConfig.java | 命令行参数解析 |
| `SynthesisMethod` | .../config/SynthesisMethod.java | 合成方法枚举 |
| `ProcessingRoute` | .../config/ProcessingRoute.java | 加工工艺枚举 |
| `SinteringRange` | .../config/SinteringRange.java | 烧结温度/时间范围 |
| `RandomUtils` | .../utils/RandomUtils.java | Beta/Gamma 分布采样工具 |
| `WeightedItem<T>` | .../utils/WeightedItem.java | 加权随机抽样 |
| `GeneratedGroup` | .../model/GeneratedGroup.java | 配方组容器 |
| `MaterialSampleRow` | .../model/MaterialSampleRow.java | 样本行模型 |
| `SampleDopantRow` | .../model/SampleDopantRow.java | 掺杂行模型 |
| `SinteringStepRow` | .../model/SinteringStepRow.java | 烧结行模型 |
| `CrystalPhaseRow` | .../model/CrystalPhaseRow.java | 晶体相行模型 |

### data-sync-mysql2hive

| 类 | 路径 | 职责 |
|----|------|------|
| `MysqlToHiveSyncApp` | .../sync/MysqlToHiveSyncApp.java | 入口，Spark Session 创建 |
| `TableSyncExecutor` | .../sync/TableSyncExecutor.java | 7 张表的同步编排 |
| `SyncConfig` | .../sync/SyncConfig.java | JDBC 连接参数 |

### data-validator

| 类 | 路径 | 职责 |
|----|------|------|
| `DataValidatorApp` | .../validator/DataValidatorApp.java | 入口，参数解析 |
| `DataValidator` | .../validator/DataValidator.java | HC 硬约束检查（Spark SQL） |
| `FidelityValidator` | .../validator/FidelityValidator.java | 保真度统计评估 |
| `HdfsResultSink` | .../validator/HdfsResultSink.java | 结果写入 HDFS Parquet |
