# Zirconia Ionic Conductivity Data Enhancement System — Technical Documentation

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
| sample_id | BIGINT | Unique identifier (`10000001 + recipe_group_id * 8 + temp_index`) |
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
# Hard constraint validation only
spark-submit --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based --validate

# Hard constraint + fidelity assessment
spark-submit --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/material_conductivity_data/conductivity_validation_rule_based

# With compliant data filtered output
spark-submit --class com.lanhung.conductivity.validator.DataValidatorApp \
  data-validator-1.0-SNAPSHOT.jar \
  --database ods_zirconia_rule_based \
  --validate --fidelity \
  --real-database ods_zirconia_conductivity_v2 \
  --output-path /data/.../conductivity_validation_rule_based \
  --compliant-output-path /data/.../conductivity_compliant_rule_based
```

---

## 7. Run Results Analysis

The following analysis is based on actual run results from 2026-03-18. A total of **2 validations** were performed: the first ran HC + Fidelity validation on the raw generated data (`ods_zirconia_rule_based`), and after discovering minor violations, compliant data was filtered into `conductivity_compliant_rule_based`. A second HC + Fidelity validation was then performed on the filtered data, producing 4 validation records in total.

### 7.1 Validation Run Overview

| run_id | Validation Type | Target Database | Total Samples | Pass/Rating | Overall Score | Run Time |
|--------|----------------|-----------------|---------------|-------------|--------------|----------|
| 1773802503281 | HC Hard Constraint | conductivity_compliant_rule_based | 113,960,665 | **PASS** | 100 | 10:55:03 |
| 1773800129257 | HC Hard Constraint | ods_zirconia_rule_based | 113,968,301 | **FAIL** | 99.993 | 10:15:29 |
| 1773802681046 | Fidelity | conductivity_compliant_rule_based | 113,960,665 | **GOOD** | 0.8465 | 10:58:01 |
| 1773801307603 | Fidelity | ods_zirconia_rule_based | 113,968,301 | **GOOD** | 0.8465 | 10:35:07 |

**Key Findings:**

- The first validation found that the raw data (`ods_zirconia_rule_based`) failed HC. After filtering out non-compliant data, the compliant dataset (`conductivity_compliant_rule_based`) achieved 100% HC pass rate in the second validation
- The fidelity scores of both databases are nearly identical (0.8465 vs 0.8465), indicating that the filtered non-compliant samples were negligible in number (only ~7,636 records) with no perceptible impact on overall distribution
- Total sample difference: 113,968,301 - 113,960,665 = **7,636 records filtered out** (0.0067%)

### 7.2 Hard Constraint Validation Results Analysis

#### Compliant Dataset (conductivity_compliant_rule_based) — All Passed

| Constraint | Status | Total Checked | Violations | Pass Rate |
|------------|--------|---------------|------------|-----------|
| HC-1: Conductivity positive | PASS | 113,960,665 | 0 | 100% |
| HC-2a: Molar fraction positive | PASS | 190,961,722 | 0 | 100% |
| HC-2b: Element solubility | PASS | 190,961,722 | 0 | 100% |
| HC-3: Total doping ≤ 0.30 | PASS | 113,960,665 | 0 | 100% |
| HC-4: Oxygen vacancy guarantee | PASS | 113,960,665 | 0 | 100% |
| HC-5: Single major phase | PASS | 113,960,665 | 0 | 100% |
| HC-6a: Referential integrity (dopants) | PASS | 113,960,665 | 0 | 100% |
| HC-6b: Referential integrity (sintering) | PASS | 110,543,958 | 0 | 100% |
| HC-6c: Referential integrity (crystal phases) | PASS | 113,960,665 | 0 | 100% |
| HC-7: Monotonicity | PASS | 113,960,665 | 0 | 100% |
| HC-8: Phase–dopant coupling | PASS | 113,960,665 | 0 | 100% |
| HC-9: Conductivity range | PASS | 113,960,665 | 0 | 100% |
| HC-10: Temperature range | PASS | 113,960,665 | 0 | 100% |

#### Raw Dataset (ods_zirconia_rule_based) — Two Constraints Had Violations

| Constraint | Status | Violations | Pass Rate |
|------------|--------|------------|-----------|
| HC-7: Monotonicity | **FAIL** | 7,569 | 99.993% |
| HC-8: Phase–dopant coupling | **FAIL** | 67 | 99.99994% |
| Remaining 11 items | PASS | 0 | 100% |

**Violation Analysis:**

- **HC-7 Monotonicity violations (7,569 records):** Approximately 0.0066% of samples exhibited temperature increase without monotonic conductivity increase within the same recipe group. This is likely caused by very small numerical reversals during floating-point truncation after multiple physical correction factors are combined.
- **HC-8 Phase–dopant coupling violations (67 records):** A very small number of samples had crystal phase selections inconsistent with their dopant type/concentration. The proportion is approximately 0.6 per million — an edge case in the generation logic.

### 7.3 Fidelity Assessment Results Analysis

The following analysis focuses on the compliant dataset (run_id: 1773802681046).

#### 7.3.1 Per-Dimension Score Overview

| Dimension | Score | Weight | Weighted Score | Rating |
|-----------|-------|--------|---------------|--------|
| Dopant Element | **0.9836** | 15% | 0.1475 | EXCELLENT |
| Sintering Temperature | **0.9071** | 5% | 0.0454 | EXCELLENT |
| Synthesis Method | 0.8825 | 15% | 0.1324 | GOOD |
| Operating Temperature | 0.8740 | 10% | 0.0874 | GOOD |
| Processing Route | 0.8719 | 10% | 0.0872 | GOOD |
| Crystal Phase (Major) | 0.8706 | 10% | 0.0871 | GOOD |
| Dopant Molar Fraction | 0.8084 | 10% | 0.0808 | GOOD |
| log₁₀(Conductivity) | 0.7682 | 20% | 0.1536 | GOOD |
| Dopant-Conductivity Correlation | **0.5029** | 5% | 0.0251 | POOR |
| **Overall Score** | | | **0.8465** | **GOOD** |

**Rating Distribution:** 2 EXCELLENT + 6 GOOD + 1 POOR

#### 7.3.2 Categorical Dimension Detailed Analysis

**Dopant Element (Score 0.9836, EXCELLENT)**

Dopant element distribution is the best among all dimensions, with major element proportions closely matching:

| Element | Real Proportion | Generated Proportion | Difference |
|---------|----------------|---------------------|------------|
| Y | 37.90% | 34.09% | -3.81% |
| Sc | 32.92% | 31.16% | -1.77% |
| Yb | 8.77% | 10.60% | +1.83% |
| Ce | 7.01% | 4.68% | -2.32% |
| Dy | 3.97% | 3.73% | -0.24% |
| Bi | 3.17% | 3.72% | +0.55% |

The main deviations are concentrated in Y and Ce, but all overall deviations are kept within 4%.

**Synthesis Method (Score 0.8825, GOOD)**

| Method | Real Proportion | Generated Proportion | Difference |
|--------|----------------|---------------------|------------|
| Solid-state synthesis | 45.37% | 45.60% | +0.23% |
| Commercialization | 19.91% | 20.00% | +0.09% |
| Hydrothermal synthesis | 17.47% | 5.00% | **-12.47%** |
| Sol–gel method | 8.88% | 11.99% | +3.11% |
| Coprecipitation method | 0% | 10.01% | **+10.01%** |
| (unknown) | 4.59% | 0% | -4.59% |

The main deviations come from: Hydrothermal synthesis being 12.5 percentage points too low, and Coprecipitation method not existing in the real data but accounting for 10% in generated data. This is because this category is classified differently in the real data.

**Processing Route (Score 0.8719, GOOD)**

Dry pressing dominates in both real and generated data (~80%), with good matching. Main deviations:

- 3D printing: real 6.74%, generated 0% (missing)
- spark plasma sintering: real 0.07%, generated 8.00% (too high)
- vacuum filtration: real 2.37%, generated 0% (missing)

Some niche processing routes are missing or have imbalanced proportions in the generated data.

**Crystal Phase (Score 0.8706, GOOD)**

| Crystal Phase | Real Proportion | Generated Proportion | Difference |
|--------------|----------------|---------------------|------------|
| 1 (Cubic) | 86.89% | 53.01% | **-33.88%** |
| 2 (Tetragonal) | 6.21% | 31.29% | **+25.09%** |
| 3 (Monoclinic) | 6.90% | 9.26% | +2.36% |
| 4 (Orthogonal) | 0% | 2.33% | +2.33% |
| 5 (Rhombohedral) | 0% | 4.10% | +4.10% |

This is the categorical dimension with the largest deviation. Cubic phase accounts for 86.89% in real data but only 53.01% in generated data, a gap of 34 percentage points. Tetragonal phase expanded from 6.21% in real data to 31.29%. This suggests that the crystal phase–dopant coupling rule (Rule 3) parameters need further tuning.

#### 7.3.3 Numerical Dimension Detailed Analysis

**log₁₀(Conductivity) (Score 0.7682, GOOD)**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|---------------|------------|
| Sample count | 1,351 | 113,960,665 | — |
| Mean | -2.113 | -2.439 | -0.326 |
| Std Dev | 0.958 | 1.306 | +0.348 |
| Min | -7.01 | -8.00 | -0.99 |
| P5 | -3.871 | -5.060 | -1.189 |
| P25 | -2.623 | -3.123 | -0.500 |
| **P50** | **-1.945** | **-2.177** | **-0.232** |
| P75 | -1.432 | -1.483 | -0.051 |
| P95 | -0.868 | -0.793 | +0.075 |
| Pct_RMSE | — | 0.0836 | — |
| Std_Ratio | — | 0.734 | — |

Generated data is skewed toward lower conductivity overall (mean lower by 0.33 log units) with a wider distribution (std dev larger by 0.35). High-percentile ranges (P75, P90, P95) match well, but low-percentile ranges (P5, P10) show larger deviations.

**Operating Temperature (Score 0.8740, GOOD)**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|---------------|------------|
| Mean | 726.0°C | 711.3°C | -14.7°C |
| Std Dev | 165.4°C | 187.6°C | +22.2°C |
| P50 | 700°C | 717°C | +17°C |
| P5 | 500°C | 389°C | -111°C |
| P95 | 1000°C | 1007°C | +7°C |
| Pct_RMSE | — | 0.0524 | — |

Temperature distribution matches well, with only a 17°C median difference. The low-temperature end (P5) shows a larger deviation (-111°C), indicating that generated data samples more broadly in the low-temperature region.

**Dopant Molar Fraction (Score 0.8084, GOOD)**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|---------------|------------|
| Mean | 0.0933 | 0.0609 | -0.032 |
| Std Dev | 0.866 | 0.042 | -0.824 |
| P50 | 0.060 | 0.053 | -0.007 |
| Max | 35.0 | 0.2 | -34.8 |
| Std_Ratio | — | 0.048 | — |

The real data has an abnormally large standard deviation (0.866), indicating the presence of extreme outliers (maximum value of 35.0, far exceeding normal doping ranges). Generated data strictly follows solubility limits (max = 0.2), resulting in an extremely low standard deviation ratio (Std_Ratio = 0.048). However, this precisely reflects the strict physical reasonableness control in generated data — the extreme values in real data may be data entry errors. The median match (0.060 vs 0.053) is very close.

**Sintering Temperature (Score 0.9071, EXCELLENT)**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|---------------|------------|
| Mean | 1417.6°C | 1425.7°C | +8.2°C |
| Std Dev | 147.2°C | 133.0°C | -14.1°C |
| P50 | 1400°C | 1450°C | +50°C |
| Min | 425°C | 1000°C | +575°C |
| Pct_RMSE | — | 0.039 | — |

Sintering temperature matching is excellent. The generated data minimum is 1000°C (constrained by the sintering–synthesis method coupling rule), while real data contains a few extremely low temperature records (425°C). The core range of the overall distribution is highly consistent.

#### 7.3.4 Correlation Dimension Analysis

**Dopant-Conductivity Correlation (Score 0.5029, POOR)**

This is the only dimension rated POOR. Average log₁₀(Conductivity) comparison per element:

| Element | Real Data | Generated Data | Difference |
|---------|-----------|---------------|------------|
| Sc | -1.538 | -1.308 | +0.230 |
| Y | -1.832 | -1.992 | -0.160 |
| Ca | -2.236 | -2.409 | -0.173 |
| Pr | -2.361 | -2.739 | -0.377 |
| Dy | -1.765 | -2.237 | -0.472 |
| Lu | -0.860 | -1.431 | -0.571 |
| Ce | -1.515 | -2.120 | -0.606 |
| Yb | -2.568 | -1.732 | **+0.837** |
| Bi | -1.487 | -2.639 | **-1.152** |

Main sources of deviation:
- **Bi (Bismuth):** Generated data conductivity is 1.15 log units lower — the largest deviation
- **Yb (Ytterbium):** Generated data conductivity is 0.84 log units higher — opposite direction
- **Ce (Cerium), Lu (Lutetium):** Deviations both exceed 0.5 log units

This indicates systematic deviations between each dopant element's base conductivity parameter (`baseConductivity`) and activation energy parameters compared to real experimental data, representing a key direction for future optimization.

### 7.4 Results Comparison: Raw Data vs Compliant Data

| Metric | Raw Data (ods_zirconia_rule_based) | Compliant Data (conductivity_compliant_rule_based) |
|--------|-----------------------------------|---------------------------------------------|
| Sample count | 113,968,301 | 113,960,665 |
| Filtered count | — | 7,636 records (0.0067%) |
| HC Validation | FAIL (HC-7: 7569, HC-8: 67) | **PASS** (all 100%) |
| Fidelity Overall Score | 0.8465 | 0.8465 |
| Fidelity Rating | GOOD | GOOD |

Conclusion: Compliance filtering removed only a very small number of samples (0.0067%), with no perceptible impact on fidelity scores, while bringing all hard constraint pass rates to 100%.

---

## 8. Data Pipeline and Deployment

### 8.1 Execution Order

```
1. Build the project
   mvn clean package -DskipTests

2. Synchronize real data (MySQL → Hive)
   scripts/submit-mysql2hive.sh

3. Create Hive external tables
   sql/hive/create_external_tables.sql            # Real data tables
   sql/hive/create_external_tables_rule_based.sql  # Generated data tables

4. Generate synthetic data
   scripts/submit.sh

5. Validation + compliance filtering
   scripts/submit-data-validator.sh

6. Create compliant data Hive table
   sql/hive/conductivity_compliant_rule_based.sql
```

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
| `ods_zirconia_rule_based` | Rule-generated raw data | data-generator-base-rule |
| `conductivity_compliant_rule_based` | Compliance-filtered data | data-validator filtered output |
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
