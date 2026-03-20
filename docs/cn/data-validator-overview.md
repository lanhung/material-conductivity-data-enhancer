# Data Validator 总览：HC 与 Fidelity

本文档用于说明 `data-validator` 模块里的两类验证机制，以及如何解读常见结果。它面向项目内部开发和调试人员，重点回答以下问题：

- `HC` 是什么，作用是什么
- `Fidelity` 是什么，评分依据是什么
- 为什么会出现 “`Fidelity = GOOD`，但 `HC` 失败”
- 遇到这类结果时，应该先看哪些指标定位问题

关于保真度评估的完整公式、权重和方法论，请参见 [保真度评估方法论](./fidelity-evaluation-methodology.md)。

## 1. 两类验证的区别

| 验证类型 | 全称 | 关注点 | 输出形式 | 判定特点 |
|---|---|---|---|---|
| HC | Hard Constraints | 数据是否合法、物理规则是否成立、表间关联是否一致 | 每条约束一行结果 + 整轮通过/失败 | 一票否决，只要任意一项失败，整轮即失败 |
| Fidelity | 保真度评估 | 生成数据与真实实验数据在统计分布上是否接近 | 各维度得分 + 综合分数 + 评级 | 加权评分，不是“一票否决” |

一句话总结：

- `HC` 管“能不能要”
- `Fidelity` 管“像不像真”

这两类验证关注的是两个不同层面：

- `HC` 用来拦截明显错误、违反规则或不一致的数据
- `Fidelity` 用来评估整体分布是否接近真实实验数据

因此，“`Fidelity` 不错”并不等于“数据一定合法”；反过来，“`HC` 全过”也不等于“分布一定足够像真实数据”。

## 2. HC 的作用与判定规则

`HC` 是生成数据的底线校验。它的目标不是衡量“相似度”，而是确保生成结果至少满足当前项目定义的硬规则。

### 2.1 单项与整轮的判定方式

当前实现中，每条硬约束都执行一条 Spark SQL：

- 若 `violations = 0`，该项记为 `passed = true`
- 若 `violations > 0`，该项记为 `passed = false`
- 若执行过程中抛出异常，该项同样记为 `passed = false`

整轮 `HC` 结果由所有单项结果汇总：

- 只有当所有检查项都 `passed = true` 时，整轮 `HC` 才通过
- 只要任意一项失败或报错，整轮 `HC` 就失败

虽然结果表注释写的是 `HC-1 ~ HC-10`，但当前代码里的实际检查项是 13 个，因为其中包含了拆分项：

- `HC-2a`、`HC-2b`
- `HC-6a`、`HC-6b`、`HC-6c`

### 2.2 当前实现中的硬约束清单

| 编号 | 含义 | 主要表 / 字段 |
|---|---|---|
| `HC-1` | 所有样本 `conductivity > 0` | `material_samples.conductivity` |
| `HC-2a` | 所有掺杂项 `dopant_molar_fraction > 0` | `sample_dopants.dopant_molar_fraction` |
| `HC-2b` | 元素固溶度上限：`Sc <= 0.12`、`Ce <= 0.18`、`Y <= 0.25`、`Ca <= 0.20` | `sample_dopants.dopant_element`、`sample_dopants.dopant_molar_fraction` |
| `HC-3` | 每个样本的总掺杂摩尔分数不超过 `0.30` | `sample_dopants.sample_id`、`sample_dopants.dopant_molar_fraction` |
| `HC-4` | 每个样本至少包含一个 `+2` 或 `+3` 价掺杂元素 | `sample_dopants.sample_id`、`sample_dopants.dopant_valence` |
| `HC-5` | 每个样本必须且只能有一个主晶相 | `sample_crystal_phases.sample_id`、`sample_crystal_phases.is_major_phase` |
| `HC-6a` | `sample_dopants.sample_id` 必须都能在主表中找到 | `sample_dopants.sample_id` -> `material_samples.sample_id` |
| `HC-6b` | `sintering_steps.sample_id` 必须都能在主表中找到 | `sintering_steps.sample_id` -> `material_samples.sample_id` |
| `HC-6c` | `sample_crystal_phases.sample_id` 必须都能在主表中找到 | `sample_crystal_phases.sample_id` -> `material_samples.sample_id` |
| `HC-7` | 同一配方组内满足“温度升高，电导率也升高” | `material_samples.operating_temperature`、`material_samples.conductivity`，并结合 `sample_dopants` 生成配方指纹 |
| `HC-8` | 晶相与掺杂量耦合：低掺杂时不能是纯立方主相，高掺杂时不能是纯单斜主相 | `sample_dopants`、`sample_crystal_phases`、`crystal_structure_dict` |
| `HC-9` | 电导率必须在 `[1e-8, 1.0]` 范围内 | `material_samples.conductivity` |
| `HC-10` | 工作温度必须在 `[300, 1400]` 范围内 | `material_samples.operating_temperature` |

### 2.3 使用时需要注意的实现细节

- `HC-3` 的实现里使用了 `0.3005` 作为判定阈值，等价于对 `0.30` 留出一小段浮点容差
- `HC-7` 的“同一配方组”不是按 `sample_id` 分组，而是按 `synthesis_method_id`、`processing_route_id` 和掺杂指纹共同分组
- `HC-8` 依赖 `crystal_structure_dict` 字典表；如果该表缺失或结构不符合预期，该项可能报错，并导致整轮 `HC` 失败

## 3. Fidelity 的作用与输出

`Fidelity` 用于评估生成数据与真实实验数据之间的统计相似度。它不检查“是否合法”，而是回答“整体看起来像不像真实数据”。

运行保真度验证时，需要提供真实数据来源：

- `--real-database <hiveDatabase>`

当前实现会从真实数据和生成数据中对比以下三类信息。

### 3.1 类别分布

对比以下类别型维度的分布差异：

- `Synthesis Method`
- `Processing Route`
- `Dopant Element`
- `Crystal Phase (Major)`

这些维度使用 Jensen-Shannon 散度计算相似度。

### 3.2 数值分布

对比以下数值型维度的分布形状和统计量：

- `log10(Conductivity)`
- `Operating Temperature`
- `Dopant Molar Fraction`
- `Sintering Temperature`

这些维度使用分位数向量、均值和标准差构成复合评分。

### 3.3 联合分布

当前还会对比“主掺杂元素与电导率”的关联是否被保留下来。

### 3.4 综合分数与评级

`Fidelity` 的每个维度都有权重，最终通过加权平均得到综合分数；它不是“一票否决”机制。

当前评级阈值如下：

| 分数区间 | 评级 |
|---|---|
| `>= 0.90` | `EXCELLENT` |
| `>= 0.75` | `GOOD` |
| `>= 0.60` | `FAIR` |
| `< 0.60` | `POOR` |

也就是说，某一个维度表现一般，并不会直接导致整轮 `Fidelity` “失败”；它只会拉低综合分数。

关于详细公式、权重和每个维度的具体 SQL，请参见 [保真度评估方法论](./fidelity-evaluation-methodology.md)。

## 4. 结果解读示例

### 4.1 为什么会出现 “`Fidelity = GOOD`，但 `HC` 失败”

这种结果并不矛盾，通常表示：

- 从整体统计分布看，生成数据和真实数据已经比较接近
- 但在明细记录层面，仍然存在违反硬规则的样本

换句话说，数据可能“整体像真”，但还“不足够合法”。

典型情形包括：

- 大部分样本的温度、电导率、掺杂元素分布都很像真实数据，所以 `Fidelity` 得分不错
- 但只要有一部分样本出现掺杂超限、主晶相数量不对、关联表 `sample_id` 对不上，或者温度升高时电导率反而下降，`HC` 就会失败

在当前项目里，遇到这种结果时，处理优先级通常应该是：

1. 先修 `HC`
2. 再继续优化 `Fidelity`

原因是 `HC` 是底线门槛，而 `Fidelity` 是质量评分。

### 4.2 建议的排查顺序

优先查看 `hard_constraint_result` 中的以下字段：

- `constraint_code`
- `violation_count`
- `passed`

建议按下面顺序排查：

1. 先看哪些 `constraint_code` 失败，以及 `violation_count` 最大的是哪些项
2. 如果只是少数约束失败、且违规数较少，优先怀疑生成规则需要微调
3. 如果多数约束都失败、且违规数很大，优先怀疑数据管道、表结构、关联关系或字典表问题
4. 先排查系统性问题，再排查生成策略问题

常见判断经验：

| 现象 | 更可能的原因 |
|---|---|
| `HC-6a` / `HC-6b` / `HC-6c` 违规很多 | `sample_id` 关联不一致，或抽样后的子表连接有问题 |
| `HC-8` 报错或失败异常明显 | `crystal_structure_dict` 缺失、字段不匹配，或晶相编码不符合预期 |
| `HC-5` 违规很多 | 主晶相标记生成逻辑有问题 |
| `HC-7` 违规很多 | 配方组内温度-电导率单调性没有维护好 |
| `HC-1` / `HC-9` 违规很多 | 电导率生成范围或归一化逻辑有问题 |

如果出现“几乎所有 `HC` 检查项都失败”的情况，通常更像是系统性问题，而不只是生成质量问题。此时应先检查：

- 输入表 schema 是否与校验代码预期一致
- `sample_id` 是否在主表与子表之间保持一致
- 依赖字典表是否存在，例如 `crystal_structure_dict`
- 字段类型或枚举编码是否与 SQL 判定逻辑一致

## 5. 代码与结果位置

### 5.1 主要代码位置

- [data-validator/src/main/java/com/lanhung/conductivity/validator/DataValidatorApp.java](../data-validator/src/main/java/com/lanhung/conductivity/validator/DataValidatorApp.java)：验证入口，负责加载数据、抽样、调用各类验证器
- [data-validator/src/main/java/com/lanhung/conductivity/validation/DataValidator.java](../data-validator/src/main/java/com/lanhung/conductivity/validation/DataValidator.java)：硬约束定义与执行
- [data-validator/src/main/java/com/lanhung/conductivity/validation/FidelityValidator.java](../data-validator/src/main/java/com/lanhung/conductivity/validation/FidelityValidator.java)：保真度评估与综合评分
- [data-validator/src/main/java/com/lanhung/conductivity/validation/HdfsResultSink.java](../data-validator/src/main/java/com/lanhung/conductivity/validation/HdfsResultSink.java)：将验证结果写入 HDFS Parquet 输出

### 5.2 结果表含义

当前验证结果会落到 `validation_run` 和若干子结果表中。

`validation_run`：

- 每次运行一条汇总记录
- 对 `HC` 而言，重点字段是 `passed`
- 对 `Fidelity` 而言，重点字段是 `overall_score` 和 `overall_grade`

`hard_constraint_result`：

- 每个硬约束检查项一条记录
- 重点字段包括 `constraint_code`、`constraint_name`、`passed`、`total_checked`、`violation_count`、`pass_rate`

其余保真度结果表用于展开维度细节：

- `fidelity_summary`
- `fidelity_categorical`
- `fidelity_numerical`
- `fidelity_correlation`

其中：

- `fidelity_summary` 用于查看各维度分数和综合评分
- `fidelity_categorical` 用于查看类别分布差异
- `fidelity_numerical` 用于查看数值统计量差异
- `fidelity_correlation` 用于查看掺杂元素与电导率关联的差异
