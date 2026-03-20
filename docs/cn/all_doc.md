# 氧化锆离子电导率数据增强系统 — 技术文档

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
| sample_id | BIGINT | 唯一标识 (`10000001 + recipe_group_id * 8 + temp_index`) |
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
# 仅硬约束验证
spark-submit --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based --validate

# 硬约束 + 保真度评估
spark-submit --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based

# 带合规数据过滤输出
spark-submit --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/.../conductivity_validation_rule_based \
  --compliant-output-path /data/.../conductivity_compliant_rule_based
```

---

## 7. 运行结果分析

以下分析基于 2026-03-18 的实际运行结果。共执行了 **2 次验证**：第一次对原始生成数据（`ods_zirconia_rule_based`）进行 HC + Fidelity 验证，发现少量违规后将合规数据过滤出来存入 `conductivity_compliant_rule_based`，然后对过滤后的数据进行第二次 HC + Fidelity 验证，共产生 4 条验证记录。

### 7.1 验证运行概览

| run_id | 验证类型 | 目标数据库 | 样本总量 | 通过/评级 | 综合得分 | 运行时间 |
|--------|---------|-----------|---------|----------|---------|---------|
| 1773802503281 | HC 硬约束 | conductivity_compliant_rule_based | 113,960,665 | **PASS** | 100 | 10:55:03 |
| 1773800129257 | HC 硬约束 | ods_zirconia_rule_based | 113,968,301 | **FAIL** | 99.993 | 10:15:29 |
| 1773802681046 | Fidelity | conductivity_compliant_rule_based | 113,960,665 | **GOOD** | 0.8465 | 10:58:01 |
| 1773801307603 | Fidelity | ods_zirconia_rule_based | 113,968,301 | **GOOD** | 0.8465 | 10:35:07 |

**关键发现：**

- 第一次验证发现原始数据（`ods_zirconia_rule_based`）HC 未通过，过滤掉违规数据后得到合规数据集（`conductivity_compliant_rule_based`），第二次验证 HC 100% 通过
- 两个数据库的保真度得分几乎完全一致（0.8465 vs 0.8465），说明过滤掉的违规样本极少（仅约 7,636 条），对整体分布影响可忽略
- 总样本量差异：113,968,301 - 113,960,665 = **7,636 条被过滤**（占比 0.0067%）

### 7.2 硬约束验证结果分析

#### 合规数据集 (conductivity_compliant_rule_based) — 全部通过

| 约束 | 状态 | 检查总量 | 违规数 | 通过率 |
|------|------|---------|--------|-------|
| HC-1: 电导率正值 | PASS | 113,960,665 | 0 | 100% |
| HC-2a: 摩尔分数正值 | PASS | 190,961,722 | 0 | 100% |
| HC-2b: 元素溶解度 | PASS | 190,961,722 | 0 | 100% |
| HC-3: 总掺杂量 ≤ 0.30 | PASS | 113,960,665 | 0 | 100% |
| HC-4: 氧空位保证 | PASS | 113,960,665 | 0 | 100% |
| HC-5: 单一主相 | PASS | 113,960,665 | 0 | 100% |
| HC-6a: 引用完整性(掺杂) | PASS | 113,960,665 | 0 | 100% |
| HC-6b: 引用完整性(烧结) | PASS | 110,543,958 | 0 | 100% |
| HC-6c: 引用完整性(晶体相) | PASS | 113,960,665 | 0 | 100% |
| HC-7: 单调性 | PASS | 113,960,665 | 0 | 100% |
| HC-8: 相-掺杂耦合 | PASS | 113,960,665 | 0 | 100% |
| HC-9: 电导率范围 | PASS | 113,960,665 | 0 | 100% |
| HC-10: 温度范围 | PASS | 113,960,665 | 0 | 100% |

#### 原始数据集 (ods_zirconia_rule_based) — 两项约束存在违规

| 约束 | 状态 | 违规数 | 通过率 |
|------|------|--------|-------|
| HC-7: 单调性 | **FAIL** | 7,569 | 99.993% |
| HC-8: 相-掺杂耦合 | **FAIL** | 67 | 99.99994% |
| 其余 11 项 | PASS | 0 | 100% |

**违规分析：**

- **HC-7 单调性违规（7,569 条）：** 约 0.0066% 的样本在同一配方组内出现温度升高但电导率未单调递增的情况。这可能是在多种物理修正因子叠加后、浮点截断时产生的极小数值逆转。
- **HC-8 相-掺杂耦合违规（67 条）：** 极少量样本的晶体相选择与其掺杂剂类型/浓度不一致。占比约百万分之 0.6，属于生成逻辑的边界情况。

### 7.3 保真度评估结果分析

以下以合规数据集（run_id: 1773802681046）为主进行分析。

#### 7.3.1 各维度得分总览

| 维度 | 得分 | 权重 | 加权得分 | 评级 |
|------|------|------|---------|------|
| Dopant Element | **0.9836** | 15% | 0.1475 | EXCELLENT |
| Sintering Temperature | **0.9071** | 5% | 0.0454 | EXCELLENT |
| Synthesis Method | 0.8825 | 15% | 0.1324 | GOOD |
| Operating Temperature | 0.8740 | 10% | 0.0874 | GOOD |
| Processing Route | 0.8719 | 10% | 0.0872 | GOOD |
| Crystal Phase (Major) | 0.8706 | 10% | 0.0871 | GOOD |
| Dopant Molar Fraction | 0.8084 | 10% | 0.0808 | GOOD |
| log₁₀(Conductivity) | 0.7682 | 20% | 0.1536 | GOOD |
| Dopant-Conductivity Correlation | **0.5029** | 5% | 0.0251 | POOR |
| **综合得分** | | | **0.8465** | **GOOD** |

**评级分布：** 2 个 EXCELLENT + 6 个 GOOD + 1 个 POOR

#### 7.3.2 分类维度详细分析

**Dopant Element（得分 0.9836，EXCELLENT）**

掺杂元素分布是所有维度中最优的，主要元素占比高度吻合：

| 元素 | 真实占比 | 生成占比 | 差异 |
|------|---------|---------|------|
| Y | 37.90% | 34.09% | -3.81% |
| Sc | 32.92% | 31.16% | -1.77% |
| Yb | 8.77% | 10.60% | +1.83% |
| Ce | 7.01% | 4.68% | -2.32% |
| Dy | 3.97% | 3.73% | -0.24% |
| Bi | 3.17% | 3.72% | +0.55% |

主要偏差集中在 Y 和 Ce，但总体偏差均控制在 4% 以内。

**Synthesis Method（得分 0.8825，GOOD）**

| 方法 | 真实占比 | 生成占比 | 差异 |
|------|---------|---------|------|
| Solid-state synthesis | 45.37% | 45.60% | +0.23% |
| Commercialization | 19.91% | 20.00% | +0.09% |
| Hydrothermal synthesis | 17.47% | 5.00% | **-12.47%** |
| Sol–gel method | 8.88% | 11.99% | +3.11% |
| Coprecipitation method | 0% | 10.01% | **+10.01%** |
| (unknown) | 4.59% | 0% | -4.59% |

主要偏差来源：水热合成（Hydrothermal）占比偏低 12.5 个百分点，共沉淀法（Coprecipitation method）在真实数据中不存在但生成数据中占 10%。这是由于真实数据中该类别归入了其他分类。

**Processing Route（得分 0.8719，GOOD）**

干压（dry pressing）在真实和生成数据中均占绝对主导（~80%），匹配良好。主要偏差：

- 3D printing：真实 6.74%，生成 0%（缺失）
- spark plasma sintering：真实 0.07%，生成 8.00%（过高）
- vacuum filtration：真实 2.37%，生成 0%（缺失）

部分小众工艺在生成数据中缺失或比例失衡。

**Crystal Phase（得分 0.8706，GOOD）**

| 晶体相 | 真实占比 | 生成占比 | 差异 |
|--------|---------|---------|------|
| 1 (Cubic) | 86.89% | 53.01% | **-33.88%** |
| 2 (Tetragonal) | 6.21% | 31.29% | **+25.09%** |
| 3 (Monoclinic) | 6.90% | 9.26% | +2.36% |
| 4 (Orthogonal) | 0% | 2.33% | +2.33% |
| 5 (Rhombohedral) | 0% | 4.10% | +4.10% |

这是分类维度中偏差最大的一项。Cubic 相在真实数据中占 86.89%，但生成数据中仅 53.01%，差距达 34 个百分点。Tetragonal 相则从真实的 6.21% 膨胀到 31.29%。这可能是晶体相-掺杂耦合规则（规则 3）的参数设置需要进一步调优。

#### 7.3.3 数值维度详细分析

**log₁₀(Conductivity)（得分 0.7682，GOOD）**

| 统计量 | 真实数据 | 生成数据 | 差异 |
|--------|---------|---------|------|
| 样本量 | 1,351 | 113,960,665 | — |
| 均值 | -2.113 | -2.439 | -0.326 |
| 标准差 | 0.958 | 1.306 | +0.348 |
| 最小值 | -7.01 | -8.00 | -0.99 |
| P5 | -3.871 | -5.060 | -1.189 |
| P25 | -2.623 | -3.123 | -0.500 |
| **P50** | **-1.945** | **-2.177** | **-0.232** |
| P75 | -1.432 | -1.483 | -0.051 |
| P95 | -0.868 | -0.793 | +0.075 |
| Pct_RMSE | — | 0.0836 | — |
| Std_Ratio | — | 0.734 | — |

生成数据整体偏向低电导率（均值低 0.33 对数单位），且分布更宽（标准差大 0.35）。在高分位数区间（P75, P90, P95）匹配较好，但低分位数区间（P5, P10）偏差较大。

**Operating Temperature（得分 0.8740，GOOD）**

| 统计量 | 真实数据 | 生成数据 | 差异 |
|--------|---------|---------|------|
| 均值 | 726.0°C | 711.3°C | -14.7°C |
| 标准差 | 165.4°C | 187.6°C | +22.2°C |
| P50 | 700°C | 717°C | +17°C |
| P5 | 500°C | 389°C | -111°C |
| P95 | 1000°C | 1007°C | +7°C |
| Pct_RMSE | — | 0.0524 | — |

温度分布匹配良好，中位数仅差 17°C。低温端（P5）偏差较大（-111°C），说明生成数据在低温区域采样更广。

**Dopant Molar Fraction（得分 0.8084，GOOD）**

| 统计量 | 真实数据 | 生成数据 | 差异 |
|--------|---------|---------|------|
| 均值 | 0.0933 | 0.0609 | -0.032 |
| 标准差 | 0.866 | 0.042 | -0.824 |
| P50 | 0.060 | 0.053 | -0.007 |
| 最大值 | 35.0 | 0.2 | -34.8 |
| Std_Ratio | — | 0.048 | — |

真实数据标准差异常大（0.866），说明存在极端离群值（最大值达 35.0，远超正常掺杂范围）。生成数据严格遵循溶解度限制（max = 0.2），因此标准差比（Std_Ratio = 0.048）极低。不过这恰恰反映了生成数据在物理合理性上的严格控制——真实数据中的极端值可能是录入错误。中位数的匹配（0.060 vs 0.053）则非常接近。

**Sintering Temperature（得分 0.9071，EXCELLENT）**

| 统计量 | 真实数据 | 生成数据 | 差异 |
|--------|---------|---------|------|
| 均值 | 1417.6°C | 1425.7°C | +8.2°C |
| 标准差 | 147.2°C | 133.0°C | -14.1°C |
| P50 | 1400°C | 1450°C | +50°C |
| 最小值 | 425°C | 1000°C | +575°C |
| Pct_RMSE | — | 0.039 | — |

烧结温度匹配优秀。生成数据最低温为 1000°C（受烧结-合成方法耦合规则约束），而真实数据中有少量极低温记录（425°C）。整体分布的核心区间高度一致。

#### 7.3.4 相关性维度分析

**Dopant-Conductivity Correlation（得分 0.5029，POOR）**

这是唯一评级为 POOR 的维度。各元素平均 log₁₀(Conductivity) 对比：

| 元素 | 真实数据 | 生成数据 | 差异 |
|------|---------|---------|------|
| Sc | -1.538 | -1.308 | +0.230 |
| Y | -1.832 | -1.992 | -0.160 |
| Ca | -2.236 | -2.409 | -0.173 |
| Pr | -2.361 | -2.739 | -0.377 |
| Dy | -1.765 | -2.237 | -0.472 |
| Lu | -0.860 | -1.431 | -0.571 |
| Ce | -1.515 | -2.120 | -0.606 |
| Yb | -2.568 | -1.732 | **+0.837** |
| Bi | -1.487 | -2.639 | **-1.152** |

主要偏差来源：
- **Bi（铋）：** 生成数据电导率低 1.15 个对数单位，差异最大
- **Yb（镱）：** 生成数据电导率高 0.84 个对数单位，方向相反
- **Ce（铈）、Lu（镥）：** 偏差均超过 0.5 个对数单位

这表明各掺杂元素的基准电导率参数（`baseConductivity`）和活化能参数与真实实验数据存在系统性偏差，是后续优化的重点方向。

### 7.4 结果对比：原始数据 vs 合规数据

| 指标 | 原始数据 (ods_zirconia_rule_based) | 合规数据 (conductivity_compliant_rule_based) |
|------|-----------------------------------|---------------------------------------------|
| 样本量 | 113,968,301 | 113,960,665 |
| 过滤数量 | — | 7,636 条 (0.0067%) |
| HC 验证 | FAIL (HC-7: 7569, HC-8: 67) | **PASS** (全部 100%) |
| Fidelity 综合得分 | 0.8465 | 0.8465 |
| Fidelity 评级 | GOOD | GOOD |

结论：合规过滤仅移除了极少量样本（0.0067%），对保真度评分无可感知的影响，同时将所有硬约束通过率提升到 100%。

---

## 8. 数据管道与部署

### 8.1 执行顺序

```
1. 构建项目
   mvn clean package -DskipTests

2. 同步真实数据（MySQL → Hive）
   scripts/submit-mysql2hive.sh

3. 创建 Hive 外部表
   sql/hive/create_external_tables.sql            # 真实数据表
   sql/hive/create_external_tables_rule_based.sql  # 生成数据表

4. 生成合成数据
   scripts/submit.sh

5. 验证 + 合规过滤
   scripts/submit-data-validator.sh

6. 创建合规数据 Hive 表
   sql/hive/conductivity_compliant_rule_based.sql
```

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
| `ods_zirconia_rule_based` | 规则生成的原始数据 | data-generator-base-rule |
| `conductivity_compliant_rule_based` | 合规过滤后的数据 | data-validator 过滤输出 |
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
