# 氧化锆离子电导率数据增强系统 — 技术文档（修订版）

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 系统架构](#2-系统架构)
- [3. 数据模型与表结构](#3-数据模型与表结构)
  - [3.1 字典表](#31-字典表)
  - [3.2 真实数据 v2 与同步后 Hive 表](#32-真实数据-v2-与同步后-hive-表)
  - [3.3 规则生成数据 Hive 表](#33-规则生成数据-hive-表)
  - [3.4 结构差异与使用注意](#34-结构差异与使用注意)
- [4. 数据生成模块 (data-generator-base-rule)](#4-数据生成模块-data-generator-base-rule)
  - [4.1 生成架构](#41-生成架构)
  - [4.2 生成流程](#42-生成流程)
  - [4.3 14 条物理规则](#43-14-条物理规则)
  - [4.4 电导率计算模型](#44-电导率计算模型)
  - [4.5 掺杂元素属性表](#45-掺杂元素属性表)
  - [4.6 合成方法与加工工艺](#46-合成方法与加工工艺)
- [5. 数据同步模块 (data-sync-mysql2hive)](#5-数据同步模块-data-sync-mysql2hive)
- [6. 数据验证模块 (data-validator)](#6-数据验证模块-data-validator)
  - [6.1 硬约束验证 (HC)](#61-硬约束验证-hc)
  - [6.2 合规数据过滤](#62-合规数据过滤)
  - [6.3 保真度评估 (Fidelity)](#63-保真度评估-fidelity)
  - [6.4 HC 与 Fidelity 的关系](#64-hc-与-fidelity-的关系)
  - [6.5 运行方式与输出](#65-运行方式与输出)
- [7. 运行结果分析](#7-运行结果分析)
  - [7.1 验证运行概览](#71-验证运行概览)
  - [7.2 硬约束验证结果](#72-硬约束验证结果)
  - [7.3 保真度评估结果](#73-保真度评估结果)
  - [7.4 原始数据 vs 合规数据](#74-原始数据-vs-合规数据)
- [8. 数据管道与部署](#8-数据管道与部署)
- [9. 代码结构索引](#9-代码结构索引)

---

## 1. 项目概述

`material-conductivity-data-enhancer` 是一个基于 Apache Spark 的氧化锆（ZrO2）离子电导率数据增强项目，覆盖三类核心能力：

- 基于规则的大规模合成数据生成
- MySQL 真实实验数据向 HDFS Parquet 的同步
- 面向生成数据的硬约束校验与统计保真度评估

项目当前由 3 个 Maven 子模块组成：

- `data-generator-base-rule`
- `data-sync-mysql2hive`
- `data-validator`

系统目标不是简单“随机造数”，而是以材料科学约束为前提，批量生成可用于建模与分析的 ZrO2 固体电解质实验样本，并通过两层验证机制保证质量：

- `HC (Hard Constraints)`：检查数据是否合法、是否满足底线规则
- `Fidelity`：检查生成分布与真实实验数据是否相似

**技术栈**

| 组件 | 技术 |
|------|------|
| 计算引擎 | Apache Spark 3.5.8 |
| 编程语言 | Java 8 |
| 构建工具 | Maven |
| 数据交换/落盘 | Parquet |
| 真实数据源 | MySQL 8 |
| 分析与对比 | Spark SQL + Hive External Tables |

---

## 2. 系统架构

项目目录结构如下：

```text
material-conductivity-data-enhancer/
├── data-generator-base-rule/    # 基于物理规则的合成数据生成
├── data-sync-mysql2hive/        # MySQL -> HDFS Parquet 同步
├── data-validator/              # HC 校验 + Fidelity 评估 + 合规过滤
├── docs/                        # 文档
├── scripts/                     # spark-submit 脚本
├── sql/                         # MySQL / Hive DDL
└── pom.xml                      # 父 POM
```

完整数据流如下：

```text
┌──────────────────────┐
│ MySQL: zirconia_     │
│ conductivity_v2      │
└──────────┬───────────┘
           │ JDBC
           ▼
┌──────────────────────┐
│ data-sync-mysql2hive │
│ 7 张表写 HDFS        │
└──────────┬───────────┘
           │ Parquet
           ▼
┌──────────────────────────────┐
│ Hive: ods_zirconia_          │
│ conductivity_v2              │
│ 作为 Fidelity 真实基准       │
└──────────────────────────────┘

┌──────────────────────┐
│ data-generator-      │
│ base-rule            │
│ 直接生成并写 Hive 表 │
└──────────┬───────────┘
           ▼
┌──────────────────────────────┐
│ Hive: ods_zirconia_          │
│ rule_based                   │
│ 原始规则生成数据             │
└──────────┬───────────────────┘
           │
           ▼
┌──────────────────────┐
│ data-validator       │
│ HC / Fidelity /      │
│ compliant filter     │
└──────┬─────────┬─────┘
       │         │
       │         └──────────────────────────────────────┐
       ▼                                                ▼
┌──────────────────────┐                  ┌──────────────────────────────┐
│ /.../validation_run  │                  │ Hive: conductivity_          │
│ 及各类结果明细表     │                  │ compliant_rule_based         │
│ (Parquet)            │                  │ 合规过滤后的数据             │
└──────────────────────┘                  └──────────────────────────────┘
```

有两个容易混淆但很重要的点：

1. `data-sync-mysql2hive` 只负责把 MySQL 表写成 Parquet，不负责建 Hive 外表；真实数据 Hive 表需要单独执行 `sql/hive/create_external_tables.sql`。
2. `data-generator-base-rule` 会直接创建并写入 Hive 外部表；当前 HDFS 跑法下，不需要额外执行 `sql/hive/create_external_tables_rule_based.sql`。

---

## 3. 数据模型与表结构

### 3.1 字典表

三张字典表在真实数据侧和生成数据侧都存在，字段定义如下：

| 表名 | 关键字段 | 说明 |
|------|----------|------|
| `crystal_structure_dict` | `id`, `code`, `full_name` | 晶相字典，当前值为 `c/t/m/o/r/β` |
| `synthesis_method_dict` | `id`, `name` | 合成方法字典，9 个枚举值 |
| `processing_route_dict` | `id`, `name` | 加工工艺字典，19 个枚举值 |

### 3.2 真实数据 v2 与同步后 Hive 表

真实数据源数据库为 `zirconia_conductivity_v2`。该库中的真实实验数据来自仓库 [lanhung/material-conductivity-data-clean](https://github.com/lanhung/material-conductivity-data-clean)。`data-sync-mysql2hive` 会按原表结构读取，并将 7 张表写到 HDFS Parquet。

**material_samples**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sample_id` | INT | 样本主键 |
| `reference` | VARCHAR / STRING | 来源标识 |
| `material_source_and_purity` | TEXT / STRING | 材料来源与纯度描述 |
| `synthesis_method_id` | INT | 合成方法字典外键 |
| `processing_route_id` | INT | 加工工艺字典外键 |
| `operating_temperature` | FLOAT / DOUBLE | 测试温度 |
| `conductivity` | DOUBLE | 电导率 |

**sample_dopants**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INT | 子表自增主键，仅真实数据侧存在 |
| `sample_id` | INT | 关联主表 |
| `dopant_element` | VARCHAR / STRING | 掺杂元素 |
| `dopant_ionic_radius` | FLOAT / DOUBLE | 离子半径 |
| `dopant_valence` | INT | 价态 |
| `dopant_molar_fraction` | FLOAT / DOUBLE | 掺杂摩尔分数 |

**sintering_steps**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INT | 子表自增主键，仅真实数据侧存在 |
| `sample_id` | INT | 关联主表 |
| `step_order` | INT | 烧结步骤序号 |
| `sintering_temperature` | FLOAT / DOUBLE | 烧结温度 |
| `sintering_duration` | FLOAT / DOUBLE | 烧结时间 |

**sample_crystal_phases**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sample_id` | INT | 关联主表 |
| `crystal_id` | INT | 晶相字典外键 |
| `is_major_phase` | BOOLEAN | 是否主相 |

### 3.3 规则生成数据 Hive 表

生成模块写出的逻辑表仍然是 4 张主事实表，但和真实数据相比有两个关键区别：

- 四张事实表都额外带有 `recipe_group_id`
- 子表不再保留真实数据里的自增 `id`

生成侧关键字段如下：

**material_samples**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sample_id` | BIGINT | 样本唯一标识 |
| `recipe_group_id` | BIGINT | 配方组 ID |
| `reference` | STRING | 固定写为 `RULE_BASED_SYNTHETIC` |
| `material_source_and_purity` | STRING | 来源文本 |
| `synthesis_method_id` | INT | 合成方法字典外键 |
| `processing_route_id` | INT | 加工工艺字典外键 |
| `operating_temperature` | DOUBLE | 测试温度 |
| `conductivity` | DOUBLE | 电导率 |

**sample_dopants**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sample_id` | BIGINT | 关联主表 |
| `recipe_group_id` | BIGINT | 配方组 ID |
| `dopant_element` | STRING | 掺杂元素 |
| `dopant_ionic_radius` | DOUBLE | 离子半径 |
| `dopant_valence` | INT | 价态 |
| `dopant_molar_fraction` | DOUBLE | 掺杂摩尔分数 |

**sintering_steps**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sample_id` | BIGINT | 关联主表 |
| `recipe_group_id` | BIGINT | 配方组 ID |
| `step_order` | INT | 烧结步骤序号 |
| `sintering_temperature` | DOUBLE | 烧结温度 |
| `sintering_duration` | DOUBLE | 烧结时间 |

**sample_crystal_phases**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sample_id` | BIGINT | 关联主表 |
| `recipe_group_id` | BIGINT | 配方组 ID |
| `crystal_id` | INT | 晶相字典外键 |
| `is_major_phase` | BOOLEAN | 是否主相 |

生成侧 `sample_id` 编码规则为：

```text
sample_id = 10000001 + recipe_group_id * 8 + temperature_index
```

这里固定使用 `8` 作为每个配方组的 sample_id 块大小，即使实际温度点数少于 8。

### 3.4 结构差异与使用注意

- 验证器优先使用 `recipe_group_id` 来执行 HC-7；如果数据没有这个列，才尝试从 `sample_id` 推导。
- 真实数据侧 `sample_dopants` / `sintering_steps` 有 `id` 列；生成数据侧没有，验证与 Fidelity 也不依赖这些 `id`。
- `crystal_structure_dict` 在代码里通过 `code` 判断 `c` / `m` 等晶相，不是通过 `full_name` 判断。

---

## 4. 数据生成模块 (data-generator-base-rule)

### 4.1 生成架构

生成单位不是单条样本，而是**配方组（recipe group）**。

- 一个配方组代表一套固定配方：掺杂组成、晶相、烧结条件、来源描述
- 同一配方组会生成多个测试温度点
- 配方组内样本共享掺杂/晶相/烧结信息，只在 `operating_temperature` 与 `conductivity` 上变化

默认参数来自 `AppConfig`：

| 参数 | 默认值 |
|------|--------|
| `totalRecipeGroups` | 25,000,000 |
| `numPartitions` | 1,000 |
| `outputPath` | `./output` |
| `hiveDatabase` | `ods_zirconia_rule_based` |

当前提交脚本 `scripts/submit.sh` 使用的是：

| 参数 | 实际值 |
|------|--------|
| 配方组数 | 28,000,000 |
| 分区数 | 2,000 |
| 输出根目录 | `/data/material_conductivity_data` |
| Hive 数据库 | `ods_zirconia_rule_based` |

温度点数分布为 `3/4/5/6/7/8 = 40%/30%/20%/5%/3%/2%`，理论平均约 `4.07` 个温度点/组。以 28,000,000 个配方组计算，期望样本量约为 `113.96M`，和实际运行结果 `113,968,301` 条一致。

### 4.2 生成流程

生成流程固定分为 6 步：

```text
Step 1: 生成掺杂剂
  - 按权重抽样 1~5 个元素
  - 主掺杂用元素自己的 Beta 参数采样
  - 共掺杂用 Beta(2,5) * 0.08 采样
  - 施加单元素固溶度上限和总掺杂量上限

Step 2: 生成晶相
  - 根据主掺杂和总掺杂量选择主相
  - 按规则追加第二相/第三相
  - 对禁止的单相组合做硬修正

Step 3: 生成烧结步骤
  - RF sputtering 直接无烧结
  - 其余样本按 1/2/3 步分布生成
  - 范围由 synthesis_method 或 SPS 路线决定

Step 4: 生成温度序列
  - 先从离散温度分布中采中心点
  - 再按 50~100°C 间距扩展
  - 排序、去重、必要时回退到等间隔序列

Step 5: 生成电导率
  - 以主掺杂属性为核心建立分段 Arrhenius 模型
  - 叠加晶相、烧结、共掺杂、ScSZ、过渡金属等修正
  - 截断到 [1e-8, 1.0] 后再做严格单调修正

Step 6: 生成来源文本
  - 根据 synthesis_method 和 dopants 生成模板化描述
```

### 4.3 14 条物理规则

#### 规则 1：Arrhenius 温度依赖性

代码采用**分段 Arrhenius**模型：

- 分界点：`600°C`
- 参考温度：`800°C`
- 玻尔兹曼常数：`8.617333e-5 eV/K`

高温段和低温段分别使用不同的活化能 `EaHigh` / `EaLow`，并通过 `sigma0High` / `sigma0Low` 保证分段连续。

#### 规则 2：离子半径与价态固定映射

每个掺杂元素的以下属性都来自固定查表，不在运行时自由漂移：

- `radius`
- `valence`
- `optimalFraction`
- `maxSolubility`
- `eaHighMin` / `eaHighMax`
- `eaLowMin` / `eaLowMax`
- `baseSigmaLog10`

#### 规则 3：晶相-掺杂耦合

主相主要由**主掺杂元素**和**总掺杂量**决定：

- `Sc` 且 `Sc >= 0.10`：使用 `PHASE_DIST_SC_HIGH`
- `Sc` 且 `Sc < 0.08`：使用 `PHASE_DIST_SC_LOW`
- 其他样本：
  - `totalFrac >= 0.10` 且主掺杂价态为 `+2/+3`：`PHASE_DIST_HIGH_DOPING`
  - `0.05 <= totalFrac < 0.10`：`PHASE_DIST_MED_DOPING`
  - `totalFrac < 0.05`：`PHASE_DIST_LOW_DOPING`

禁止组合的硬修正只覆盖两类单相情况：

- `totalFrac < 0.05` 且单相 `cubic`：改成单相 `tetragonal`
- `totalFrac > 0.12` 且单相 `monoclinic`：改成单相 `cubic`

#### 规则 4：至少包含一个 +2 或 +3 掺杂元素

掺杂剂生成会最多尝试 50 次；若 50 次都没采到 `+2` 或 `+3` 元素，则回退到默认元素 `Y`。

#### 规则 5：元素固溶度上限

每种元素都有独立的 `maxSolubility`。掺杂分数在落表前会先被裁剪到：

```text
[MIN_MOLAR_FRACTION, maxSolubility]
```

#### 规则 6：总掺杂量限制

总掺杂量上限固定为：

```text
SUM(dopant_molar_fraction) <= 0.30
```

若新采样的某个元素会使总量超限，只保留“剩余可用空间”对应的分数。

#### 规则 7：浓度非单调性

代码通过主掺杂偏离最优浓度的程度来提高活化能：

```text
concDeviation = |primaryFraction - optimalFraction|
eaPenalty = concDeviation * 2.0
```

偏离越远，`EaHigh` / `EaLow` 越高，等价于电导率下降。

#### 规则 8：元素类型与电导率参数相关

不同元素使用不同的：

- 高温活化能范围
- 低温活化能范围
- 800°C 基准 `log10(conductivity)` 值

这部分不是显式使用“半径函数”，而是通过 `DopantProperty` 中预设参数间接表达。

#### 规则 9：烧结-工艺耦合

烧结温度/时长范围由合成方法或工艺路线决定：

| 条件 | 温度范围 (°C) | 时间范围 (min) |
|------|----------------|----------------|
| `Solid-state synthesis` | 1400–1650 | 120–600 |
| `Commercialization` | 1300–1550 | 60–300 |
| `Sol–gel method` | 1200–1500 | 60–360 |
| `Coprecipitation` / `Coprecipitation method` | 1200–1500 | 60–360 |
| `Hydrothermal synthesis` | 1200–1500 | 60–360 |
| `Glycine method` | 1200–1500 | 60–360 |
| `Directional melt crystallization` | 1300–1550 | 60–360 |
| `spark plasma sintering` 路线 | 1000–1300 | 3–30 |
| 其他默认值 | 1300–1550 | 60–360 |

#### 规则 10：晶界效应

若 `maxSinteringTemp < 1300°C`，会对 `sigmaRefLog10` 施加额外惩罚：

```text
0.1 + (1300 - maxSinteringTemp) / 1000 * 0.3
```

#### 规则 11：ScSZ 低温退化

若满足：

- 主掺杂元素为 `Sc`
- `Sc < 0.10`
- `T < 650°C`

则对该温度点电导率乘以 `0.2 ~ 0.5` 的随机因子。

#### 规则 12：共掺杂非加和性

当掺杂元素数 `>= 3` 时，不是简单乘一个固定 5% 因子，而是对 `sigmaRefLog10` 施加附加惩罚：

```text
0.05 + (numDopants - 2) * 0.05
```

对应关系如下：

- 3 元掺杂：`-0.10`
- 4 元掺杂：`-0.15`
- 5 元掺杂：`-0.20`

#### 规则 13：过渡金属增益

若掺杂中包含 `Fe` 或 `Mn`，则对 `sigmaRefLog10` 加上 `0.04 ~ 0.14` 的随机增益。

#### 规则 14：RF sputtering 薄膜特殊性

若 `processing_route = RF sputtering`：

- 不生成烧结步骤
- 温度序列中心温度会被压到 `<= 800°C`

### 4.4 电导率计算模型

代码里的电导率生成不是简单一条公式，而是三层组合：

1. 先确定主掺杂相关的参考参数
2. 再用分段 Arrhenius 公式推导各温度点电导率
3. 最后在裁剪后做严格单调修正

**第一层：800°C 参考电导率**

```text
sigmaRefLog10
  = baseSigmaLog10
  - 15 * concDeviation^2
  + phaseAdjustment
  - grainBoundaryPenalty
  - codopingPenalty
  + transitionMetalBonus
  + N(0, 0.15)
```

相位修正为：

| 主相 | 修正 |
|------|------|
| Cubic | 0 |
| Tetragonal | -0.3 |
| Monoclinic | -1.0 |
| Rhombohedral | -0.5 |
| Orthogonal | -0.8 |
| 其他 | -0.5 |

然后：

```text
sigmaRef = 10^(sigmaRefLog10)
```

**第二层：分段 Arrhenius**

```text
sigma0High = sigmaRef * Tref * exp(EaHigh / (kB * Tref))
sigma0Low  = sigma0High * exp((EaLow - EaHigh) / (kB * Tboundary))
sigma(T)   = (sigma0 / T) * exp(-Ea / (kB * T))
```

其中：

- `Tref = 1073.15 K`（800°C）
- `Tboundary = 873.15 K`（600°C）
- `T >= 600°C` 用高温段参数，否则用低温段参数

这等价于“以 800°C 为参考点的分段 Arrhenius 曲线”，并显式保留了 `1/T` 项。

**第三层：低温退化、噪声、裁剪与单调修正**

对每个温度点：

1. 如果满足 ScSZ 低温退化条件，乘 `0.2~0.5`
2. 在 `log10(sigma)` 上加 `N(0, 0.05)` 噪声
3. 裁剪到 `[1e-8, 1.0]`
4. 若裁剪后出现 `conductivity[i] <= conductivity[i-1]`，则把当前点抬高到前一点的 `1.01 ~ 1.06` 倍，但仍然上限不超过 `1.0`

因此，HC-7 检查的是**最终落表后的严格单调性**，而不是理论模型里的未裁剪曲线。

### 4.5 掺杂元素属性表

当前代码支持 20 种掺杂元素，属性如下：

| 元素 | 半径 (pm) | 价态 | 频率 | 最优分数 | 最大固溶度 | Beta(α/β/scale) | `EaHigh` (eV) | `EaLow` (eV) | `baseSigmaLog10` |
|------|-----------|------|------|----------|------------|------------------|----------------|---------------|------------------|
| Y | 101.9 | +3 | 0.380 | 0.08 | 0.25 | 3.0 / 4.0 / 0.20 | 0.90–1.05 | 1.10–1.25 | -1.70 |
| Sc | 87.0 | +3 | 0.330 | 0.09 | 0.12 | 4.0 / 3.0 / 0.15 | 0.78–0.85 | 1.05–1.15 | -1.00 |
| Yb | 98.5 | +3 | 0.090 | 0.08 | 0.20 | 4.0 / 3.0 / 0.18 | 0.85–0.95 | 1.05–1.20 | -1.50 |
| Ce | 105.3 | +4 | 0.050 | 0.10 | 0.18 | 4.0 / 3.0 / 0.20 | 0.95–1.10 | 1.10–1.25 | -1.80 |
| Dy | 102.7 | +3 | 0.030 | 0.08 | 0.20 | 4.0 / 3.0 / 0.18 | 0.95–1.10 | 1.10–1.30 | -2.00 |
| Bi | 96.0 | +3 | 0.030 | 0.05 | 0.15 | 3.0 / 4.0 / 0.12 | 1.00–1.15 | 1.15–1.30 | -2.20 |
| Gd | 97.0 | +3 | 0.020 | 0.08 | 0.20 | 4.0 / 3.0 / 0.18 | 0.95–1.10 | 1.10–1.30 | -2.00 |
| Er | 100.4 | +3 | 0.015 | 0.08 | 0.20 | 4.0 / 3.0 / 0.18 | 0.90–1.05 | 1.10–1.25 | -1.80 |
| Lu | 97.7 | +3 | 0.010 | 0.08 | 0.20 | 4.0 / 3.0 / 0.18 | 0.82–0.90 | 1.05–1.18 | -1.20 |
| Pr | 112.6 | +3 | 0.010 | 0.05 | 0.15 | 3.0 / 4.0 / 0.12 | 1.00–1.15 | 1.15–1.30 | -2.30 |
| Ca | 112.0 | +2 | 0.010 | 0.12 | 0.20 | 3.0 / 2.0 / 0.25 | 1.00–1.20 | 1.15–1.35 | -2.20 |
| Fe | 64.5 | +3 | 0.005 | 0.02 | 0.05 | 3.0 / 5.0 / 0.06 | 1.00–1.15 | 1.15–1.30 | -2.00 |
| Mn | 83.0 | +2 | 0.005 | 0.01 | 0.05 | 3.0 / 5.0 / 0.04 | 1.00–1.15 | 1.10–1.30 | -2.00 |
| Zn | 74.0 | +2 | 0.005 | 0.02 | 0.05 | 3.0 / 5.0 / 0.06 | 1.05–1.20 | 1.15–1.35 | -2.50 |
| Al | 53.5 | +3 | 0.005 | 0.01 | 0.03 | 3.0 / 6.0 / 0.04 | 1.05–1.20 | 1.20–1.35 | -2.50 |
| In | 80.0 | +3 | 0.003 | 0.05 | 0.10 | 3.0 / 4.0 / 0.12 | 0.95–1.10 | 1.10–1.30 | -2.00 |
| Eu | 106.6 | +3 | 0.003 | 0.05 | 0.15 | 3.0 / 4.0 / 0.12 | 0.95–1.10 | 1.10–1.30 | -2.10 |
| Si | 40.0 | +4 | 0.002 | 0.01 | 0.02 | 3.0 / 6.0 / 0.03 | 1.10–1.25 | 1.20–1.40 | -3.00 |
| Nb | 64.0 | +5 | 0.002 | 0.02 | 0.05 | 3.0 / 5.0 / 0.06 | 1.10–1.25 | 1.20–1.40 | -2.80 |
| Ti | 60.5 | +4 | 0.002 | 0.02 | 0.05 | 3.0 / 5.0 / 0.06 | 1.05–1.20 | 1.15–1.35 | -2.50 |

### 4.6 合成方法与加工工艺

**字典表支持的 9 种合成方法**

| id | name |
|----|------|
| 1 | `/` |
| 2 | `Commercialization` |
| 3 | `Coprecipitation` |
| 4 | `Coprecipitation method` |
| 5 | `Directional melt crystallization` |
| 6 | `Glycine method` |
| 7 | `Hydrothermal synthesis` |
| 8 | `Sol–gel method` |
| 9 | `Solid-state synthesis` |

**当前生成器实际采样权重**

| 方法 | 权重 |
|------|------|
| Solid-state synthesis | 45.6% |
| Commercialization | 20.0% |
| Sol–gel method | 12.0% |
| Coprecipitation method | 10.0% |
| Hydrothermal synthesis | 5.0% |
| Glycine method | 3.0% |
| Coprecipitation | 2.0% |
| Directional melt crystallization | 1.4% |
| / | 1.0% |

**字典表支持的 19 种加工工艺**

`/`, `3D printing`, `chemical vapor deposition`, `cutting and polishing`, `dry pressing`, `isostatic pressing`, `magnetron sputtering`, `metal-organic chemical vapor deposition`, `plasma spray deposition`, `pulsed laser deposition`, `RF sputtering`, `spark plasma sintering`, `spin coating`, `spray pyrolysis`, `tape casting`, `ultrasonic atomization`, `ultrasonic spray pyrolysis`, `vacuum filtration`, `vapor deposition`

**当前生成器实际采样的只有 6 种工艺**

| 工艺 | 权重 |
|------|------|
| dry pressing | 80.4% |
| spark plasma sintering | 8.0% |
| RF sputtering | 3.0% |
| tape casting | 3.0% |
| isostatic pressing | 3.0% |
| spray pyrolysis | 2.6% |

这也是后续 Fidelity 中 `Processing Route` 维度出现“很多真实类别在生成数据中缺失”的直接原因。

---

## 5. 数据同步模块 (data-sync-mysql2hive)

`data-sync-mysql2hive` 的职责很单纯：通过 JDBC 读取 MySQL 表，并按表名写到 HDFS Parquet。

### 5.1 功能说明

- 输入：MySQL `zirconia_conductivity_v2`
- 真实数据来源：`https://github.com/lanhung/material-conductivity-data-clean`
- 输出：`<hdfsOutputPath>/<tableName>`
- 写入模式：`Overwrite`
- 输出格式：Parquet

### 5.2 同步表

固定同步 7 张表：

1. `crystal_structure_dict`
2. `synthesis_method_dict`
3. `processing_route_dict`
4. `material_samples`
5. `sample_dopants`
6. `sintering_steps`
7. `sample_crystal_phases`

### 5.3 命令行参数

程序参数格式为：

```bash
spark-submit \
  --class com.lanhung.conductivity.sync.MysqlToHiveSyncApp \
  data-sync-mysql2hive/target/data-sync-mysql2hive-1.0-SNAPSHOT.jar \
  jdbc:mysql://<host>:<port>/zirconia_conductivity_v2 \
  <user> \
  <password> \
  [hdfs_output_path]
```

其中：

- 前 3 个参数必填：`mysql_url`、`mysql_user`、`mysql_password`
- 第 4 个参数可选：`hdfs_output_path`
- 若省略输出路径，默认值为 `/data/material_conductivity_data/ods_zirconia_conductivity_v2`

### 5.4 和 Hive 的关系

该模块**不创建 Hive 表**。如果希望通过 Hive/ Spark SQL 访问同步结果，需要额外执行：

```text
sql/hive/create_external_tables.sql
```

---

## 6. 数据验证模块 (data-validator)

验证模块提供 3 类能力：

- `HC`：硬约束校验
- `Fidelity`：统计保真度评估
- `compliant filter`：从原始生成数据中过滤出合规数据集

### 6.1 硬约束验证 (HC)

当前实现共有 13 个检查项：

| 编号 | 检查内容 | 口径 |
|------|----------|------|
| HC-1 | 所有 `conductivity > 0` | 明细级校验 |
| HC-2a | 所有 `dopant_molar_fraction > 0` | 明细级校验 |
| HC-2b | 元素固溶度上限 | 只检查 `Sc/Ce/Y/Ca` 四个元素 |
| HC-3 | 总掺杂量不超过 0.30 | 实现阈值为 `0.3005` |
| HC-4 | 每个样本至少有一个 `+2/+3` 掺杂元素 | 分组校验 |
| HC-5 | 每个样本恰好一个主相 | 分组校验 |
| HC-6a | `sample_dopants.sample_id` 都能在主表中找到 | 引用完整性 |
| HC-6b | `sintering_steps.sample_id` 都能在主表中找到 | 引用完整性 |
| HC-6c | `sample_crystal_phases.sample_id` 都能在主表中找到 | 引用完整性 |
| HC-7 | 同一配方组内温度升高时电导率严格升高 | 使用 `recipe_group_id` 优先 |
| HC-8 | 晶相-掺杂禁配 | 只检查“低掺杂纯立方”和“高掺杂纯单斜” |
| HC-9 | `conductivity` 在 `[1e-8, 1.0]` | 范围校验 |
| HC-10 | `operating_temperature` 在 `[300, 1400]` | 范围校验 |

判定规则很简单：

- 单项 `violation_count = 0` 时视为通过
- 整轮 HC 只有在所有检查项都通过时才视为通过

`validation_run` 表里，`HARD_CONSTRAINT` 类型的 `overall_score` 不是独立评分模型，而是**所有 HC 项里最小的 pass rate**。因此原始数据这轮 HC 的 `99.99335867962093`，本质上就是 HC-7 的通过率，而不是“另一套 100 分制评分”。

### 6.2 合规数据过滤

`--compliant-output-path` 打开后，验证器会额外执行合规过滤：

1. 收集违反 HC-1 / 2a / 2b / 3 / 4 / 5 / 7 / 8 / 9 / 10 的 `sample_id`
2. 通过 `LEFT ANTI JOIN` 移除这些样本
3. 对 HC-7 额外做迭代收敛

HC-7 之所以要迭代，是因为删除某个温度点后，同一配方组中剩余点之间可能出现新的 `conductivity <= prev_conductivity` 关系。代码会持续删除新增 HC-7 违规样本，直到没有新的违规为止。

过滤后的 4 张事实表和可用字典表会被重写到：

```text
<compliant-output-path>/
  ├── material_samples
  ├── sample_dopants
  ├── sintering_steps
  ├── sample_crystal_phases
  ├── synthesis_method_dict   (若存在)
  ├── processing_route_dict   (若存在)
  └── crystal_structure_dict  (若存在)
```

### 6.3 保真度评估 (Fidelity)

保真度评估用于回答“生成数据整体像不像真实数据”，它依赖：

```bash
--real-database <hiveDatabase>
```

真实数据不是从本地 TSV 目录直接读取，而是从指定 Hive 数据库中读取 4 张真实表。

#### 评估维度与权重

| 维度 | 权重 | 类型 |
|------|------|------|
| Synthesis Method | 15% | 类别 |
| Processing Route | 10% | 类别 |
| Dopant Element | 15% | 类别 |
| Crystal Phase (Major) | 10% | 类别 |
| log10(Conductivity) | 20% | 数值 |
| Operating Temperature | 10% | 数值 |
| Dopant Molar Fraction | 10% | 数值 |
| Sintering Temperature | 5% | 数值 |
| Dopant-Conductivity Correlation | 5% | 联合分布 |

#### 类别维度评分

类别维度使用 Jensen-Shannon 散度，代码中的相似度定义为：

```text
JSD(P, Q) = 0.5 * KL(P || M) + 0.5 * KL(Q || M)
M = (P + Q) / 2
normalizedJsd = JSD / ln(2)
similarity = 1 - normalizedJsd
```

实现细节：

- `Synthesis Method` / `Processing Route` 会优先通过字典表把 ID 映射成名称
- 如果字典表缺失，则退化为比较 ID 的字符串值
- `Crystal Phase (Major)` 当前比较的是主相 `crystal_id` 的字符串，而不是 `full_name`

#### 数值维度评分

数值维度使用 7 个分位点（P5/P10/P25/P50/P75/P90/P95）+ 均值 + 标准差的复合评分：

```text
range = max(|realMax - realMin|, 1e-10)
pctRmse = sqrt(avg(((genPct - realPct) / range)^2))
meanDiff = |genMean - realMean| / range
stdRatio = min(genStd, realStd) / max(genStd, realStd)

similarity =
    0.6 * max(0, 1 - min(pctRmse * 3, 1))
  + 0.2 * max(0, 1 - min(meanDiff * 3, 1))
  + 0.2 * stdRatio
```

注意：

- 文档中不能把它简化成未缩放版 `0.6*(1-pctRmse)+...`，那和实际代码不一致
- `Pct_RMSE` 与 `Std_Ratio` 会直接作为统计量写入 `fidelity_numerical`

#### 联合分布评分

联合分布维度比较“主掺杂元素 vs 平均 `log10(conductivity)`”的对应关系。

筛选口径：

1. 每个样本选摩尔分数最高的掺杂元素作为主掺杂
2. 温度窗口限定在 `700~900°C`
3. 要求 `conductivity > 0`
4. 每个元素至少有 3 个样本

评分公式为：

```text
corrScore = max(0, (pearson + 1) / 2)
rmseScore = max(0, 1 - normalizedRmse * 2)
similarity = 0.5 * corrScore + 0.5 * rmseScore
```

这里不是 `|pearson|`；负相关会明确拉低得分。

#### 综合评级

| 分数区间 | 评级 |
|----------|------|
| `>= 0.90` | `EXCELLENT` |
| `>= 0.75` | `GOOD` |
| `>= 0.60` | `FAIR` |
| `< 0.60` | `POOR` |

### 6.4 HC 与 Fidelity 的关系

这两类验证互相独立：

- `HC` 关注合法性和规则底线
- `Fidelity` 关注统计相似性

因此以下情况都可能出现：

| HC | Fidelity | 含义 |
|----|----------|------|
| PASS | GOOD / EXCELLENT | 数据既合法也接近真实分布 |
| PASS | FAIR / POOR | 数据合法，但分布不够像真实数据 |
| FAIL | GOOD | 整体分布接近真实，但仍存在规则违规样本 |
| FAIL | POOR | 同时存在规则违规和分布偏差 |

### 6.5 运行方式与输出

#### 常见运行方式

**仅跑 HC（仍然必须提供 `--output-path`）**

```bash
spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based
```

**HC + Fidelity**

```bash
spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based
```

**HC + Fidelity + 合规过滤**

```bash
spark-submit \
  --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator/target/data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate \
  --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based \
  --compliant-output-path /data/material_conductivity_data/conductivity_compliant_rule_based
```

如果既不传 `--validate` 也不传 `--fidelity`，程序会默认执行 `HC`；但 `--output-path` 依然是必填项。

#### 输出目录

`--output-path` 下会以 Parquet 目录形式追加写入以下结果：

| 目录 | 内容 |
|------|------|
| `validation_run` | 每次运行一条汇总记录 |
| `hard_constraint_result` | 每个 HC 检查项一条记录 |
| `fidelity_summary` | 每个 Fidelity 维度一条记录 |
| `fidelity_categorical` | 类别维度的真实占比 / 生成占比 |
| `fidelity_numerical` | 数值维度的统计量与差值 |
| `fidelity_correlation` | 元素级平均 `log10(conductivity)` 对比 |

`validation_run` 中：

- `run_type` 取值为 `HARD_CONSTRAINT` 或 `FIDELITY`
- `HARD_CONSTRAINT` 行的 `passed` 有值，`overall_grade` 为空
- `FIDELITY` 行的 `overall_grade` 有值，`passed` 为空

---

## 7. 运行结果分析

以下分析基于 `2026-03-18` 的实际运行结果，结果文件位于：

```text
/home/zxc/Desktop/run_result
```

这些 TSV 对应的是验证输出 Parquet 表的导出视图。

### 7.1 验证运行概览

共 4 条运行记录，对应两轮数据库验证：

| run_id | 类型 | 数据库 | 总样本数 | 结果 | overall_score | run_at |
|--------|------|--------|----------|------|---------------|--------|
| 1773800129257 | HARD_CONSTRAINT | `ods_zirconia_rule_based` | 113,968,301 | FAIL | 99.99335867962093 | 2026-03-18 10:15:29 |
| 1773801307603 | FIDELITY | `ods_zirconia_rule_based` | 113,968,301 | GOOD | 0.846548985171353 | 2026-03-18 10:35:07 |
| 1773802503281 | HARD_CONSTRAINT | `conductivity_compliant_rule_based` | 113,960,665 | PASS | 100.0 | 2026-03-18 10:55:03 |
| 1773802681046 | FIDELITY | `conductivity_compliant_rule_based` | 113,960,665 | GOOD | 0.8465469724349198 | 2026-03-18 10:58:01 |

**关键结论**

- 原始规则生成数据 HC 未通过，合规过滤后 HC 全部通过
- 合规过滤后样本量减少 `7,636` 条，占原始数据的约 `0.0067%`
- 两轮 Fidelity 总分仅相差约 `2.0e-6`，说明被过滤样本对整体统计分布几乎没有影响

### 7.2 硬约束验证结果

#### 合规数据集：全部通过

| 约束 | 检查总量 | 违规数 | 通过率 |
|------|----------|--------|--------|
| HC-1 | 113,960,665 | 0 | 100% |
| HC-2a | 190,961,722 | 0 | 100% |
| HC-2b | 190,961,722 | 0 | 100% |
| HC-3 | 113,960,665 | 0 | 100% |
| HC-4 | 113,960,665 | 0 | 100% |
| HC-5 | 113,960,665 | 0 | 100% |
| HC-6a | 113,960,665 | 0 | 100% |
| HC-6b | 110,543,958 | 0 | 100% |
| HC-6c | 113,960,665 | 0 | 100% |
| HC-7 | 113,960,665 | 0 | 100% |
| HC-8 | 113,960,665 | 0 | 100% |
| HC-9 | 113,960,665 | 0 | 100% |
| HC-10 | 113,960,665 | 0 | 100% |

#### 原始数据集：两项失败

| 约束 | 违规数 | 通过率 |
|------|--------|--------|
| HC-7 | 7,569 | 99.99335867962093% |
| HC-8 | 67 | 99.99994121172342% |
| 其余 11 项 | 0 | 100% |

#### 结果解读

- `HC-7` 说明原始结果集中仍存在“同一配方组内相邻温度点 `conductivity <= prev_conductivity`”的样本。代码已经在生成端做了单调修正，因此这里更适合表述为“仍有落表后的严格单调性违规”，而不宜直接写成某一种唯一根因。
- 结合实现，**上限截断到 `1.0` 后形成平顶值**是一个值得优先核查的方向，但这属于推测，需要结合违规 `sample_id` 回查。
- `HC-8` 的 67 条记录表示仍有少量样本触发了验证器定义的两类单相禁配规则；结果文件本身只能说明“存在边界样本”，不能单独定位到生成逻辑中的唯一失配点。

### 7.3 保真度评估结果

以下以合规数据集 `run_id = 1773802681046` 为主。

#### 7.3.1 各维度得分总览

| 维度 | 得分 | 权重 | 加权得分 | 评级 |
|------|------|------|---------|------|
| Dopant Element | 0.9835843887491135 | 15% | 0.147537658312367 | EXCELLENT |
| Sintering Temperature | 0.9070680363507568 | 5% | 0.045353401817537844 | EXCELLENT |
| Synthesis Method | 0.8825221129602756 | 15% | 0.13237831694404134 | GOOD |
| Operating Temperature | 0.8739760564322571 | 10% | 0.08739760564322571 | GOOD |
| Processing Route | 0.8718721676437372 | 10% | 0.08718721676437373 | GOOD |
| Crystal Phase (Major) | 0.8705712805578834 | 10% | 0.08705712805578834 | GOOD |
| Dopant Molar Fraction | 0.8084147878644956 | 10% | 0.08084147878644957 | GOOD |
| log10(Conductivity) | 0.7682388115930227 | 20% | 0.15364776231860455 | GOOD |
| Dopant-Conductivity Correlation | 0.5029280758506336 | 5% | 0.02514640379253168 | POOR |
| **综合得分** |  |  | **0.8465469724349198** | **GOOD** |

整体评级分布为：

- 2 个 `EXCELLENT`
- 6 个 `GOOD`
- 1 个 `POOR`

#### 7.3.2 分类维度分析

**Dopant Element：最稳定的维度**

| 元素 | 真实占比 | 生成占比 | 差值 |
|------|---------|---------|------|
| Y | 37.9022% | 34.0950% | -3.8071% |
| Sc | 32.9220% | 31.1544% | -1.7676% |
| Yb | 8.7704% | 10.5989% | +1.8285% |
| Ce | 7.0075% | 4.6839% | -2.3236% |
| Dy | 3.9665% | 3.7283% | -0.2382% |
| Bi | 3.1732% | 3.7215% | +0.5483% |

这一维度得分很高，说明当前掺杂元素频率权重已经比较接近真实数据。

**Synthesis Method：枚举体系与权重共同造成偏差**

| 方法 | 真实占比 | 生成占比 | 差值 |
|------|---------|---------|------|
| Solid-state synthesis | 45.3738% | 45.6001% | +0.2263% |
| Commercialization | 19.9112% | 19.9984% | +0.0872% |
| Hydrothermal synthesis | 17.4685% | 4.9986% | -12.4699% |
| Sol–gel method | 8.8823% | 11.9931% | +3.1108% |
| Coprecipitation method | 0% | 10.0086% | +10.0086% |
| / | 2.1466% | 0.9981% | -1.1485% |

这里的偏差和生成器当前枚举/权重完全一致：

- `Coprecipitation method` 在生成器中固定占 `10%`
- `Coprecipitation` 还额外占 `2%`

如果真实基准里这两个类别不存在或命名体系不同，JSD 会明显上升。

**Processing Route：当前生成器只采样 6 种工艺**

几个代表性偏差如下：

| 工艺 | 真实占比 | 生成占比 | 差值 |
|------|---------|---------|------|
| dry pressing | 80.0148% | 80.3931% | +0.3783% |
| 3D printing | 6.7358% | 0% | -6.7358% |
| vacuum filtration | 2.3686% | 0% | -2.3686% |
| RF sputtering | 0.1480% | 2.9981% | +2.8501% |
| spray pyrolysis | 0.0740% | 2.6012% | +2.5272% |
| spark plasma sintering | 0.0740% | 8.0023% | +7.9283% |

这个结果和代码完全一致：字典表虽然支持 19 个工艺，但生成分布只实际采样 6 个，因此很多真实类别必然缺失。

**Crystal Phase (Major)：当前相分布明显偏离真实数据**

| 晶相 | 真实占比 | 生成占比 | 差值 |
|------|---------|---------|------|
| 1 (Cubic) | 86.8891% | 53.0087% | -33.8804% |
| 2 (Tetragonal) | 6.2064% | 31.2968% | +25.0904% |
| 3 (Monoclinic) | 6.9046% | 9.2642% | +2.3596% |
| 4 (Orthogonal) | 0% | 2.3301% | +2.3301% |
| 5 (Rhombohedral) | 0% | 4.1003% | +4.1003% |

这说明当前 `PHASE_DIST_*` 参数更偏向生成 `tetragonal / orthogonal / rhombohedral`，而真实数据显著偏向 `cubic`。

#### 7.3.3 数值维度分析

**log10(Conductivity)**

| 统计量 | 真实数据 | 生成数据 | 差值 |
|--------|---------|---------|------|
| Count | 1,351 | 113,960,665 | — |
| Mean | -2.1133 | -2.4391 | -0.3257 |
| Std | 0.9584 | 1.3061 | +0.3477 |
| Min | -7.01 | -8.00 | -0.99 |
| P5 | -3.8711 | -5.0597 | -1.1886 |
| P50 | -1.9452 | -2.1767 | -0.2315 |
| P95 | -0.8675 | -0.7925 | +0.0750 |
| Pct_RMSE | 0.083643 | 0.083643 | — |
| Std_Ratio | 0.733785 | 0.733785 | — |

结论：

- 生成数据整体偏向更低的电导率
- 分布比真实数据更宽
- 高分位区间相对接近，低分位区间偏差更明显

**Operating Temperature**

| 统计量 | 真实数据 | 生成数据 | 差值 |
|--------|---------|---------|------|
| Mean | 726.0264 | 711.3070 | -14.7194 |
| Std | 165.3772 | 187.5754 | +22.1982 |
| Min | 290 | 300 | +10 |
| P5 | 500 | 389 | -111 |
| P50 | 700 | 717 | +17 |
| P95 | 1000 | 1007 | +7 |
| Max | 1400 | 1363 | -37 |

这一维度整体表现不错。生成端温度下限被硬裁剪到 300°C，因此真实数据里的 `290°C` 不会出现。

**Dopant Molar Fraction**

| 统计量 | 真实数据 | 生成数据 | 差值 |
|--------|---------|---------|------|
| Mean | 0.0932518 | 0.0608535 | -0.0323983 |
| Std | 0.8658486 | 0.0418967 | -0.8239519 |
| Min | 0.001 | 0.001 | 0 |
| P50 | 0.06 | 0.0527 | -0.0073 |
| P95 | 0.11 | 0.1337 | +0.0237 |
| Max | 35.0 | 0.2 | -34.8 |
| Std_Ratio | 0.0483880 | 0.0483880 | — |

这里最需要谨慎解读：

- 生成端严格受固溶度与总掺杂量约束，最大值只有 `0.2`
- 真实数据侧存在 `35.0` 这样的极端值，导致标准差显著放大

是否把这些极端值视为录入问题，不能仅凭结果文件下定论；更稳妥的表述是：**真实数据侧的数值口径和生成端物理约束口径并不一致，且包含明显离群点。**

**Sintering Temperature**

| 统计量 | 真实数据 | 生成数据 | 差值 |
|--------|---------|---------|------|
| Mean | 1417.5916 | 1425.7480 | +8.1564 |
| Std | 147.1596 | 133.0144 | -14.1452 |
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

`Dopant-Conductivity Correlation` 是当前最弱的维度：

| 元素 | 真实值 | 生成值 | 差值 |
|------|--------|--------|------|
| Sc | -1.5381 | -1.3076 | +0.2305 |
| Y | -1.8322 | -1.9924 | -0.1602 |
| Ca | -2.2362 | -2.4091 | -0.1729 |
| Pr | -2.3614 | -2.7386 | -0.3772 |
| Dy | -1.7652 | -2.2373 | -0.4721 |
| Lu | -0.8599 | -1.4305 | -0.5706 |
| Ce | -1.5147 | -2.1204 | -0.6057 |
| Yb | -2.5684 | -1.7317 | +0.8367 |
| Bi | -1.4872 | -2.6388 | -1.1517 |

这说明：

- 各主掺杂元素在 700~900°C 区间的相对排序和绝对值都还有明显偏差
- 优化优先级应落在 `baseSigmaLog10`、活化能范围，以及晶相/烧结修正的联合作用上

### 7.4 原始数据 vs 合规数据

| 指标 | 原始数据 | 合规数据 |
|------|----------|----------|
| 数据库 | `ods_zirconia_rule_based` | `conductivity_compliant_rule_based` |
| 样本量 | 113,968,301 | 113,960,665 |
| 过滤数量 | — | 7,636 |
| HC 结果 | FAIL | PASS |
| HC 失败项 | HC-7: 7,569；HC-8: 67 | 无 |
| Fidelity 总分 | 0.846548985171353 | 0.8465469724349198 |
| Fidelity 评级 | GOOD | GOOD |

结论：

- 合规过滤只移除了极少量样本
- 对整体 Fidelity 几乎没有影响
- 但它确实把原始数据从“统计分布看起来还行，但存在规则违规”提升到“规则上也合规”

---

## 8. 数据管道与部署

### 8.1 推荐执行顺序

```text
1. 构建项目
   mvn clean package -DskipTests

2. 同步真实数据到 HDFS
   scripts/submit-mysql2hive.sh

3. 为真实数据创建 Hive 外部表
   sql/hive/create_external_tables.sql

4. 生成规则数据
   scripts/submit.sh
   说明：这一步会直接创建并写入 ods_zirconia_rule_based 下的外部表

5. 运行验证，并按需要输出合规数据
   scripts/submit-data-validator.sh

6. 若已产出合规数据目录，再为其创建 Hive 外部表
   sql/hive/conductivity_compliant_rule_based.sql
```

补充说明：

- `sql/hive/create_external_tables_rule_based.sql` 适合本地默认 `./output` 的场景或手工回放，不是当前 HDFS 提交脚本的必需步骤。
- `scripts/submit-data-validator.sh` 当前包含多段 `spark-submit`，分别对应原始数据验证、带过滤的验证，以及对合规库的复验。

### 8.2 当前脚本资源配置

**生成任务 (`scripts/submit.sh`)**

| 参数 | 值 |
|------|-----|
| deploy-mode | client |
| num-executors | 6 |
| executor-memory | 6g |
| executor-cores | 4 |
| driver-memory | 8g |
| `spark.default.parallelism` | 2000 |
| `spark.sql.shuffle.partitions` | 2000 |
| 配方组数 | 28,000,000 |
| 分区数 | 2,000 |

**验证任务（脚本中的主要配置）**

| 阶段 | deploy-mode | executors | executor-memory | executor-cores |
|------|-------------|-----------|-----------------|----------------|
| 原始库 HC + Fidelity | cluster | 12 | 8g | 6 |
| 带合规过滤输出 | client / cluster（脚本中都有） | 6 | 6g | 4 |
| 合规库复验 | cluster | 6 | 6g | 4 |

### 8.3 数据库与路径映射

| 名称 | 角色 | 典型路径 |
|------|------|----------|
| `zirconia_conductivity_v2` | MySQL 真实源库 | MySQL |
| `ods_zirconia_conductivity_v2` | 真实数据 Hive 外部库 | `/data/material_conductivity_data/ods_zirconia_conductivity_v2` |
| `ods_zirconia_rule_based` | 原始规则生成数据 | `/data/material_conductivity_data/ods_zirconia_rule_based` |
| `conductivity_compliant_rule_based` | 合规过滤后的生成数据 | `/data/material_conductivity_data/conductivity_compliant_rule_based` |
| `conductivity_validation_rule_based` | 验证结果目录/库的上游数据源 | `/data/material_conductivity_data/conductivity_validation_rule_based` |

---

## 9. 代码结构索引

### data-generator-base-rule

| 类 | 路径 | 职责 |
|----|------|------|
| `DataGeneratorBaseRuleApp` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/DataGeneratorBaseRuleApp.java` | 入口，负责建库建表并写出生成数据 |
| `RecipeGroupGenerator` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/generator/RecipeGroupGenerator.java` | 配方组核心生成逻辑 |
| `SourceTextGenerator` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/generator/SourceTextGenerator.java` | 生成来源文本 |
| `AppConfig` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/AppConfig.java` | 参数解析 |
| `PhysicsConstants` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/PhysicsConstants.java` | 常量、分布、元素参数 |
| `DopantProperty` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/DopantProperty.java` | 掺杂元素属性定义 |
| `SinteringRange` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/SinteringRange.java` | 烧结范围 |
| `SynthesisMethod` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/SynthesisMethod.java` | 合成方法常量 |
| `ProcessingRoute` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/ProcessingRoute.java` | 工艺路线常量 |
| `RandomUtils` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/util/RandomUtils.java` | 随机采样工具 |
| `WeightedItem` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/util/WeightedItem.java` | 加权抽样结构 |
| `GeneratedGroup` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/GeneratedGroup.java` | 配方组容器 |
| `MaterialSampleRow` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/MaterialSampleRow.java` | 主表行模型 |
| `SampleDopantRow` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/SampleDopantRow.java` | 掺杂行模型 |
| `SinteringStepRow` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/SinteringStepRow.java` | 烧结行模型 |
| `CrystalPhaseRow` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/CrystalPhaseRow.java` | 晶相行模型 |

### data-sync-mysql2hive

| 类 | 路径 | 职责 |
|----|------|------|
| `MysqlToHiveSyncApp` | `data-sync-mysql2hive/src/main/java/com/lanhung/conductivity/sync/MysqlToHiveSyncApp.java` | 同步入口 |
| `TableSyncExecutor` | `data-sync-mysql2hive/src/main/java/com/lanhung/conductivity/sync/task/TableSyncExecutor.java` | 7 张表的 JDBC 读取与 Parquet 写出 |
| `SyncConfig` | `data-sync-mysql2hive/src/main/java/com/lanhung/conductivity/sync/config/SyncConfig.java` | JDBC 与输出路径配置 |

### data-validator

| 类 | 路径 | 职责 |
|----|------|------|
| `DataValidatorApp` | `data-validator/src/main/java/com/lanhung/conductivity/validator/DataValidatorApp.java` | 参数解析、流程编排 |
| `DataValidator` | `data-validator/src/main/java/com/lanhung/conductivity/validation/DataValidator.java` | HC 校验与合规过滤 |
| `FidelityValidator` | `data-validator/src/main/java/com/lanhung/conductivity/validation/FidelityValidator.java` | Fidelity 评估 |
| `HdfsResultSink` | `data-validator/src/main/java/com/lanhung/conductivity/validation/HdfsResultSink.java` | 结果写入 Parquet 目录 |
