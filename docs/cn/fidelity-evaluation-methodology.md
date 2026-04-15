# 保真度评估方法论

本文档详细描述生成数据与真实实验数据之间的保真度（Fidelity）评估体系。评估由 `FidelityValidator` 执行，当前实现会从 `--real-database` 指定的 Hive 数据库读取真实基线，对比其中 1,351 条真实 ZrO₂ 离子电导率实验记录与生成数据的统计分布，输出 0~1 的综合置信度分数（真实数据 = 1.0）。

## 1. 评估维度与权重

| # | 维度 | 类型 | 权重 | 评估方法 |
|---|------|------|------|----------|
| 1 | Synthesis Method（合成方法） | 类别 | 15% | Jensen-Shannon 散度 |
| 2 | Processing Route（加工路线） | 类别 | 10% | Jensen-Shannon 散度 |
| 3 | Dopant Element（掺杂元素） | 类别 | 15% | Jensen-Shannon 散度 |
| 4 | Crystal Phase / Major（主晶相） | 类别 | 10% | Jensen-Shannon 散度 |
| 5 | log₁₀(Conductivity)（电导率） | 数值 | 20% | 分位数 RMSE 复合评分 |
| 6 | Operating Temperature（工作温度） | 数值 | 10% | 分位数 RMSE 复合评分 |
| 7 | Dopant Molar Fraction（掺杂摩尔分数） | 数值 | 10% | 分位数 RMSE 复合评分 |
| 8 | Sintering Temperature（烧结温度） | 数值 | 5% | 分位数 RMSE 复合评分 |
| 9 | Dopant-Conductivity Correlation（掺杂-电导率关联） | 联合 | 5% | Pearson 相关 + 归一化 RMSE |

**综合分数** = Σ(维度分数 × 权重)，权重合计 100%。

## 2. 类别分布评估（维度 1-4）

### 方法：Jensen-Shannon 散度（JSD）

对真实数据和生成数据分别统计各类别的频率分布 P 和 Q，计算 JSD：

```
M = (P + Q) / 2
JSD(P, Q) = 0.5 × KL(P ‖ M) + 0.5 × KL(Q ‖ M)
```

其中 KL 散度：`KL(P ‖ M) = Σ pᵢ × ln(pᵢ / mᵢ)`

JSD 的取值范围为 [0, ln2]，归一化后：

```
Normalized JSD = JSD / ln(2)      ∈ [0, 1]
Similarity = 1 - Normalized JSD   ∈ [0, 1]
```

- **1.0** = 两个分布完全一致
- **0.0** = 两个分布完全不相交

### 各维度数据来源

| 维度 | 真实数据 SQL | 生成数据 SQL |
|------|-------------|-------------|
| Synthesis Method | `material_samples.synthesis_method_id` 左连接 `synthesis_method_dict.name`；若字典缺失则退回比较 ID 字符串 | 同 |
| Processing Route | `material_samples.processing_route_id` 左连接 `processing_route_dict.name`；若字典缺失则退回比较 ID 字符串 | 同 |
| Dopant Element | `sample_dopants.dopant_element` 频率 | 同 |
| Crystal Phase (Major) | `sample_crystal_phases` 中 `is_major_phase=1` 的 `crystal_id` 字符串频率 | 同 |

### 注意事项

- 类别匹配为**精确字符串匹配**，大小写、标点（如 en-dash `–` vs hyphen `-`）差异会被视为不同类别
- 一方独有的类别在另一方的概率为 0，会显著增大 JSD

## 3. 数值分布评估（维度 5-8）

### 方法：分位数向量归一化 RMSE 复合评分

对真实数据和生成数据分别计算 7 个分位数（P5, P10, P25, P50, P75, P90, P95）以及基础统计量。

#### 3.1 分位数 RMSE（Percentile RMSE）

```
range = max(|real_max - real_min|, 1e-10)

pct_rmse = sqrt( (1/7) × Σᵢ ((gen_pctᵢ - real_pctᵢ) / range)² )
```

衡量两个分布在各分位点上的形状差异，经真实数据值域归一化。

#### 3.2 均值偏差（Mean Difference）

```
mean_diff = |gen_mean - real_mean| / range
```

#### 3.3 标准差比（Std Ratio）

```
std_ratio = min(gen_std, real_std) / max(gen_std, real_std)
```

取值 [0, 1]，1.0 表示离散程度完全一致。

#### 3.4 复合评分公式

```
similarity = 0.6 × max(0, 1 - min(pct_rmse × 3, 1))
           + 0.2 × max(0, 1 - min(mean_diff × 3, 1))
           + 0.2 × std_ratio
```

三个子项的权重：**分位数形状 60% + 均值位置 20% + 离散程度 20%**。

乘以 3 的缩放因子意味着：
- `pct_rmse ≥ 0.33` 或 `mean_diff ≥ 0.33` → 该子项得分为 0
- `pct_rmse = 0` → 该子项满分 0.6

### 各维度数据来源

| 维度 | 数据值 | 来源表 |
|------|--------|--------|
| log₁₀(Conductivity) | `LOG10(conductivity)` (where > 0) | `material_samples` |
| Operating Temperature | `operating_temperature` | `material_samples` |
| Dopant Molar Fraction | `dopant_molar_fraction` | `sample_dopants` |
| Sintering Temperature | `sintering_temperature` | `sintering_steps` |

## 4. 联合分布评估（维度 9）

### 方法：掺杂元素-电导率关联

评估各主掺杂元素在 700-900°C 温度窗口内的平均 log₁₀(电导率) 是否与真实数据一致。

#### 数据筛选

1. 每个 sample 取摩尔分数最高的掺杂元素作为"主掺杂"（`ROW_NUMBER() OVER (PARTITION BY sample_id ORDER BY dopant_molar_fraction DESC)`，取 rn=1）
2. 温度范围限定 700~900°C（中温区，数据最密集）
3. 电导率 > 0
4. 每种元素至少 3 个样本（`HAVING COUNT(*) >= 3`）

#### 评分公式

对共同出现的掺杂元素计算：

**Pearson 相关系数 r**：衡量元素间电导率排序的一致性

```
corr_score = max(0, (r + 1) / 2)    ∈ [0, 1]
```

- r = 1 → 排序完全一致，得 1.0
- r = 0 → 无相关，得 0.5
- r = -1 → 排序完全相反，得 0.0

**归一化 RMSE**：衡量绝对值偏差

```
range = max(real_max - real_min, 1.0)
rmse = sqrt( (1/n) × Σ ((realᵢ - genᵢ) / range)² )
rmse_score = max(0, 1 - rmse × 2)
```

**最终分数**：

```
similarity = 0.5 × corr_score + 0.5 × rmse_score
```

相关性（排序）和绝对值偏差各占 50%。

## 5. 综合评分与评级

```
overall = Σ(dimensionᵢ.score × dimensionᵢ.weight) / Σ(dimensionᵢ.weight)
```

| 分数区间 | 评级 | 含义 |
|----------|------|------|
| ≥ 0.90 | EXCELLENT | 生成数据与真实实验数据高度吻合 |
| ≥ 0.75 | GOOD | 合理匹配，存在小幅偏差 |
| ≥ 0.60 | FAIR | 明显差异，需审查分布细节 |
| < 0.60 | POOR | 显著偏离，需调整生成器参数 |

## 6. 输出结果

当前实现会把结果以 Parquet 目录形式写到 `--output-path` 下：

| 目录 | 内容 |
|------|------|
| `validation_run` | 每次 Fidelity 运行一条汇总记录，包含 `overall_score` 与 `overall_grade` |
| `fidelity_summary` | 各维度分数、权重、加权分数、评级，以及综合分数 |
| `fidelity_categorical` | 每个类别维度的各类别真实占比、生成占比、差值 |
| `fidelity_numerical` | 每个数值维度的 Count/Mean/Std/Min/P5-P95/Max、Pct_RMSE、Std_Ratio |
| `fidelity_correlation` | 各掺杂元素在 700-900°C 的真实/生成平均 log₁₀(电导率) 及差值 |

`docs/result_v2/*.tsv` 是这些结果表导出后的文档视图，不是运行时直接写出的原始格式。

## 7. 真实数据基准

当前运行时的真实数据基线来自 `--real-database` 指定的 Hive 数据库，例如 `ods_zirconia_conductivity_v2`，包含 4 个表：

- `material_samples`
- `sample_dopants`
- `sintering_steps`
- `sample_crystal_phases`

这些表是评估时的“黄金标准”，置信度定义为 1.0。若仓库中另有 TSV 导出文件，它们更适合作为离线快照，而不是当前代码的运行时输入源。
