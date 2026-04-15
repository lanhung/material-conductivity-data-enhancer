# Data Validator Overview: HC and Fidelity

This document describes the two types of validation mechanisms in the `data-validator` module and how to interpret common results. It is intended for internal developers and debuggers, and focuses on answering the following questions:

- What is `HC` and what does it do
- What is `Fidelity` and what are the scoring criteria
- Why can "`Fidelity = GOOD` but `HC` fails" occur
- When encountering such results, which metrics to check first to locate the problem

For the complete formulas, weights, and methodology of fidelity evaluation, see [Fidelity Evaluation Methodology](./fidelity-evaluation-methodology.md).

## 1. Differences Between the Two Validation Types

| Validation Type | Full Name | Focus | Output Format | Decision Characteristics |
|---|---|---|---|---|
| HC | Hard Constraints | Whether data is valid, physical rules hold, and cross-table relationships are consistent | One result per constraint + overall pass/fail for the run | Veto-based: if any single item fails, the entire run fails |
| Fidelity | Fidelity Evaluation | Whether generated data is statistically close to real experimental data | Per-dimension scores + composite score + grade | Weighted scoring, not veto-based |

In one sentence:

- `HC` governs "is the data acceptable"
- `Fidelity` governs "does it look like real data"

These two types of validation address two different levels:

- `HC` intercepts clearly erroneous, rule-violating, or inconsistent data
- `Fidelity` evaluates whether the overall distribution is close to real experimental data

Therefore, "good `Fidelity`" does not mean "the data is necessarily valid"; conversely, "all `HC` checks passed" does not mean "the distribution is necessarily close enough to real data."

## 2. Purpose and Decision Rules of HC

`HC` is the baseline check for generated data. Its goal is not to measure "similarity," but to ensure that generated results at least satisfy the hard rules defined by the project.

### 2.1 Per-Item and Per-Run Decision Logic

In the current implementation, each hard constraint executes a Spark SQL query:

- If `violations = 0`, the item is marked as `passed = true`
- If `violations > 0`, the item is marked as `passed = false`
- If an exception is thrown during execution, the item is also marked as `passed = false`

The overall `HC` result for a run is aggregated from all individual results:

- The run passes only when all check items are `passed = true`
- If any single item fails or errors out, the entire run fails

Although the result table annotations list `HC-1 ~ HC-10`, the actual number of check items in the current code is 13, because some items are split:

- `HC-2a`, `HC-2b`
- `HC-6a`, `HC-6b`, `HC-6c`

### 2.2 Current Hard Constraint Checklist

| Code | Meaning | Primary Table / Field |
|---|---|---|
| `HC-1` | All samples must have `conductivity > 0` | `material_samples.conductivity` |
| `HC-2a` | All dopant entries must have `dopant_molar_fraction > 0` | `sample_dopants.dopant_molar_fraction` |
| `HC-2b` | Element solid solubility limits: `Sc <= 0.12`, `Ce <= 0.18`, `Y <= 0.25`, `Ca <= 0.20` | `sample_dopants.dopant_element`, `sample_dopants.dopant_molar_fraction` |
| `HC-3` | Total dopant molar fraction per sample must not exceed `0.30` | `sample_dopants.sample_id`, `sample_dopants.dopant_molar_fraction` |
| `HC-4` | Each sample must contain at least one dopant element with valence `+2` or `+3` | `sample_dopants.sample_id`, `sample_dopants.dopant_valence` |
| `HC-5` | Each sample must have exactly one major crystal phase | `sample_crystal_phases.sample_id`, `sample_crystal_phases.is_major_phase` |
| `HC-6a` | `sample_dopants.sample_id` must all exist in the main table | `sample_dopants.sample_id` -> `material_samples.sample_id` |
| `HC-6b` | `sintering_steps.sample_id` must all exist in the main table | `sintering_steps.sample_id` -> `material_samples.sample_id` |
| `HC-6c` | `sample_crystal_phases.sample_id` must all exist in the main table | `sample_crystal_phases.sample_id` -> `material_samples.sample_id` |
| `HC-7` | Within the same composition group, conductivity must increase as temperature increases | When read through the current Hive tables, it is usually derived from `sample_id`; if the runtime DataFrame directly exposes Parquet `recipe_group_id`, that is used first |
| `HC-8` | Crystal phase and dopant level coupling: low dopant levels cannot have pure cubic major phase; high dopant levels cannot have pure monoclinic major phase | `sample_dopants`, `sample_crystal_phases`, `crystal_structure_dict` |
| `HC-9` | Conductivity must be within the range `[1e-8, 1.0]` | `material_samples.conductivity` |
| `HC-10` | Operating temperature must be within the range `[300, 1400]` | `material_samples.operating_temperature` |

### 2.3 Implementation Details to Be Aware Of

- `HC-3` uses `0.3005` as the threshold, effectively allowing a small floating-point tolerance around `0.30`
- `HC-7` does not require `recipe_group_id` to exist in the Hive table schema; when data is read through the current Hive tables, that column is usually unavailable, so the validator first derives the recipe group from `sample_id`; if the runtime DataFrame directly carries the underlying Parquet `recipe_group_id`, it can also use that; only otherwise does it fall back to grouping by `synthesis_method_id`, `processing_route_id`, and dopant fingerprint
- `HC-8` depends on the `crystal_structure_dict` dictionary table; if this table is missing or has an unexpected structure, this check may error out and cause the entire `HC` run to fail

## 3. Purpose and Output of Fidelity

`Fidelity` evaluates the statistical similarity between generated data and real experimental data. It does not check "whether the data is valid," but rather answers "does the data overall look like real data."

When running fidelity validation, a real data source must be provided:

- `--real-database <hiveDatabase>`

The current implementation reads the 4 real-data tables directly from that Hive database rather than from a local TSV directory.

The current implementation compares the following three categories of information between real and generated data.

### 3.1 Categorical Distributions

Compares distribution differences across the following categorical dimensions:

- `Synthesis Method`
- `Processing Route`
- `Dopant Element`
- `Crystal Phase (Major)`

These dimensions use Jensen-Shannon divergence to compute similarity.

### 3.2 Numerical Distributions

Compares distribution shape and summary statistics across the following numerical dimensions:

- `log10(Conductivity)`
- `Operating Temperature`
- `Dopant Molar Fraction`
- `Sintering Temperature`

These dimensions use a composite score consisting of quantile vectors, mean, and standard deviation.

### 3.3 Joint Distribution

The current implementation also checks whether the association between the primary dopant element and conductivity is preserved.

### 3.4 Composite Score and Grading

Each dimension of `Fidelity` has a weight, and the final composite score is obtained through a weighted average; it is not a veto-based mechanism.

The current grading thresholds are as follows:

| Score Range | Grade |
|---|---|
| `>= 0.90` | `EXCELLENT` |
| `>= 0.75` | `GOOD` |
| `>= 0.60` | `FAIR` |
| `< 0.60` | `POOR` |

In other words, a mediocre performance in one dimension does not directly cause the entire `Fidelity` run to "fail"; it only lowers the composite score.

For detailed formulas, weights, and the specific SQL for each dimension, see [Fidelity Evaluation Methodology](./fidelity-evaluation-methodology.md).

## 4. Result Interpretation Examples

### 4.1 Why Can "`Fidelity = GOOD` but `HC` Fails" Occur

This result is not contradictory and typically indicates:

- From an overall statistical distribution perspective, the generated data and real data are already fairly close
- But at the individual record level, there are still samples that violate hard rules

In other words, the data may "look real overall" but is still "not sufficiently valid."

Typical scenarios include:

- Most samples have temperature, conductivity, and dopant element distributions that closely resemble real data, so `Fidelity` scores well
- But as long as some samples have excessive dopant levels, incorrect major phase counts, mismatched `sample_id` in related tables, or decreasing conductivity with increasing temperature, `HC` will fail

In the current project, when encountering such results, the typical priority should be:

1. Fix `HC` first
2. Then continue optimizing `Fidelity`

The reason is that `HC` is the baseline threshold, while `Fidelity` is a quality score.

The latest v2 run is a good example:

- The raw database `ods_zirconia_rule_based_v2` already achieved `GOOD` Fidelity (`0.8640317372280062`), but HC still failed because `HC-7 = 1,264` and `HC-8 = 63`
- After compliant filtering and re-validation, `ods_conductivity_compliant_rule_based_v2` became `HC = PASS`, while Fidelity still remained `GOOD (0.8640322957399598)`
- This is exactly how the current v2 workflow should be interpreted: first filter anomalies, then perform quality validation on the filtered dataset

### 4.2 Recommended Troubleshooting Order

Start by checking the following fields in `hard_constraint_result`:

- `constraint_code`
- `violation_count`
- `passed`

Recommended troubleshooting sequence:

1. First, identify which `constraint_code` items failed and which have the highest `violation_count`
2. If only a few constraints failed with a small number of violations, first suspect that generation rules need fine-tuning
3. If most constraints failed with a large number of violations, first suspect data pipeline, table schema, referential integrity, or dictionary table issues
4. Investigate systemic issues first, then generation strategy issues

Common diagnostic patterns:

| Observation | More Likely Cause |
|---|---|
| `HC-6a` / `HC-6b` / `HC-6c` have many violations | `sample_id` referential inconsistency, or join issues in sub-tables after sampling |
| `HC-8` errors out or shows clearly anomalous failures | `crystal_structure_dict` is missing, fields do not match, or phase encoding does not meet expectations |
| `HC-5` has many violations | Major phase marking generation logic has issues |
| `HC-7` has many violations | Temperature-conductivity monotonicity within composition groups is not maintained properly |
| `HC-1` / `HC-9` have many violations | Conductivity generation range or normalization logic has issues |

If "almost all `HC` checks fail," it is usually a systemic issue rather than just a generation quality problem. In this case, check the following first:

- Whether the input table schema matches what the validation code expects
- Whether `sample_id` is consistent between the main table and sub-tables
- Whether required dictionary tables exist, such as `crystal_structure_dict`
- Whether field types or enumeration encodings match the SQL validation logic

## 5. Code and Result Locations

### 5.1 Main Code Locations

- [data-validator/src/main/java/com/lanhung/conductivity/validator/DataValidatorApp.java](../data-validator/src/main/java/com/lanhung/conductivity/validator/DataValidatorApp.java): Validation entry point, responsible for loading data, sampling, and invoking various validators
- [data-validator/src/main/java/com/lanhung/conductivity/validation/DataValidator.java](../data-validator/src/main/java/com/lanhung/conductivity/validation/DataValidator.java): Hard constraint definitions and execution
- [data-validator/src/main/java/com/lanhung/conductivity/validation/FidelityValidator.java](../data-validator/src/main/java/com/lanhung/conductivity/validation/FidelityValidator.java): Fidelity evaluation and composite scoring
- [data-validator/src/main/java/com/lanhung/conductivity/validation/HdfsResultSink.java](../data-validator/src/main/java/com/lanhung/conductivity/validation/HdfsResultSink.java): Writes validation results to HDFS Parquet output

### 5.2 Result Table Descriptions

Validation results are written to `validation_run` and several sub-result tables.

`validation_run`:

- One summary record per run
- For `HC`, the key field is `passed`
- For `Fidelity`, the key fields are `overall_score` and `overall_grade`

`hard_constraint_result`:

- One record per hard constraint check item
- Key fields include `constraint_code`, `constraint_name`, `passed`, `total_checked`, `violation_count`, `pass_rate`

The remaining fidelity result tables expand dimension details:

- `fidelity_summary`
- `fidelity_categorical`
- `fidelity_numerical`
- `fidelity_correlation`

Where:

- `fidelity_summary` is used to view per-dimension scores and the composite score
- `fidelity_categorical` is used to view categorical distribution differences
- `fidelity_numerical` is used to view numerical statistics differences
- `fidelity_correlation` is used to view differences in the dopant element-conductivity association

Additional note:

- At runtime, these results are written as Parquet directories under `--output-path`
- `docs/result_v2/*.tsv` are exported documentation views of those result tables for offline analysis and reporting
