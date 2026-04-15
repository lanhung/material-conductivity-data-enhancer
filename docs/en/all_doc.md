# Zirconia Ionic Conductivity Data Enhancement System — Technical Documentation

> Note: the latest v2 validation results (`docs/result_v2`) and the two-stage validation / re-validation analysis are maintained in [all_doc_v2.md](./all_doc_v2.md). This document preserves the earlier batch-level write-up, so for the latest run conclusions please read the revised v2 document first.

## Table of Contents

- [1. Project Overview](#1-project-overview)
- [2. System Architecture](#2-system-architecture)
- [3. Data Model and Table Schema](#3-data-model-and-table-schema)
- [4. Data Generation Module (data-generator-base-rule)](#4-data-generation-module-data-generator-base-rule)
  - [4.1 Generation Architecture](#41-generation-architecture)
  - [4.2 Generation Workflow](#42-generation-workflow)
  - [4.3 14 Physical Constraint Rules](#43-14-physical-constraint-rules)
  - [4.4 Conductivity Calculation Model](#44-conductivity-calculation-model)
  - [4.5 Dopant Element Property Table](#45-dopant-element-property-table)
  - [4.6 Synthesis Methods and Processing Routes](#46-synthesis-methods-and-processing-routes)
- [5. Data Synchronization Module (data-sync-mysql2hive)](#5-data-synchronization-module-data-sync-mysql2hive)
- [6. Data Validation Module (data-validator)](#6-data-validation-module-data-validator)
  - [6.1 Hard Constraint Validation](#61-hard-constraint-validation)
  - [6.2 Fidelity Assessment](#62-fidelity-assessment)
  - [6.3 Relationship Between HC and Fidelity](#63-relationship-between-hc-and-fidelity)
- [7. Run Results Analysis](#7-run-results-analysis)
  - [7.1 Validation Run Overview](#71-validation-run-overview)
  - [7.2 Hard Constraint Validation Results Analysis](#72-hard-constraint-validation-results-analysis)
  - [7.3 Fidelity Assessment Results Analysis](#73-fidelity-assessment-results-analysis)
  - [7.4 Results Comparison: Raw Data vs Compliant Data](#74-results-comparison-raw-data-vs-compliant-data)
- [8. Data Pipeline and Deployment](#8-data-pipeline-and-deployment)
- [9. Code Structure Index](#9-code-structure-index)

---

## 1. Project Overview

This system (material-conductivity-data-enhancer) is a large-scale data pipeline based on Apache Spark for **generating, synchronizing, and validating** synthetic experimental data on the ionic conductivity of zirconia (ZrO₂) solid electrolytes.

Core objective: Based on 14 materials science physical constraints, automatically generate approximately **100 million** high-fidelity synthetic sample records, and ensure the generated data meets both physical rule baselines and closely approximates real experimental data distributions through a dual verification mechanism of hard constraint checks and statistical fidelity assessment.

**Tech Stack:**

| Component | Technology |
|-----------|------------|
| Compute Engine | Apache Spark 3.5.8 |
| Storage Format | Parquet |
| Data Warehouse | Hive External Tables |
| Data Source | MySQL 8.0.33 |
| Programming Language | Java 8 |
| Build Tool | Maven |

---

## 2. System Architecture

The project consists of three Maven submodules:

```
material-conductivity-data-enhancer/
├── data-generator-base-rule/    # Data generation: physics-rule-based synthetic data engine
├── data-sync-mysql2hive/        # Data sync: MySQL real data → HDFS Parquet
├── data-validator/              # Data validation: hard constraint checks + fidelity assessment
├── scripts/                     # Spark submit scripts
├── sql/                         # DDL statements (MySQL / Hive)
├── docs/                        # Documentation
└── pom.xml                      # Parent POM
```

**Complete Data Pipeline Flow:**

```
┌─────────────────┐     ┌──────────────────┐     ┌───────────────────┐
│  MySQL Real Data │────▶│  data-sync-mysql  │────▶│ Hive: ods_zirconia│
│ (zirconia_v2)   │     │  2hive (Spark)    │     │ _conductivity_v2  │
└─────────────────┘     └──────────────────┘     └────────┬──────────┘
                                                          │ Used as Fidelity
                                                          │ Reference Baseline
┌─────────────────┐     ┌──────────────────┐     ┌────────▼──────────┐
│  Physics Rules + │────▶│  data-generator-  │────▶│ Hive: ods_zirconia│
│  Stat. Dist.     │     │  base-rule (Spark)│     │ _rule_based       │
│  Parameters      │     └──────────────────┘     └────────┬──────────┘
└─────────────────┘                                        │
                                                 ┌────────▼──────────┐
                                                 │  data-validator   │
                                                 │  (Spark)          │
                                                 │  ├─ HC Checks     │
                                                 │  └─ Fidelity      │
                                                 └────────┬──────────┘
                                                          │
                                               ┌──────────▼───────────┐
                                               │ Compliant Dataset     │
                                               │ conductivity_        │
                                               │ compliant_rule_based │
                                               └──────────────────────┘
```

---

## 3. Data Model and Table Schema

### 3.1 Dictionary Tables

| Table Name | Description | Key Fields |
|------------|-------------|------------|
| `crystal_structure_dict` | Crystal structure dictionary | id, name (Cubic/Tetragonal/Monoclinic/Orthogonal/Rhombohedral/Beta-phase) |
| `synthesis_method_dict` | Synthesis method dictionary | id, name (9 methods) |
| `processing_route_dict` | Processing route dictionary | id, name (19 routes) |

### 3.2 Core Data Tables

**material_samples** — Material sample master table

| Field | Type | Description |
|-------|------|-------------|
| sample_id | BIGINT | Unique identifier (`10000001 + recipe_group_id * 8 + temp_index`; `recipe_group_id` physically exists in the output Parquet, but the current Hive table schema does not expose that column) |
| reference | STRING | Source identifier (`RULE_BASED_SYNTHETIC` for generated data) |
| material_source_and_purity | STRING | Material source and purity description |
| synthesis_method_id | INT | Synthesis method ID (FK → synthesis_method_dict) |
| processing_route_id | INT | Processing route ID (FK → processing_route_dict) |
| operating_temperature | DOUBLE | Test temperature (300–1400°C) |
| conductivity | DOUBLE | Ionic conductivity (1×10⁻⁸ – 1.0 S/cm) |

**sample_dopants** — Dopant composition

| Field | Type | Description |
|-------|------|-------------|
| sample_id | BIGINT | FK → material_samples |
| dopant_element | STRING | Dopant element symbol (Y, Sc, Ce, etc. — 20 types) |
| dopant_ionic_radius | DOUBLE | Ionic radius (pm) |
| dopant_valence | INT | Valence (+2, +3, +4, +5) |
| dopant_molar_fraction | DOUBLE | Dopant molar fraction (0.001–0.30) |

**sintering_steps** — Sintering steps

| Field | Type | Description |
|-------|------|-------------|
| sample_id | BIGINT | FK → material_samples |
| step_order | INT | Sintering step order (1–4) |
| sintering_temperature | DOUBLE | Sintering temperature (°C) |
| sintering_duration | DOUBLE | Sintering duration (min) |

**sample_crystal_phases** — Crystal phases

| Field | Type | Description |
|-------|------|-------------|
| sample_id | BIGINT | FK → material_samples |
| crystal_id | INT | FK → crystal_structure_dict |
| is_major_phase | BOOLEAN | Whether this is the major phase |

---

## 4. Data Generation Module (data-generator-base-rule)

### 4.1 Generation Architecture

The generation unit is a **Recipe Group**, not an individual sample.

- One recipe group = a fixed recipe (dopants, sintering conditions, crystal phases, etc.) × multiple test temperature points
- Each recipe group produces 3–8 material_sample records (corresponding to different test temperatures)
- Samples within the same group share dopants, sintering parameters, and crystal phases — only temperature and conductivity differ

**Scale Parameters:**

| Parameter | Default Value |
|-----------|---------------|
| Total recipe groups | 25,000,000 – 28,000,000 |
| Temperature points per group | 3–8 |
| Total samples | ~100,000,000 – 113,000,000 |
| Partitions | 1,000 – 2,000 |

### 4.2 Generation Workflow

The generation process consists of 6 sequential steps:

```
Step 1: Dopant Generation
  ├─ Randomly select 1–5 dopant elements (weighted sampling)
  ├─ Generate molar fraction for each element (Beta distribution)
  └─ Validation: solubility limits, total doping ≤ 0.30

Step 2: Crystal Phase Generation
  ├─ Determine major phase based on dopant concentration and element type
  └─ Optionally add secondary phases

Step 3: Sintering Parameter Generation
  ├─ Determine sintering temperature range based on synthesis method
  ├─ Generate 0–4 sintering steps
  └─ RF sputtered thin films have no sintering steps

Step 4: Temperature Sequence Generation
  ├─ Generate 3–8 test temperature points (300–1400°C)
  └─ Sort temperature points

Step 5: Conductivity Calculation
  ├─ Segmented Arrhenius model
  ├─ Physical correction factor overlay
  └─ Ensure monotonic increase: temperature ↑ → conductivity ↑ within group

Step 6: Material Source Description Generation
  └─ Generate realistic material source and purity text
```

### 4.3 14 Physical Constraint Rules

The following 14 physical laws and empirical rules are strictly enforced during generation:

#### Rule 1: Arrhenius Temperature Dependence

Conductivity follows the Arrhenius equation as a function of temperature:

```
σ(T) = σ_ref × exp[-Ea/kB × (1/T - 1/T_ref)]
```

A **segmented activation energy model** is used: 600°C serves as the boundary, with different activation energy parameters (`Ea_high`, `Ea_low`) for the high- and low-temperature segments, reflecting the different ionic conduction mechanisms of zirconia in different temperature ranges.

- Reference temperature T_ref = 800°C (1073.15 K)
- Boltzmann constant kB = 8.617333 × 10⁻⁵ eV/K

#### Rule 2: Fixed Ionic Radius and Valence Mapping

Each dopant element has a fixed ionic radius (pm) and valence, hardcoded via a lookup table. The system supports 20 dopant elements, with each element's radius and valence retrieved from the `DopantProperty` lookup table.

#### Rule 3: Crystal Phase–Dopant Coupling

Crystal phase selection depends on dopant type and concentration:

- High-concentration Y or Sc doping → tends toward Cubic phase
- Low-concentration doping → Tetragonal or Monoclinic phase
- Specific dopant combinations correspond to specific crystal phases

#### Rule 4: Oxygen Vacancy Creation (Valence Constraint)

Each sample must contain at least one dopant element with +2 or +3 valence to ensure oxygen vacancy creation. This is the fundamental mechanism of ionic conduction in zirconia — lower-valence cations substituting for Zr⁴⁺ create oxygen vacancies, enabling oxygen ion migration.

#### Rule 5: Element-Specific Solubility Limits

Each element has an independent maximum solubility limit:

| Element | Max Solubility | Element | Max Solubility |
|---------|---------------|---------|---------------|
| Y | 25% | Sc | 12% |
| Ce | 18% | Ca | 20% |
| Yb | 15% | Bi | 15% |
| Gd | 12% | Er | 12% |

Other elements also have their respective solubility limits (retrieved from `DopantProperty.maxSolubility`).

#### Rule 6: Total Doping Limit

The total dopant molar fraction per sample ≤ 0.30 (30%). Beyond this threshold, it is no longer doping but rather the formation of a new solid solution.

#### Rule 7: Concentration Non-Monotonicity

There is a non-monotonic relationship between conductivity and dopant concentration — deviation from the optimal doping concentration (different for each element) increases the activation energy, leading to decreased conductivity. The model implements this effect by imposing an activation energy penalty on the deviation amount.

#### Rule 8: Ionic Radius–Conductivity Correlation

Different elements' ionic radii affect how well they fit into the crystal lattice, thereby influencing conductivity. This is reflected through element-specific activation energy parameters (`Ea_high`, `Ea_low`) and base conductivity (`baseConductivity`).

#### Rule 9: Sintering–Synthesis Method Coupling

Sintering temperature and duration ranges depend on the synthesis method:

| Synthesis Method | Sintering Temperature Range (°C) |
|-----------------|----------------------------------|
| Solid-state synthesis | 1200–1600 |
| Sol–gel method | 1000–1500 |
| Coprecipitation method | 1100–1500 |
| Hydrothermal synthesis | 800–1200 |
| Commercialization | 1300–1600 |

#### Rule 10: Grain Boundary Effect

Low sintering temperatures (< 1300°C) result in small grain sizes and high grain boundary density, producing an attenuation effect on conductivity. The model applies an additional conductivity penalty factor to low-temperature sintered samples.

#### Rule 11: ScSZ Phase Degradation

When Sc doping < 10% and test temperature < 650°C, Sc-stabilized zirconia (ScSZ) undergoes phase degradation, reducing conductivity by 20%–50%.

#### Rule 12: Co-Doping Non-Additivity

When more than 2 dopant elements are present, the interactions from multi-element doping cause conductivity to be lower than the simple sum of individual elements. The model applies a 5% conductivity penalty for each additional element beyond the 2nd.

#### Rule 13: Transition Metal Electronic Conduction

Transition metal dopants such as Fe and Mn introduce electronic conduction components, slightly increasing total conductivity. The model applies a conductivity gain to samples containing transition metal dopants.

#### Rule 14: Thin Film RF Sputtering Specifics

Samples prepared by RF sputtering have no sintering steps and exhibit different microstructural characteristics, with conductivity calculations adjusted accordingly.

### 4.4 Conductivity Calculation Model

Conductivity generation uses a **segmented Arrhenius + physical corrections** composite model:

```
σ(T) = σ_base × A(T) × C_grain × C_ScSZ × C_codope × C_TM
```

Where:

| Symbol | Meaning | Calculation |
|--------|---------|-------------|
| σ_base | Base conductivity | Obtained from dopant element property lookup table |
| A(T) | Arrhenius temperature factor | exp[-Ea/kB × (1/T - 1/T_ref)], Ea segmented |
| C_grain | Grain boundary correction | Attenuation when sintering temperature < 1300°C |
| C_ScSZ | ScSZ degradation correction | 20%–50% attenuation when Sc < 10% and T < 650°C |
| C_codope | Co-doping correction | 5% attenuation for each additional element beyond 2 |
| C_TM | Transition metal correction | Gain when Fe/Mn present |

The final conductivity is clamped to the [1×10⁻⁸, 1.0] S/cm range and monotonicity within the same recipe group is enforced (temperature increase → conductivity increase).

### 4.5 Dopant Element Property Table

The system supports 20 dopant elements, each with the following properties:

| Property | Description |
|----------|-------------|
| Element symbol | Y, Sc, Yb, Ce, Dy, Bi, Gd, Er, Lu, Pr, Ca, Fe, Mn, Zn, Al, In, Eu, Si, Nb, Ti |
| Ionic radius | Fixed value (pm) |
| Valence | +2 / +3 / +4 / +5 |
| Selection frequency | Probability weight for weighted sampling |
| Optimal doping concentration | Molar fraction at which conductivity is maximized |
| Maximum solubility | Solid solubility limit of the element in ZrO₂ |
| Beta distribution parameters | α, β, scale — used for molar fraction generation |
| Activation energy (high/low temp) | Ea_high, Ea_low (eV) — Arrhenius model parameters |
| Base conductivity | Conductivity at the 800°C reference condition |

In the dopant element frequency distribution, Y and Sc are the most frequent elements (approximately 38% and 33% respectively), reflecting the focus areas in real research.

### 4.6 Synthesis Methods and Processing Routes

**9 Synthesis Methods:**

| Synthesis Method | Description |
|-----------------|-------------|
| Solid-state synthesis | Solid-state synthesis |
| Commercialization | Commercial product |
| Sol–gel method | Sol–gel method |
| Hydrothermal synthesis | Hydrothermal synthesis |
| Coprecipitation method | Coprecipitation method |
| Glycine method | Glycine method |
| Coprecipitation | Coprecipitation |
| Directional melt crystallization | Directional melt crystallization |
| (Other) | — |

**19 Processing Routes (partial list):**

| Processing Route | Description |
|-----------------|-------------|
| dry pressing | Dry pressing |
| isostatic pressing | Isostatic pressing |
| tape casting | Tape casting |
| spark plasma sintering | Spark plasma sintering |
| RF sputtering | RF sputtering |
| spray pyrolysis | Spray pyrolysis |
| 3D printing | 3D printing |
| spin coating | Spin coating |
| magnetron sputtering | Magnetron sputtering |
| pulsed laser deposition | Pulsed laser deposition |

---

## 5. Data Synchronization Module (data-sync-mysql2hive)

### 5.1 Functional Description

Synchronizes real experimental data from MySQL to HDFS, stored in Parquet format for Hive external table access, serving as the reference baseline for fidelity assessment.

The real data used in this project comes from the repository [lanhung/material-conductivity-data-clean](https://github.com/lanhung/material-conductivity-data-clean), and is imported into the MySQL database `zirconia_conductivity_v2` before synchronization.

### 5.2 Synchronized Tables

A total of 7 tables are synchronized:

1. `crystal_structure_dict`
2. `synthesis_method_dict`
3. `processing_route_dict`
4. `material_samples`
5. `sample_dopants`
6. `sintering_steps`
7. `sample_crystal_phases`

### 5.3 Usage

```bash
spark-submit \
  --class com.lanhung.conductivity.sync.MysqlToHiveSyncApp \
  data-sync-mysql2hive-1.0-SNAPSHOT.jar \
  jdbc:mysql://<host>:<port>/zirconia_conductivity_v2 \
  <user> <password> \
  /data/material_conductivity_data/ods_zirconia_conductivity_v2
```

- Source database: `zirconia_conductivity_v2` (MySQL)
- Real data source: `https://github.com/lanhung/material-conductivity-data-clean`
- Target path: HDFS Parquet, corresponding to Hive database `ods_zirconia_conductivity_v2`
- Write mode: Overwrite

---

## 6. Data Validation Module (data-validator)

The validation module provides two independent verification mechanisms that manage different levels of data quality:

| Validation Type | Core Question | Decision Logic |
|----------------|---------------|----------------|
| **HC Hard Constraint** | "Is the data usable?" — baseline check | One-strike-out: any single constraint failure means overall failure |
| **Fidelity** | "Does the data look real?" — distribution similarity | Weighted scoring: produces an overall rating |

### 6.1 Hard Constraint Validation

A total of 13 checks (HC-1 through HC-10, some subdivided):

| ID | Constraint Name | Check Content |
|----|----------------|---------------|
| HC-1 | Conductivity positive | All conductivity > 0 |
| HC-2a | Molar fraction positive | All molar_fraction > 0 |
| HC-2b | Element solubility limit | Each element does not exceed its solid solubility limit |
| HC-3 | Total doping limit | Total dopant molar fraction per sample ≤ 0.30 |
| HC-4 | Oxygen vacancy guarantee | Each sample contains at least one +2 or +3 valence dopant |
| HC-5 | Single major phase | Each sample has exactly one is_major_phase = true |
| HC-6a | Referential integrity (dopants) | All dopant sample_id values exist in material_samples |
| HC-6b | Referential integrity (sintering) | All sintering sample_id values exist in material_samples |
| HC-6c | Referential integrity (crystal phases) | All phase sample_id values exist in material_samples |
| HC-7 | Monotonicity | Temperature increase → conductivity increase within the same group |
| HC-8 | Phase–dopant coupling | Crystal phase selection consistent with dopant type/concentration |
| HC-9 | Conductivity range | conductivity ∈ [1×10⁻⁸, 1.0] |
| HC-10 | Temperature range | operating_temperature ∈ [300, 1400] |

**Decision rule:** If any constraint has violation_count > 0, the overall HC validation is judged as FAIL.

### 6.2 Fidelity Assessment

The fidelity assessment compares the statistical characteristics of generated data against real experimental data across **9 weighted dimensions**.

#### Assessment Dimensions and Weights

| Dimension | Weight | Scoring Method | Type |
|-----------|--------|---------------|------|
| Synthesis Method | 15% | Jensen-Shannon divergence | Categorical |
| Processing Route | 10% | Jensen-Shannon divergence | Categorical |
| Dopant Element | 15% | Jensen-Shannon divergence | Categorical |
| Crystal Phase (Major) | 10% | Jensen-Shannon divergence | Categorical |
| log₁₀(Conductivity) | 20% | Percentile RMSE composite score | Numerical |
| Operating Temperature | 10% | Percentile RMSE composite score | Numerical |
| Dopant Molar Fraction | 10% | Percentile RMSE composite score | Numerical |
| Sintering Temperature | 5% | Percentile RMSE composite score | Numerical |
| Dopant-Conductivity Correlation | 5% | Pearson correlation + normalized RMSE | Correlation |

#### Scoring Methods

**Categorical dimensions** — Jensen-Shannon divergence:

```
JSD(P, Q) = ½ KL(P‖M) + ½ KL(Q‖M), where M = ½(P + Q)
Score = 1 - √JSD    (normalized to [0, 1])
```

**Numerical dimensions** — Composite score:

```
Score = 0.6 × (1 - Percentile_RMSE) + 0.2 × (1 - |mean_diff|/range) + 0.2 × min(std_gen/std_real, std_real/std_gen)
```

Percentile RMSE is calculated based on seven percentiles: P5, P10, P25, P50, P75, P90, P95.

**Correlation dimension** — Dopant-Conductivity correlation:

```
Score = 0.5 × |Pearson_r| + 0.5 × (1 - normalized_RMSE)
```

Compares the average log₁₀(Conductivity) for each dopant element between real and generated data.

#### Rating Criteria

| Overall Score | Rating |
|--------------|--------|
| ≥ 0.90 | EXCELLENT |
| ≥ 0.75 | GOOD |
| ≥ 0.60 | FAIR |
| < 0.60 | POOR |

#### Output Files

| File | Content |
|------|---------|
| `fidelity_summary` | Per-dimension scores, weights, weighted scores, and ratings |
| `fidelity_categorical` | Real vs generated proportions for each category in categorical dimensions |
| `fidelity_numerical` | Statistical comparison for numerical dimensions (Count, Mean, Std, Percentiles, etc.) |
| `fidelity_correlation` | Average log₁₀(Conductivity) comparison per dopant element |

### 6.3 Relationship Between HC and Fidelity

The two validations are independent and may produce the following combinations:

| HC | Fidelity | Meaning |
|----|----------|---------|
| PASS | GOOD+ | Ideal state: data is compliant and realistic |
| PASS | POOR | Data does not violate rules, but distribution is distorted |
| FAIL | GOOD | Distribution is similar but physical violations exist (e.g., minor monotonicity violations) |
| FAIL | POOR | Data is both non-compliant and distorted |

### 6.4 Usage

```bash
# Run HC only (still requires --output-path)
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

# HC + Fidelity + compliant filtering
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

If neither `--validate` nor `--fidelity` is passed, the program defaults to `HC`; however, `--output-path` is still required.

---

## 7. Run Results Analysis

The following analysis is based on the latest exported results in `docs/result_v2`. There are 4 run records in total, but they are not four unrelated jobs. They form one continuous two-stage workflow:

- Stage 1: detect anomalous records in the raw v2 rule-generated dataset and produce the compliant database
- Stage 2: re-validate the compliant database to confirm that the filtered result can be used as the stable version

### 7.1 Validation Run Overview

| run_id | Validation Type | Target Database | Total Samples | Pass/Rating | Overall Score | Run Time |
|--------|----------------|-----------------|---------------|-------------|--------------|----------|
| 1776064352809 | HARD_CONSTRAINT | `ods_zirconia_rule_based_v2` | 100,007,040 | FAIL | 99.99873608897934 | 2026-04-13 15:12:32 |
| 1776065204878 | FIDELITY | `ods_zirconia_rule_based_v2` | 100,007,040 | GOOD | 0.8640317372280062 | 2026-04-13 15:26:44 |
| 1776130873472 | HARD_CONSTRAINT | `ods_conductivity_compliant_rule_based_v2` | 100,005,713 | PASS | 100 | 2026-04-14 09:41:13 |
| 1776131049790 | FIDELITY | `ods_conductivity_compliant_rule_based_v2` | 100,005,713 | GOOD | 0.8640322957399598 | 2026-04-14 09:44:09 |

**Key Findings:**

- `2026-04-13`: the raw database still contained a small tail of `HC-7` / `HC-8` violations, while its overall Fidelity had already reached `GOOD`
- `2026-04-14`: the compliant database was then re-validated, yielding `HC = PASS` while Fidelity remained `GOOD`
- The raw v2 rule-generated data failed HC only on `HC-7` and `HC-8`; after compliant filtering, all HC checks passed
- The compliant filtering reduced the sample count by `1,327` records, which is `0.0013269066%` of the raw data
- The overall Fidelity score increased by only `5.5855e-7`; all 9 per-dimension score changes stayed within the `4e-6` scale, indicating that the filtered samples had virtually no impact on the overall statistical distribution
- At the aggregate level, the filtered count `1,327` happens to equal `HC-7 (1,264) + HC-8 (63)`. The safer interpretation is that the removed samples were concentrated almost entirely in these two tail-risk categories, although the summary tables alone cannot prove that there was zero overlap

### 7.2 Hard Constraint Validation Results Analysis

#### Compliant Dataset: All Passed

| Constraint | Status | Total Checked | Violations | Pass Rate |
|------------|--------|---------------|------------|-----------|
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

#### Raw Dataset: Two Items Failed

| Constraint | Status | Violations | Pass Rate |
|------------|--------|------------|-----------|
| HC-7 | FAIL | 1,264 | 99.99873608897934% |
| HC-8 | FAIL | 63 | 99.99993700443488% |
| Remaining 11 items | PASS | 0 | 100% |

**Violation Analysis:**

- The raw v2 dataset now has only two tail-risk HC items left: `HC-7` with `1,264` violations and `HC-8` with `63` violations. Both proportions are extremely low.
- The result files alone can only confirm that "a small number of strict monotonicity violations remain in the final written data" and that "a very small number of forbidden single-phase boundary samples still exist." They cannot, by themselves, pinpoint a single root cause; that still requires tracing the violating `sample_id` values and, when needed, checking the underlying Parquet `recipe_group_id` or a recipe-group identity derived from `sample_id`.
- After compliant filtering, all HC items pass at 100%, indicating that the current filtering stage is sufficient to remove this remaining tail of violating samples.

### 7.3 Fidelity Assessment Results Analysis

The following is based primarily on the compliant dataset `run_id = 1776131049790`, because this is the final result of the second-stage "post-filter quality re-validation."

#### 7.3.1 Per-Dimension Score Overview

| Dimension | Score | Weight | Weighted Score | Rating |
|-----------|-------|--------|---------------|--------|
| Dopant Element | 0.983579854542093 | 15% | 0.14753697818131395 | EXCELLENT |
| Sintering Temperature | 0.9070683998357505 | 5% | 0.045353419991787526 | EXCELLENT |
| Synthesis Method | 0.8825280168513817 | 15% | 0.13237920252770724 | GOOD |
| Operating Temperature | 0.8739536607521723 | 10% | 0.08739536607521724 | GOOD |
| Processing Route | 0.8718796654473151 | 10% | 0.08718796654473152 | GOOD |
| Crystal Phase (Major) | 0.8705872327510655 | 10% | 0.08705872327510655 | GOOD |
| Dopant-Conductivity Correlation | 0.8446302026217843 | 5% | 0.04223151013108922 | GOOD |
| Dopant Molar Fraction | 0.8084145609916732 | 10% | 0.08084145609916732 | GOOD |
| log10(Conductivity) | 0.7702383645691955 | 20% | 0.1540476729138391 | GOOD |
| **Overall Score** |  |  | **0.8640322957399598** | **GOOD** |

**Rating Distribution:** 2 `EXCELLENT` + 7 `GOOD`

#### 7.3.2 Categorical Dimension Detailed Analysis

**Dopant Element: The Most Stable Dimension**

| Element | Real Proportion | Generated Proportion | Difference |
|---------|----------------|---------------------|------------|
| Y | 37.9022% | 34.0946% | -3.8075% |
| Sc | 32.9220% | 31.1573% | -1.7647% |
| Yb | 8.7704% | 10.5974% | +1.8270% |
| Ce | 7.0075% | 4.6830% | -2.3245% |
| Dy | 3.9665% | 3.7297% | -0.2368% |
| Bi | 3.1732% | 3.7207% | +0.5475% |

This dimension scores very high, indicating that the current dopant element frequency weights are already quite close to the real data.

**Synthesis Method: Deviations Caused by Both Enumeration and Weights**

| Method | Real Proportion | Generated Proportion | Difference |
|--------|----------------|---------------------|------------|
| Solid-state synthesis | 45.3738% | 45.6069% | +0.2331% |
| Commercialization | 19.9112% | 19.9960% | +0.0848% |
| Hydrothermal synthesis | 17.4685% | 4.9982% | -12.4704% |
| Sol–gel method | 8.8823% | 11.9936% | +3.1113% |
| Coprecipitation method | 0% | 10.0054% | +10.0054% |
| Coprecipitation | 0% | 2.0006% | +2.0006% |
| / | 2.1466% | 0.9964% | -1.1502% |

The deviations here are entirely consistent with the generator's current enumeration/weights:

- `Coprecipitation method` is fixed at about `10%` in the generator
- `Coprecipitation` additionally accounts for about `2%`

If these two categories do not exist or have different naming conventions in the real baseline, JSD will increase noticeably.

**Processing Route: The Current Generator Only Samples 6 Routes**

Some representative deviations are as follows:

| Route | Real Proportion | Generated Proportion | Difference |
|-------|-----------------|----------------------|------------|
| dry pressing | 80.0148% | 80.3938% | +0.3790% |
| 3D printing | 6.7358% | 0% | -6.7358% |
| vacuum filtration | 2.3686% | 0% | -2.3686% |
| RF sputtering | 0.1480% | 2.9953% | +2.8473% |
| spray pyrolysis | 0.0740% | 2.6006% | +2.5266% |
| spark plasma sintering | 0.0740% | 8.0041% | +7.9301% |

This result is entirely consistent with the code: although the dictionary table supports 19 routes, the generation distribution only actually samples 6, so many real categories are necessarily missing.

**Crystal Phase (Major): The Current Phase Distribution Clearly Deviates from Real Data**

| Crystal Phase | Real Proportion | Generated Proportion | Difference |
|--------------|----------------|---------------------|------------|
| 1 (Cubic) | 86.8891% | 53.0114% | -33.8776% |
| 2 (Tetragonal) | 6.2064% | 31.2935% | +25.0872% |
| 3 (Monoclinic) | 6.9046% | 9.2644% | +2.3598% |
| 4 (Orthogonal) | 0% | 2.3302% | +2.3302% |
| 5 (Rhombohedral) | 0% | 4.1004% | +4.1004% |

This indicates that the current `PHASE_DIST_*` parameters tend to generate more `tetragonal / orthogonal / rhombohedral`, whereas the real data is significantly skewed toward `cubic`.

#### 7.3.3 Numerical Dimension Detailed Analysis

**log10(Conductivity)**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|---------------|------------|
| Count | 1,351 | 100,005,713 | — |
| Mean | -2.1133 | -2.4624 | -0.3490 |
| Std | 0.9584 | 1.2755 | +0.3172 |
| Min | -7.01 | -8.00 | -0.99 |
| P5 | -3.8711 | -5.0520 | -1.1809 |
| P50 | -1.9452 | -2.1735 | -0.2283 |
| P95 | -0.8675 | -0.8941 | -0.0266 |
| Pct_RMSE | 0.083374 | 0.083374 | — |
| Std_Ratio | 0.751359 | 0.751359 | — |

Conclusions:

- `log10(Conductivity)` remains the lowest-scoring dimension, but it is now stably within `GOOD`
- The generated data is still biased toward lower conductivity values, with the lower tail deviating more strongly
- The mid-to-high quantiles are already fairly close, indicating that the main remaining gap lies in the lower tail and overall dispersion

**Operating Temperature**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|---------------|------------|
| Mean | 726.0264 | 711.3150 | -14.7114 |
| Std | 165.3772 | 187.6038 | +22.2266 |
| Min | 290 | 300 | +10 |
| P5 | 500 | 389 | -111 |
| P50 | 700 | 717 | +17 |
| P95 | 1000 | 1007 | +7 |
| Max | 1400 | 1363 | -37 |

This dimension performs well overall. The generation-side temperature lower bound is hard-clamped to 300°C, so the `290°C` present in the real data will not appear.

**Dopant Molar Fraction**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|---------------|------------|
| Mean | 0.0932518 | 0.0608552 | -0.0323966 |
| Std | 0.8658486 | 0.0418956 | -0.8240 |
| Min | 0.001 | 0.001 | 0 |
| P50 | 0.06 | 0.0527 | -0.0073 |
| P95 | 0.11 | 0.1337 | +0.0237 |
| Max | 35.0 | 0.2 | -34.8 |
| Std_Ratio | 0.0483867 | 0.0483867 | — |

This requires careful interpretation:

- The generation side is strictly constrained by solubility limits and total doping limits, with a maximum value of only `0.2`
- The real data side contains extreme values such as `35.0`, which significantly inflates the standard deviation

Whether to consider these extreme values as data entry errors cannot be determined from the result files alone. A more prudent statement is: **The numerical scope on the real data side and the physics constraint scope on the generation side are inconsistent, and the real data contains obvious outliers.**

**Sintering Temperature**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|---------------|------------|
| Mean | 1417.5916 | 1425.7452 | +8.1536 |
| Std | 147.1596 | 133.0138 | -14.1458 |
| Min | 425 | 1000 | +575 |
| P5 | 1100 | 1200 | +100 |
| P50 | 1400 | 1450 | +50 |
| P95 | 1600 | 1650 | +50 |
| Max | 1800 | 1650 | -150 |

This is consistent with the generator's current sintering range parameters:

- The generation side does not have sintering temperatures below `1000°C`
- The generation side also does not exceed `1650°C`

Therefore, although this dimension scores high, it is essentially because "the core range is relatively close," not because the full range is completely consistent.

#### 7.3.4 Correlation Dimension Analysis

`Dopant-Conductivity Correlation` has now improved into the `GOOD` range and is no longer the main bottleneck:

| Element | Real Data | Generated Data | Difference |
|---------|-----------|---------------|------------|
| Sc | -1.5381 | -1.5377 | +0.0004 |
| Y | -1.8322 | -1.8323 | -0.0001 |
| Dy | -1.7652 | -1.8174 | -0.0522 |
| Ca | -2.2362 | -2.3292 | -0.0930 |
| Pr | -2.3614 | -2.5490 | -0.1876 |
| Ce | -1.5147 | -1.7503 | -0.2356 |
| Yb | -2.5684 | -2.3218 | +0.2466 |
| Lu | -0.8599 | -1.2003 | -0.3404 |
| Bi | -1.4872 | -1.9490 | -0.4619 |

This indicates:

- `Sc` and `Y` are now almost perfectly aligned with the real values, indicating that the core dopant ranking is largely stable
- The larger remaining deviations are concentrated in `Bi`, `Lu`, `Ce`, and `Yb`
- If further optimization continues, the higher priority is to tighten the overall conductivity distribution first; this correlation dimension can then be tuned specifically around those elements

### 7.4 Results Comparison: Raw Data vs Compliant Data

| Metric | Raw Data | Compliant Data |
|--------|-----------------------------------|---------------------------------------------|
| Database | `ods_zirconia_rule_based_v2` | `ods_conductivity_compliant_rule_based_v2` |
| Sample Count | 100,007,040 | 100,005,713 |
| Filtered Count | — | 1,327 |
| HC Result | FAIL | PASS |
| HC Failed Items | HC-7: 1,264; HC-8: 63 | None |
| Fidelity Score | 0.8640317372280062 | 0.8640322957399598 |
| Fidelity Rating | GOOD | GOOD |

Conclusions:

- Compliant filtering removed only `1,327` samples, which is extremely small at this scale
- It had virtually no impact on overall Fidelity, and the composite score even improved slightly
- This shows that the first pass is mainly about removing a very small tail of non-compliant samples, while the second pass confirms that the filtered dataset still resembles real data
- The current v2 pipeline can now move the result from "statistically reasonable but still containing rule violations" to "also compliant at the rule level" without materially changing the distribution

---

## 8. Data Pipeline and Deployment

### 8.1 Execution Order

```
1. Build the project
   mvn clean package -DskipTests

2. Synchronize real data (MySQL → Hive)
   scripts/submit-mysql2hive.sh

3. Create Hive external tables for real data
   sql/hive/create_external_tables.sql

4. Generate rule-based data
   scripts/submit-plan-e-final-original-layout.sh
   Note: This step directly creates and writes to external tables under ods_zirconia_rule_based_v2

5. Run validation, with optional compliant data output
   scripts/submit-plan-e-final-original-layout-validator.sh
   scripts/submit-data-validator.sh

6. If compliant data directory has been produced, create Hive external tables for it
   sql/hive/conductivity_compliant_rule_based_v2.sql
```

Additional notes:

- `sql/hive/create_external_tables_rule_based_v2.sql` is useful when the raw v2 external tables need to be created explicitly; it is not a required step for the current HDFS submission scripts.
- `scripts/submit-data-validator.sh` currently contains multiple `spark-submit` sections, corresponding to raw data validation, validation with filtering, and re-validation of the compliant database.

### 8.2 Spark Resource Configuration (Generation Task)

| Parameter | Value |
|-----------|-------|
| num-executors | 6 |
| executor-memory | 6 GB |
| executor-cores | 4 |
| Recipe groups | 28,000,000 |
| Partitions | 2,000 |

### 8.3 Database Mapping

| Hive Database | Content | Source |
|--------------|---------|--------|
| `ods_zirconia_conductivity_v2` | Real experimental data | MySQL sync |
| `ods_zirconia_rule_based_v2` | Rule-generated raw data | data-generator-base-rule |
| `ods_conductivity_compliant_rule_based_v2` | Compliance-filtered data | data-validator filtered output |
| `conductivity_validation_rule_based` | Validation results | data-validator output |

---

## 9. Code Structure Index

### data-generator-base-rule

| Class | Path | Responsibility |
|-------|------|----------------|
| `DataGeneratorBaseRuleApp` | .../DataGeneratorBaseRuleApp.java | Entry point, Spark job orchestration |
| `RecipeGroupGenerator` | .../generator/RecipeGroupGenerator.java | Core generation logic, 6-step generation workflow |
| `PhysicsConstants` | .../config/PhysicsConstants.java | Physical constants, dopant element lookup table |
| `DopantProperty` | .../config/DopantProperty.java | Dopant element property definitions |
| `AppConfig` | .../config/AppConfig.java | Command-line argument parsing |
| `SynthesisMethod` | .../config/SynthesisMethod.java | Synthesis method enumeration |
| `ProcessingRoute` | .../config/ProcessingRoute.java | Processing route enumeration |
| `SinteringRange` | .../config/SinteringRange.java | Sintering temperature/duration ranges |
| `RandomUtils` | .../utils/RandomUtils.java | Beta/Gamma distribution sampling utilities |
| `WeightedItem<T>` | .../utils/WeightedItem.java | Weighted random sampling |
| `GeneratedGroup` | .../model/GeneratedGroup.java | Recipe group container |
| `MaterialSampleRow` | .../model/MaterialSampleRow.java | Sample row model |
| `SampleDopantRow` | .../model/SampleDopantRow.java | Dopant row model |
| `SinteringStepRow` | .../model/SinteringStepRow.java | Sintering row model |
| `CrystalPhaseRow` | .../model/CrystalPhaseRow.java | Crystal phase row model |

### data-sync-mysql2hive

| Class | Path | Responsibility |
|-------|------|----------------|
| `MysqlToHiveSyncApp` | .../sync/MysqlToHiveSyncApp.java | Entry point, Spark Session creation |
| `TableSyncExecutor` | .../sync/TableSyncExecutor.java | Synchronization orchestration for 7 tables |
| `SyncConfig` | .../sync/SyncConfig.java | JDBC connection parameters |

### data-validator

| Class | Path | Responsibility |
|-------|------|----------------|
| `DataValidatorApp` | .../validator/DataValidatorApp.java | Entry point, argument parsing |
| `DataValidator` | .../validator/DataValidator.java | HC hard constraint checks (Spark SQL) |
| `FidelityValidator` | .../validator/FidelityValidator.java | Fidelity statistical assessment |
| `HdfsResultSink` | .../validator/HdfsResultSink.java | Result writing to HDFS Parquet |
