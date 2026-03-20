# Zirconia Ionic Conductivity Data Enhancement System — Technical Documentation (Revised)

## Table of Contents

- [1. Project Overview](#1-project-overview)
- [2. System Architecture](#2-system-architecture)
- [3. Data Model and Table Structure](#3-data-model-and-table-structure)
  - [3.1 Dictionary Tables](#31-dictionary-tables)
  - [3.2 Real Data v2 and Synchronized Hive Tables](#32-real-data-v2-and-synchronized-hive-tables)
  - [3.3 Rule-Generated Data Hive Tables](#33-rule-generated-data-hive-tables)
  - [3.4 Structural Differences and Usage Notes](#34-structural-differences-and-usage-notes)
- [4. Data Generation Module (data-generator-base-rule)](#4-data-generation-module-data-generator-base-rule)
  - [4.1 Generation Architecture](#41-generation-architecture)
  - [4.2 Generation Workflow](#42-generation-workflow)
  - [4.3 14 Physics Rules](#43-14-physics-rules)
  - [4.4 Conductivity Calculation Model](#44-conductivity-calculation-model)
  - [4.5 Dopant Element Property Table](#45-dopant-element-property-table)
  - [4.6 Synthesis Methods and Processing Routes](#46-synthesis-methods-and-processing-routes)
- [5. Data Synchronization Module (data-sync-mysql2hive)](#5-data-synchronization-module-data-sync-mysql2hive)
- [6. Data Validation Module (data-validator)](#6-data-validation-module-data-validator)
  - [6.1 Hard Constraint Validation (HC)](#61-hard-constraint-validation-hc)
  - [6.2 Compliant Data Filtering](#62-compliant-data-filtering)
  - [6.3 Fidelity Assessment](#63-fidelity-assessment)
  - [6.4 Relationship Between HC and Fidelity](#64-relationship-between-hc-and-fidelity)
  - [6.5 Execution Modes and Output](#65-execution-modes-and-output)
- [7. Run Results Analysis](#7-run-results-analysis)
  - [7.1 Validation Run Overview](#71-validation-run-overview)
  - [7.2 Hard Constraint Validation Results](#72-hard-constraint-validation-results)
  - [7.3 Fidelity Assessment Results](#73-fidelity-assessment-results)
  - [7.4 Raw Data vs Compliant Data](#74-raw-data-vs-compliant-data)
- [8. Data Pipeline and Deployment](#8-data-pipeline-and-deployment)
- [9. Code Structure Index](#9-code-structure-index)

---

## 1. Project Overview

`material-conductivity-data-enhancer` is an Apache Spark-based zirconia (ZrO2) ionic conductivity data enhancement project covering three core capabilities:

- Large-scale rule-based synthetic data generation
- Synchronization of real MySQL experimental data to HDFS Parquet
- Hard constraint validation and statistical fidelity assessment for generated data

The project currently consists of 3 Maven submodules:

- `data-generator-base-rule`
- `data-sync-mysql2hive`
- `data-validator`

The system's goal is not simply "random data fabrication," but rather batch generation of ZrO2 solid electrolyte experimental samples — constrained by materials science principles — that are suitable for modeling and analysis. Quality is ensured through a two-layer validation mechanism:

- `HC (Hard Constraints)`: Checks whether data is valid and meets baseline rules
- `Fidelity`: Checks whether the generated distribution is similar to real experimental data

**Technology Stack**

| Component | Technology |
|-----------|------------|
| Compute Engine | Apache Spark 3.5.8 |
| Programming Language | Java 8 |
| Build Tool | Maven |
| Data Exchange / Persistence | Parquet |
| Real Data Source | MySQL 8 |
| Analysis and Comparison | Spark SQL + Hive External Tables |

---

## 2. System Architecture

The project directory structure is as follows:

```text
material-conductivity-data-enhancer/
├── data-generator-base-rule/    # Physics rule-based synthetic data generation
├── data-sync-mysql2hive/        # MySQL -> HDFS Parquet synchronization
├── data-validator/              # HC validation + Fidelity assessment + compliant filtering
├── docs/                        # Documentation
├── scripts/                     # spark-submit scripts
├── sql/                         # MySQL / Hive DDL
└── pom.xml                      # Parent POM
```

The complete data flow is as follows:

```text
┌──────────────────────┐
│ MySQL: zirconia_     │
│ conductivity_v2      │
└──────────┬───────────┘
           │ JDBC
           ▼
┌──────────────────────┐
│ data-sync-mysql2hive │
│ 7 tables to HDFS     │
└──────────┬───────────┘
           │ Parquet
           ▼
┌──────────────────────────────┐
│ Hive: ods_zirconia_          │
│ conductivity_v2              │
│ Serves as Fidelity real      │
│ baseline                     │
└──────────────────────────────┘

┌──────────────────────┐
│ data-generator-      │
│ base-rule            │
│ Generates and writes │
│ directly to Hive     │
└──────────┬───────────┘
           ▼
┌──────────────────────────────┐
│ Hive: ods_zirconia_          │
│ rule_based                   │
│ Raw rule-generated data      │
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
│ and various result   │                  │ compliant_rule_based         │
│ detail tables        │                  │ Data after compliant         │
│ (Parquet)            │                  │ filtering                    │
└──────────────────────┘                  └──────────────────────────────┘
```

There are two easily confused but important points:

1. `data-sync-mysql2hive` is only responsible for writing MySQL tables as Parquet; it does not create Hive external tables. The real data Hive tables require separately executing `sql/hive/create_external_tables.sql`.
2. `data-generator-base-rule` directly creates and writes to Hive external tables. Under the current HDFS execution approach, there is no need to additionally execute `sql/hive/create_external_tables_rule_based.sql`.

---

## 3. Data Model and Table Structure

### 3.1 Dictionary Tables

Three dictionary tables exist on both the real data side and the generated data side. Field definitions are as follows:

| Table Name | Key Fields | Description |
|------------|------------|-------------|
| `crystal_structure_dict` | `id`, `code`, `full_name` | Crystal phase dictionary; current values are `c/t/m/o/r/β` |
| `synthesis_method_dict` | `id`, `name` | Synthesis method dictionary; 9 enumerated values |
| `processing_route_dict` | `id`, `name` | Processing route dictionary; 19 enumerated values |

### 3.2 Real Data v2 and Synchronized Hive Tables

The real data source database is `zirconia_conductivity_v2`. The real experimental data in this database comes from the repository [lanhung/material-conductivity-data-clean](https://github.com/lanhung/material-conductivity-data-clean). `data-sync-mysql2hive` reads the original table structures and writes 7 tables to HDFS Parquet.

**material_samples**

| Field | Type | Description |
|-------|------|-------------|
| `sample_id` | INT | Sample primary key |
| `reference` | VARCHAR / STRING | Source identifier |
| `material_source_and_purity` | TEXT / STRING | Material source and purity description |
| `synthesis_method_id` | INT | Synthesis method dictionary foreign key |
| `processing_route_id` | INT | Processing route dictionary foreign key |
| `operating_temperature` | FLOAT / DOUBLE | Test temperature |
| `conductivity` | DOUBLE | Conductivity |

**sample_dopants**

| Field | Type | Description |
|-------|------|-------------|
| `id` | INT | Sub-table auto-increment primary key; exists only on the real data side |
| `sample_id` | INT | References main table |
| `dopant_element` | VARCHAR / STRING | Dopant element |
| `dopant_ionic_radius` | FLOAT / DOUBLE | Ionic radius |
| `dopant_valence` | INT | Valence |
| `dopant_molar_fraction` | FLOAT / DOUBLE | Dopant molar fraction |

**sintering_steps**

| Field | Type | Description |
|-------|------|-------------|
| `id` | INT | Sub-table auto-increment primary key; exists only on the real data side |
| `sample_id` | INT | References main table |
| `step_order` | INT | Sintering step sequence number |
| `sintering_temperature` | FLOAT / DOUBLE | Sintering temperature |
| `sintering_duration` | FLOAT / DOUBLE | Sintering duration |

**sample_crystal_phases**

| Field | Type | Description |
|-------|------|-------------|
| `sample_id` | INT | References main table |
| `crystal_id` | INT | Crystal phase dictionary foreign key |
| `is_major_phase` | BOOLEAN | Whether it is the major phase |

### 3.3 Rule-Generated Data Hive Tables

The logical tables written by the generation module are still 4 main fact tables, but there are two key differences compared to real data:

- All four fact tables additionally include `recipe_group_id`
- Sub-tables no longer retain the auto-increment `id` from the real data

Key fields on the generation side are as follows:

**material_samples**

| Field | Type | Description |
|-------|------|-------------|
| `sample_id` | BIGINT | Sample unique identifier |
| `recipe_group_id` | BIGINT | Recipe group ID |
| `reference` | STRING | Fixed value: `RULE_BASED_SYNTHETIC` |
| `material_source_and_purity` | STRING | Source text |
| `synthesis_method_id` | INT | Synthesis method dictionary foreign key |
| `processing_route_id` | INT | Processing route dictionary foreign key |
| `operating_temperature` | DOUBLE | Test temperature |
| `conductivity` | DOUBLE | Conductivity |

**sample_dopants**

| Field | Type | Description |
|-------|------|-------------|
| `sample_id` | BIGINT | References main table |
| `recipe_group_id` | BIGINT | Recipe group ID |
| `dopant_element` | STRING | Dopant element |
| `dopant_ionic_radius` | DOUBLE | Ionic radius |
| `dopant_valence` | INT | Valence |
| `dopant_molar_fraction` | DOUBLE | Dopant molar fraction |

**sintering_steps**

| Field | Type | Description |
|-------|------|-------------|
| `sample_id` | BIGINT | References main table |
| `recipe_group_id` | BIGINT | Recipe group ID |
| `step_order` | INT | Sintering step sequence number |
| `sintering_temperature` | DOUBLE | Sintering temperature |
| `sintering_duration` | DOUBLE | Sintering duration |

**sample_crystal_phases**

| Field | Type | Description |
|-------|------|-------------|
| `sample_id` | BIGINT | References main table |
| `recipe_group_id` | BIGINT | Recipe group ID |
| `crystal_id` | INT | Crystal phase dictionary foreign key |
| `is_major_phase` | BOOLEAN | Whether it is the major phase |

The `sample_id` encoding rule on the generation side is:

```text
sample_id = 10000001 + recipe_group_id * 8 + temperature_index
```

Here, `8` is used as a fixed block size for sample_id per recipe group, even if the actual number of temperature points is fewer than 8.

### 3.4 Structural Differences and Usage Notes

- The validator preferentially uses `recipe_group_id` to execute HC-7; only if the data lacks this column does it attempt to derive it from `sample_id`.
- On the real data side, `sample_dopants` / `sintering_steps` have an `id` column; the generated data side does not, and validation and Fidelity do not depend on these `id` values.
- In the code, `crystal_structure_dict` determines crystal phases like `c` / `m` via the `code` field, not via `full_name`.

---

## 4. Data Generation Module (data-generator-base-rule)

### 4.1 Generation Architecture

The generation unit is not individual samples, but **recipe groups**.

- A recipe group represents a fixed recipe: dopant composition, crystal phases, sintering conditions, and source description
- A single recipe group generates multiple test temperature points
- Samples within a recipe group share dopant/crystal phase/sintering information and differ only in `operating_temperature` and `conductivity`

Default parameters come from `AppConfig`:

| Parameter | Default Value |
|-----------|---------------|
| `totalRecipeGroups` | 25,000,000 |
| `numPartitions` | 1,000 |
| `outputPath` | `./output` |
| `hiveDatabase` | `ods_zirconia_rule_based` |

The current submission script `scripts/submit.sh` uses:

| Parameter | Actual Value |
|-----------|--------------|
| Recipe groups | 28,000,000 |
| Partitions | 2,000 |
| Output root directory | `/data/material_conductivity_data` |
| Hive database | `ods_zirconia_rule_based` |

The temperature point count distribution is `3/4/5/6/7/8 = 40%/30%/20%/5%/3%/2%`, with a theoretical average of approximately `4.07` temperature points per group. With 28,000,000 recipe groups, the expected sample count is approximately `113.96M`, consistent with the actual run result of `113,968,301` records.

### 4.2 Generation Workflow

The generation workflow consists of 6 fixed steps:

```text
Step 1: Generate dopants
  - Sample 1~5 elements by weight
  - Primary dopant uses the element's own Beta parameters for sampling
  - Co-dopants use Beta(2,5) * 0.08 for sampling
  - Apply per-element solubility limits and total doping amount limits

Step 2: Generate crystal phases
  - Select major phase based on primary dopant and total doping amount
  - Append second/third phases according to rules
  - Apply hard corrections for forbidden single-phase combinations

Step 3: Generate sintering steps
  - RF sputtering has no sintering
  - Other samples generate 1/2/3 steps by distribution
  - Ranges are determined by synthesis_method or SPS route

Step 4: Generate temperature sequence
  - First sample a center point from the discrete temperature distribution
  - Then expand with 50~100°C intervals
  - Sort, deduplicate, and fall back to equally-spaced sequence if needed

Step 5: Generate conductivity
  - Build a segmented Arrhenius model centered on primary dopant properties
  - Apply crystal phase, sintering, co-doping, ScSZ, transition metal corrections
  - Clamp to [1e-8, 1.0] then apply strict monotonicity correction

Step 6: Generate source text
  - Generate templated descriptions based on synthesis_method and dopants
```

### 4.3 14 Physics Rules

#### Rule 1: Arrhenius Temperature Dependence

The code uses a **segmented Arrhenius** model:

- Boundary point: `600°C`
- Reference temperature: `800°C`
- Boltzmann constant: `8.617333e-5 eV/K`

The high-temperature and low-temperature segments use different activation energies `EaHigh` / `EaLow`, with continuity at the boundary ensured through `sigma0High` / `sigma0Low`.

#### Rule 2: Fixed Mapping of Ionic Radius and Valence

The following properties for each dopant element come from a fixed lookup table and do not drift freely at runtime:

- `radius`
- `valence`
- `optimalFraction`
- `maxSolubility`
- `eaHighMin` / `eaHighMax`
- `eaLowMin` / `eaLowMax`
- `baseSigmaLog10`

#### Rule 3: Crystal Phase–Dopant Coupling

The major phase is primarily determined by the **primary dopant element** and **total doping amount**:

- `Sc` with `Sc >= 0.10`: Uses `PHASE_DIST_SC_HIGH`
- `Sc` with `Sc < 0.08`: Uses `PHASE_DIST_SC_LOW`
- Other samples:
  - `totalFrac >= 0.10` and primary dopant valence is `+2/+3`: `PHASE_DIST_HIGH_DOPING`
  - `0.05 <= totalFrac < 0.10`: `PHASE_DIST_MED_DOPING`
  - `totalFrac < 0.05`: `PHASE_DIST_LOW_DOPING`

Hard corrections for forbidden combinations cover only two single-phase cases:

- `totalFrac < 0.05` with single-phase `cubic`: Changed to single-phase `tetragonal`
- `totalFrac > 0.12` with single-phase `monoclinic`: Changed to single-phase `cubic`

#### Rule 4: At Least One +2 or +3 Dopant Element Required

Dopant generation attempts up to 50 retries; if after 50 attempts no `+2` or `+3` element has been sampled, it falls back to the default element `Y`.

#### Rule 5: Element Solubility Limits

Each element has an independent `maxSolubility`. The doping fraction is clipped before writing to the table:

```text
[MIN_MOLAR_FRACTION, maxSolubility]
```

#### Rule 6: Total Doping Amount Limit

The total doping amount upper limit is fixed at:

```text
SUM(dopant_molar_fraction) <= 0.30
```

If a newly sampled element would cause the total to exceed the limit, only the fraction corresponding to the "remaining available space" is retained.

#### Rule 7: Concentration Non-Monotonicity

The code increases the activation energy based on the degree of deviation of the primary dopant from its optimal concentration:

```text
concDeviation = |primaryFraction - optimalFraction|
eaPenalty = concDeviation * 2.0
```

The greater the deviation, the higher `EaHigh` / `EaLow` become, which is equivalent to a decrease in conductivity.

#### Rule 8: Element Type Correlates with Conductivity Parameters

Different elements use different:

- High-temperature activation energy ranges
- Low-temperature activation energy ranges
- 800°C baseline `log10(conductivity)` values

This is not expressed through an explicit "radius function," but rather indirectly through preset parameters in `DopantProperty`.

#### Rule 9: Sintering–Process Coupling

Sintering temperature/duration ranges are determined by synthesis method or processing route:

| Condition | Temperature Range (°C) | Duration Range (min) |
|-----------|------------------------|----------------------|
| `Solid-state synthesis` | 1400–1650 | 120–600 |
| `Commercialization` | 1300–1550 | 60–300 |
| `Sol–gel method` | 1200–1500 | 60–360 |
| `Coprecipitation` / `Coprecipitation method` | 1200–1500 | 60–360 |
| `Hydrothermal synthesis` | 1200–1500 | 60–360 |
| `Glycine method` | 1200–1500 | 60–360 |
| `Directional melt crystallization` | 1300–1550 | 60–360 |
| `spark plasma sintering` route | 1000–1300 | 3–30 |
| Other (default) | 1300–1550 | 60–360 |

#### Rule 10: Grain Boundary Effect

If `maxSinteringTemp < 1300°C`, an additional penalty is applied to `sigmaRefLog10`:

```text
0.1 + (1300 - maxSinteringTemp) / 1000 * 0.3
```

#### Rule 11: ScSZ Low-Temperature Degradation

If the following conditions are met:

- Primary dopant element is `Sc`
- `Sc < 0.10`
- `T < 650°C`

Then the conductivity at that temperature point is multiplied by a random factor of `0.2 ~ 0.5`.

#### Rule 12: Non-Additive Co-Doping Effect

When the number of dopant elements is `>= 3`, instead of simply multiplying by a fixed 5% factor, an additional penalty is applied to `sigmaRefLog10`:

```text
0.05 + (numDopants - 2) * 0.05
```

The corresponding values are:

- 3-element doping: `-0.10`
- 4-element doping: `-0.15`
- 5-element doping: `-0.20`

#### Rule 13: Transition Metal Enhancement

If the dopants include `Fe` or `Mn`, a random bonus of `0.04 ~ 0.14` is added to `sigmaRefLog10`.

#### Rule 14: RF Sputtering Thin Film Specifics

If `processing_route = RF sputtering`:

- No sintering steps are generated
- The center temperature in the temperature sequence is capped at `<= 800°C`

### 4.4 Conductivity Calculation Model

The conductivity generation in the code is not a simple single formula, but a three-layer composition:

1. First, determine the reference parameters related to the primary dopant
2. Then use the segmented Arrhenius formula to derive conductivity at each temperature point
3. Finally, apply strict monotonicity correction after clamping

**Layer 1: 800°C Reference Conductivity**

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

The phase adjustment is:

| Major Phase | Adjustment |
|-------------|------------|
| Cubic | 0 |
| Tetragonal | -0.3 |
| Monoclinic | -1.0 |
| Rhombohedral | -0.5 |
| Orthogonal | -0.8 |
| Other | -0.5 |

Then:

```text
sigmaRef = 10^(sigmaRefLog10)
```

**Layer 2: Segmented Arrhenius**

```text
sigma0High = sigmaRef * Tref * exp(EaHigh / (kB * Tref))
sigma0Low  = sigma0High * exp((EaLow - EaHigh) / (kB * Tboundary))
sigma(T)   = (sigma0 / T) * exp(-Ea / (kB * T))
```

Where:

- `Tref = 1073.15 K` (800°C)
- `Tboundary = 873.15 K` (600°C)
- `T >= 600°C` uses high-temperature segment parameters; otherwise uses low-temperature segment parameters

This is equivalent to a "segmented Arrhenius curve with 800°C as the reference point," with the `1/T` term explicitly retained.

**Layer 3: Low-Temperature Degradation, Noise, Clamping, and Monotonicity Correction**

For each temperature point:

1. If the ScSZ low-temperature degradation condition is met, multiply by `0.2~0.5`
2. Add `N(0, 0.05)` noise to `log10(sigma)`
3. Clamp to `[1e-8, 1.0]`
4. If after clamping `conductivity[i] <= conductivity[i-1]`, raise the current point to `1.01 ~ 1.06` times the previous point, but still capped at `1.0`

Therefore, HC-7 checks **strict monotonicity of the final written data**, not the unclamped curve from the theoretical model.

### 4.5 Dopant Element Property Table

The current code supports 20 dopant elements with the following properties:

| Element | Radius (pm) | Valence | Frequency | Optimal Fraction | Max Solubility | Beta(α/β/scale) | `EaHigh` (eV) | `EaLow` (eV) | `baseSigmaLog10` |
|---------|-------------|---------|-----------|------------------|----------------|------------------|----------------|---------------|------------------|
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

### 4.6 Synthesis Methods and Processing Routes

**9 Synthesis Methods Supported by the Dictionary Table**

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

**Current Generator Actual Sampling Weights**

| Method | Weight |
|--------|--------|
| Solid-state synthesis | 45.6% |
| Commercialization | 20.0% |
| Sol–gel method | 12.0% |
| Coprecipitation method | 10.0% |
| Hydrothermal synthesis | 5.0% |
| Glycine method | 3.0% |
| Coprecipitation | 2.0% |
| Directional melt crystallization | 1.4% |
| / | 1.0% |

**19 Processing Routes Supported by the Dictionary Table**

`/`, `3D printing`, `chemical vapor deposition`, `cutting and polishing`, `dry pressing`, `isostatic pressing`, `magnetron sputtering`, `metal-organic chemical vapor deposition`, `plasma spray deposition`, `pulsed laser deposition`, `RF sputtering`, `spark plasma sintering`, `spin coating`, `spray pyrolysis`, `tape casting`, `ultrasonic atomization`, `ultrasonic spray pyrolysis`, `vacuum filtration`, `vapor deposition`

**Current Generator Actually Samples Only 6 Routes**

| Route | Weight |
|-------|--------|
| dry pressing | 80.4% |
| spark plasma sintering | 8.0% |
| RF sputtering | 3.0% |
| tape casting | 3.0% |
| isostatic pressing | 3.0% |
| spray pyrolysis | 2.6% |

This is the direct reason why the Fidelity `Processing Route` dimension shows "many real categories are missing from the generated data."

---

## 5. Data Synchronization Module (data-sync-mysql2hive)

The responsibility of `data-sync-mysql2hive` is straightforward: read MySQL tables via JDBC and write them to HDFS Parquet by table name.

### 5.1 Functional Description

- Input: MySQL `zirconia_conductivity_v2`
- Real data source: `https://github.com/lanhung/material-conductivity-data-clean`
- Output: `<hdfsOutputPath>/<tableName>`
- Write mode: `Overwrite`
- Output format: Parquet

### 5.2 Synchronized Tables

7 tables are synchronized in a fixed set:

1. `crystal_structure_dict`
2. `synthesis_method_dict`
3. `processing_route_dict`
4. `material_samples`
5. `sample_dopants`
6. `sintering_steps`
7. `sample_crystal_phases`

### 5.3 Command Line Parameters

The program parameter format is:

```bash
spark-submit \
  --class com.lanhung.conductivity.sync.MysqlToHiveSyncApp \
  data-sync-mysql2hive/target/data-sync-mysql2hive-1.0-SNAPSHOT.jar \
  jdbc:mysql://<host>:<port>/zirconia_conductivity_v2 \
  <user> \
  <password> \
  [hdfs_output_path]
```

Where:

- The first 3 parameters are required: `mysql_url`, `mysql_user`, `mysql_password`
- The 4th parameter is optional: `hdfs_output_path`
- If the output path is omitted, the default value is `/data/material_conductivity_data/ods_zirconia_conductivity_v2`

### 5.4 Relationship with Hive

This module **does not create Hive tables**. If you want to access the synchronized results via Hive/Spark SQL, you need to additionally execute:

```text
sql/hive/create_external_tables.sql
```

---

## 6. Data Validation Module (data-validator)

The validation module provides 3 types of capabilities:

- `HC`: Hard constraint validation
- `Fidelity`: Statistical fidelity assessment
- `compliant filter`: Filtering compliant data from the raw generated dataset

### 6.1 Hard Constraint Validation (HC)

The current implementation has 13 check items:

| ID | Check Content | Scope |
|----|---------------|-------|
| HC-1 | All `conductivity > 0` | Record-level check |
| HC-2a | All `dopant_molar_fraction > 0` | Record-level check |
| HC-2b | Element solubility limits | Only checks 4 elements: `Sc/Ce/Y/Ca` |
| HC-3 | Total doping does not exceed 0.30 | Implementation threshold is `0.3005` |
| HC-4 | Each sample has at least one `+2/+3` dopant element | Group-level check |
| HC-5 | Each sample has exactly one major phase | Group-level check |
| HC-6a | `sample_dopants.sample_id` can all be found in the main table | Referential integrity |
| HC-6b | `sintering_steps.sample_id` can all be found in the main table | Referential integrity |
| HC-6c | `sample_crystal_phases.sample_id` can all be found in the main table | Referential integrity |
| HC-7 | Within the same recipe group, conductivity strictly increases as temperature increases | Uses `recipe_group_id` preferentially |
| HC-8 | Crystal phase–dopant forbidden combinations | Only checks "low-doping pure cubic" and "high-doping pure monoclinic" |
| HC-9 | `conductivity` in `[1e-8, 1.0]` | Range check |
| HC-10 | `operating_temperature` in `[300, 1400]` | Range check |

The determination rule is simple:

- An individual item is considered passed when `violation_count = 0`
- An entire HC round is considered passed only when all check items pass

In the `validation_run` table, the `overall_score` for the `HARD_CONSTRAINT` type is not an independent scoring model but rather **the minimum pass rate among all HC items**. Therefore, the `99.99335867962093` for this round of HC on the raw data is essentially the pass rate of HC-7, not "a separate 100-point scoring system."

### 6.2 Compliant Data Filtering

When `--compliant-output-path` is enabled, the validator additionally performs compliant filtering:

1. Collect the `sample_id` values that violate HC-1 / 2a / 2b / 3 / 4 / 5 / 7 / 8 / 9 / 10
2. Remove these samples via `LEFT ANTI JOIN`
3. Perform iterative convergence for HC-7

HC-7 requires iteration because after deleting a temperature point, the remaining points within the same recipe group may produce new `conductivity <= prev_conductivity` relationships. The code continues to remove newly violating HC-7 samples until no new violations remain.

The 4 filtered fact tables and available dictionary tables are rewritten to:

```text
<compliant-output-path>/
  ├── material_samples
  ├── sample_dopants
  ├── sintering_steps
  ├── sample_crystal_phases
  ├── synthesis_method_dict   (if available)
  ├── processing_route_dict   (if available)
  └── crystal_structure_dict  (if available)
```

### 6.3 Fidelity Assessment

The fidelity assessment answers the question "Does the generated data overall resemble real data?" It depends on:

```bash
--real-database <hiveDatabase>
```

Real data is not read directly from a local TSV directory, but rather from 4 real tables in the specified Hive database.

#### Assessment Dimensions and Weights

| Dimension | Weight | Type |
|-----------|--------|------|
| Synthesis Method | 15% | Categorical |
| Processing Route | 10% | Categorical |
| Dopant Element | 15% | Categorical |
| Crystal Phase (Major) | 10% | Categorical |
| log10(Conductivity) | 20% | Numerical |
| Operating Temperature | 10% | Numerical |
| Dopant Molar Fraction | 10% | Numerical |
| Sintering Temperature | 5% | Numerical |
| Dopant-Conductivity Correlation | 5% | Joint distribution |

#### Categorical Dimension Scoring

Categorical dimensions use Jensen-Shannon divergence. The similarity is defined in the code as:

```text
JSD(P, Q) = 0.5 * KL(P || M) + 0.5 * KL(Q || M)
M = (P + Q) / 2
normalizedJsd = JSD / ln(2)
similarity = 1 - normalizedJsd
```

Implementation details:

- `Synthesis Method` / `Processing Route` preferentially map IDs to names via dictionary tables
- If the dictionary table is missing, it degrades to comparing the string values of IDs
- `Crystal Phase (Major)` currently compares the string of the major phase `crystal_id`, not `full_name`

#### Numerical Dimension Scoring

Numerical dimensions use a composite score of 7 quantile points (P5/P10/P25/P50/P75/P90/P95) + mean + standard deviation:

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

Note:

- The documentation should not simplify this to an unscaled version `0.6*(1-pctRmse)+...`, as that would be inconsistent with the actual code
- `Pct_RMSE` and `Std_Ratio` are written directly as statistics to `fidelity_numerical`

#### Joint Distribution Scoring

The joint distribution dimension compares the correspondence between "primary dopant element vs average `log10(conductivity)`."

Selection criteria:

1. For each sample, select the dopant element with the highest molar fraction as the primary dopant
2. Temperature window is limited to `700~900°C`
3. Requires `conductivity > 0`
4. Each element must have at least 3 samples

The scoring formula is:

```text
corrScore = max(0, (pearson + 1) / 2)
rmseScore = max(0, 1 - normalizedRmse * 2)
similarity = 0.5 * corrScore + 0.5 * rmseScore
```

Here, it is not `|pearson|`; negative correlation explicitly lowers the score.

#### Overall Rating

| Score Range | Rating |
|-------------|--------|
| `>= 0.90` | `EXCELLENT` |
| `>= 0.75` | `GOOD` |
| `>= 0.60` | `FAIR` |
| `< 0.60` | `POOR` |

### 6.4 Relationship Between HC and Fidelity

These two types of validation are independent of each other:

- `HC` focuses on legality and rule baselines
- `Fidelity` focuses on statistical similarity

Therefore, the following scenarios are all possible:

| HC | Fidelity | Meaning |
|----|----------|---------|
| PASS | GOOD / EXCELLENT | Data is both valid and close to the real distribution |
| PASS | FAIR / POOR | Data is valid but the distribution does not sufficiently resemble real data |
| FAIL | GOOD | Overall distribution is close to real, but rule-violating samples still exist |
| FAIL | POOR | Both rule violations and distribution deviations exist |

### 6.5 Execution Modes and Output

#### Common Execution Modes

**Run HC only (still requires `--output-path`)**

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

**HC + Fidelity + Compliant Filtering**

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

If neither `--validate` nor `--fidelity` is passed, the program defaults to executing `HC`; however, `--output-path` is still required.

#### Output Directory

Under `--output-path`, the following results are appended in Parquet directory format:

| Directory | Content |
|-----------|---------|
| `validation_run` | One summary record per run |
| `hard_constraint_result` | One record per HC check item |
| `fidelity_summary` | One record per Fidelity dimension |
| `fidelity_categorical` | Real proportions / generated proportions for categorical dimensions |
| `fidelity_numerical` | Statistics and differences for numerical dimensions |
| `fidelity_correlation` | Per-element average `log10(conductivity)` comparison |

In `validation_run`:

- `run_type` takes values `HARD_CONSTRAINT` or `FIDELITY`
- The `HARD_CONSTRAINT` row has a `passed` value; `overall_grade` is null
- The `FIDELITY` row has an `overall_grade` value; `passed` is null

---

## 7. Run Results Analysis

The following analysis is based on actual run results from `2026-03-18`, with result files located at:

```text
/home/zxc/Desktop/run_result
```

These TSV files correspond to exported views of the validation output Parquet tables.

### 7.1 Validation Run Overview

There are 4 run records in total, corresponding to two rounds of database validation:

| run_id | Type | Database | Total Samples | Result | overall_score | run_at |
|--------|------|----------|---------------|--------|---------------|--------|
| 1773800129257 | HARD_CONSTRAINT | `ods_zirconia_rule_based` | 113,968,301 | FAIL | 99.99335867962093 | 2026-03-18 10:15:29 |
| 1773801307603 | FIDELITY | `ods_zirconia_rule_based` | 113,968,301 | GOOD | 0.846548985171353 | 2026-03-18 10:35:07 |
| 1773802503281 | HARD_CONSTRAINT | `conductivity_compliant_rule_based` | 113,960,665 | PASS | 100.0 | 2026-03-18 10:55:03 |
| 1773802681046 | FIDELITY | `conductivity_compliant_rule_based` | 113,960,665 | GOOD | 0.8465469724349198 | 2026-03-18 10:58:01 |

**Key Conclusions**

- The raw rule-generated data failed HC; after compliant filtering, all HC checks passed
- The compliant filtering reduced the sample count by `7,636` records, approximately `0.0067%` of the raw data
- The two rounds of Fidelity scores differ by only about `2.0e-6`, indicating that the filtered samples had virtually no impact on the overall statistical distribution

### 7.2 Hard Constraint Validation Results

#### Compliant Dataset: All Passed

| Constraint | Total Checked | Violations | Pass Rate |
|------------|---------------|------------|-----------|
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

#### Raw Dataset: Two Items Failed

| Constraint | Violations | Pass Rate |
|------------|------------|-----------|
| HC-7 | 7,569 | 99.99335867962093% |
| HC-8 | 67 | 99.99994121172342% |
| Remaining 11 items | 0 | 100% |

#### Results Interpretation

- `HC-7` indicates that the raw result set still contains samples where "within the same recipe group, adjacent temperature points have `conductivity <= prev_conductivity`." The code already performs monotonicity correction at the generation end, so this is better described as "strict monotonicity violations still exist in the final written data," rather than attributing it to a single root cause.
- Considering the implementation, **clamping to the upper bound of `1.0` creating a flat-top value** is a direction worth investigating first, but this is speculative and requires cross-referencing with the violating `sample_id` values.
- The 67 records for `HC-8` indicate that a small number of samples still triggered the two single-phase forbidden combination rules defined by the validator. The result files alone can only confirm "boundary samples exist" — they cannot pinpoint a single mismatch point in the generation logic.

### 7.3 Fidelity Assessment Results

The following is based primarily on the compliant dataset `run_id = 1773802681046`.

#### 7.3.1 Dimension Score Overview

| Dimension | Score | Weight | Weighted Score | Rating |
|-----------|-------|--------|----------------|--------|
| Dopant Element | 0.9835843887491135 | 15% | 0.147537658312367 | EXCELLENT |
| Sintering Temperature | 0.9070680363507568 | 5% | 0.045353401817537844 | EXCELLENT |
| Synthesis Method | 0.8825221129602756 | 15% | 0.13237831694404134 | GOOD |
| Operating Temperature | 0.8739760564322571 | 10% | 0.08739760564322571 | GOOD |
| Processing Route | 0.8718721676437372 | 10% | 0.08718721676437373 | GOOD |
| Crystal Phase (Major) | 0.8705712805578834 | 10% | 0.08705712805578834 | GOOD |
| Dopant Molar Fraction | 0.8084147878644956 | 10% | 0.08084147878644957 | GOOD |
| log10(Conductivity) | 0.7682388115930227 | 20% | 0.15364776231860455 | GOOD |
| Dopant-Conductivity Correlation | 0.5029280758506336 | 5% | 0.02514640379253168 | POOR |
| **Overall Score** |  |  | **0.8465469724349198** | **GOOD** |

The overall rating distribution is:

- 2 `EXCELLENT`
- 6 `GOOD`
- 1 `POOR`

#### 7.3.2 Categorical Dimension Analysis

**Dopant Element: The Most Stable Dimension**

| Element | Real Proportion | Generated Proportion | Difference |
|---------|-----------------|----------------------|------------|
| Y | 37.9022% | 34.0950% | -3.8071% |
| Sc | 32.9220% | 31.1544% | -1.7676% |
| Yb | 8.7704% | 10.5989% | +1.8285% |
| Ce | 7.0075% | 4.6839% | -2.3236% |
| Dy | 3.9665% | 3.7283% | -0.2382% |
| Bi | 3.1732% | 3.7215% | +0.5483% |

This dimension scores very high, indicating that the current dopant element frequency weights are already quite close to the real data.

**Synthesis Method: Deviations Caused by Both Enumeration and Weights**

| Method | Real Proportion | Generated Proportion | Difference |
|--------|-----------------|----------------------|------------|
| Solid-state synthesis | 45.3738% | 45.6001% | +0.2263% |
| Commercialization | 19.9112% | 19.9984% | +0.0872% |
| Hydrothermal synthesis | 17.4685% | 4.9986% | -12.4699% |
| Sol–gel method | 8.8823% | 11.9931% | +3.1108% |
| Coprecipitation method | 0% | 10.0086% | +10.0086% |
| / | 2.1466% | 0.9981% | -1.1485% |

The deviations here are entirely consistent with the generator's current enumeration/weights:

- `Coprecipitation method` is fixed at `10%` in the generator
- `Coprecipitation` additionally accounts for `2%`

If these two categories do not exist or have different naming conventions in the real baseline, JSD will increase noticeably.

**Processing Route: The Current Generator Only Samples 6 Routes**

Some representative deviations are as follows:

| Route | Real Proportion | Generated Proportion | Difference |
|-------|-----------------|----------------------|------------|
| dry pressing | 80.0148% | 80.3931% | +0.3783% |
| 3D printing | 6.7358% | 0% | -6.7358% |
| vacuum filtration | 2.3686% | 0% | -2.3686% |
| RF sputtering | 0.1480% | 2.9981% | +2.8501% |
| spray pyrolysis | 0.0740% | 2.6012% | +2.5272% |
| spark plasma sintering | 0.0740% | 8.0023% | +7.9283% |

This result is entirely consistent with the code: although the dictionary table supports 19 routes, the generation distribution only actually samples 6, so many real categories are necessarily missing.

**Crystal Phase (Major): The Current Phase Distribution Clearly Deviates from Real Data**

| Crystal Phase | Real Proportion | Generated Proportion | Difference |
|---------------|-----------------|----------------------|------------|
| 1 (Cubic) | 86.8891% | 53.0087% | -33.8804% |
| 2 (Tetragonal) | 6.2064% | 31.2968% | +25.0904% |
| 3 (Monoclinic) | 6.9046% | 9.2642% | +2.3596% |
| 4 (Orthogonal) | 0% | 2.3301% | +2.3301% |
| 5 (Rhombohedral) | 0% | 4.1003% | +4.1003% |

This indicates that the current `PHASE_DIST_*` parameters tend to generate more `tetragonal / orthogonal / rhombohedral`, whereas the real data is significantly skewed toward `cubic`.

#### 7.3.3 Numerical Dimension Analysis

**log10(Conductivity)**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|----------------|------------|
| Count | 1,351 | 113,960,665 | — |
| Mean | -2.1133 | -2.4391 | -0.3257 |
| Std | 0.9584 | 1.3061 | +0.3477 |
| Min | -7.01 | -8.00 | -0.99 |
| P5 | -3.8711 | -5.0597 | -1.1886 |
| P50 | -1.9452 | -2.1767 | -0.2315 |
| P95 | -0.8675 | -0.7925 | +0.0750 |
| Pct_RMSE | 0.083643 | 0.083643 | — |
| Std_Ratio | 0.733785 | 0.733785 | — |

Conclusions:

- The generated data is overall biased toward lower conductivity values
- The distribution is wider than the real data
- The high-quantile range is relatively close, while the low-quantile range shows more noticeable deviation

**Operating Temperature**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|----------------|------------|
| Mean | 726.0264 | 711.3070 | -14.7194 |
| Std | 165.3772 | 187.5754 | +22.1982 |
| Min | 290 | 300 | +10 |
| P5 | 500 | 389 | -111 |
| P50 | 700 | 717 | +17 |
| P95 | 1000 | 1007 | +7 |
| Max | 1400 | 1363 | -37 |

This dimension performs well overall. The generation-side temperature lower bound is hard-clamped to 300°C, so the `290°C` present in the real data will not appear.

**Dopant Molar Fraction**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|----------------|------------|
| Mean | 0.0932518 | 0.0608535 | -0.0323983 |
| Std | 0.8658486 | 0.0418967 | -0.8239519 |
| Min | 0.001 | 0.001 | 0 |
| P50 | 0.06 | 0.0527 | -0.0073 |
| P95 | 0.11 | 0.1337 | +0.0237 |
| Max | 35.0 | 0.2 | -34.8 |
| Std_Ratio | 0.0483880 | 0.0483880 | — |

This requires careful interpretation:

- The generation side is strictly constrained by solubility limits and total doping limits, with a maximum value of only `0.2`
- The real data side contains extreme values such as `35.0`, which significantly inflates the standard deviation

Whether to consider these extreme values as data entry errors cannot be determined from the result files alone. A more prudent statement is: **The numerical scope on the real data side and the physics constraint scope on the generation side are inconsistent, and the real data contains obvious outliers.**

**Sintering Temperature**

| Statistic | Real Data | Generated Data | Difference |
|-----------|-----------|----------------|------------|
| Mean | 1417.5916 | 1425.7480 | +8.1564 |
| Std | 147.1596 | 133.0144 | -14.1452 |
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

`Dopant-Conductivity Correlation` is the weakest dimension currently:

| Element | Real Value | Generated Value | Difference |
|---------|------------|-----------------|------------|
| Sc | -1.5381 | -1.3076 | +0.2305 |
| Y | -1.8322 | -1.9924 | -0.1602 |
| Ca | -2.2362 | -2.4091 | -0.1729 |
| Pr | -2.3614 | -2.7386 | -0.3772 |
| Dy | -1.7652 | -2.2373 | -0.4721 |
| Lu | -0.8599 | -1.4305 | -0.5706 |
| Ce | -1.5147 | -2.1204 | -0.6057 |
| Yb | -2.5684 | -1.7317 | +0.8367 |
| Bi | -1.4872 | -2.6388 | -1.1517 |

This indicates:

- The relative ranking and absolute values of each primary dopant element in the 700~900°C range still show significant deviations
- Optimization priority should focus on `baseSigmaLog10`, activation energy ranges, and the combined effects of crystal phase/sintering corrections

### 7.4 Raw Data vs Compliant Data

| Metric | Raw Data | Compliant Data |
|--------|----------|----------------|
| Database | `ods_zirconia_rule_based` | `conductivity_compliant_rule_based` |
| Sample Count | 113,968,301 | 113,960,665 |
| Filtered Count | — | 7,636 |
| HC Result | FAIL | PASS |
| HC Failed Items | HC-7: 7,569; HC-8: 67 | None |
| Fidelity Score | 0.846548985171353 | 0.8465469724349198 |
| Fidelity Rating | GOOD | GOOD |

Conclusions:

- Compliant filtering removed only a very small number of samples
- It had virtually no impact on overall Fidelity
- However, it did elevate the raw data from "statistical distribution looks reasonable but rule violations exist" to "also compliant at the rule level"

---

## 8. Data Pipeline and Deployment

### 8.1 Recommended Execution Order

```text
1. Build the project
   mvn clean package -DskipTests

2. Synchronize real data to HDFS
   scripts/submit-mysql2hive.sh

3. Create Hive external tables for real data
   sql/hive/create_external_tables.sql

4. Generate rule-based data
   scripts/submit.sh
   Note: This step directly creates and writes to external tables under ods_zirconia_rule_based

5. Run validation, with optional compliant data output
   scripts/submit-data-validator.sh

6. If compliant data directory has been produced, create Hive external tables for it
   sql/hive/conductivity_compliant_rule_based.sql
```

Additional notes:

- `sql/hive/create_external_tables_rule_based.sql` is suitable for the local default `./output` scenario or manual replay, and is not a required step for the current HDFS submission scripts.
- `scripts/submit-data-validator.sh` currently contains multiple `spark-submit` sections, corresponding to raw data validation, validation with filtering, and re-validation of the compliant database.

### 8.2 Current Script Resource Configuration

**Generation task (`scripts/submit.sh`)**

| Parameter | Value |
|-----------|-------|
| deploy-mode | client |
| num-executors | 6 |
| executor-memory | 6g |
| executor-cores | 4 |
| driver-memory | 8g |
| `spark.default.parallelism` | 2000 |
| `spark.sql.shuffle.partitions` | 2000 |
| Recipe groups | 28,000,000 |
| Partitions | 2,000 |

**Validation task (main configurations in the script)**

| Phase | deploy-mode | executors | executor-memory | executor-cores |
|-------|-------------|-----------|-----------------|----------------|
| Raw DB HC + Fidelity | cluster | 12 | 8g | 6 |
| With compliant filtering output | client / cluster (both in script) | 6 | 6g | 4 |
| Compliant DB re-validation | cluster | 6 | 6g | 4 |

### 8.3 Database and Path Mapping

| Name | Role | Typical Path |
|------|------|--------------|
| `zirconia_conductivity_v2` | MySQL real source database | MySQL |
| `ods_zirconia_conductivity_v2` | Real data Hive external database | `/data/material_conductivity_data/ods_zirconia_conductivity_v2` |
| `ods_zirconia_rule_based` | Raw rule-generated data | `/data/material_conductivity_data/ods_zirconia_rule_based` |
| `conductivity_compliant_rule_based` | Compliant-filtered generated data | `/data/material_conductivity_data/conductivity_compliant_rule_based` |
| `conductivity_validation_rule_based` | Validation results directory / upstream data source for the database | `/data/material_conductivity_data/conductivity_validation_rule_based` |

---

## 9. Code Structure Index

### data-generator-base-rule

| Class | Path | Responsibility |
|-------|------|----------------|
| `DataGeneratorBaseRuleApp` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/DataGeneratorBaseRuleApp.java` | Entry point; creates databases, tables, and writes generated data |
| `RecipeGroupGenerator` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/generator/RecipeGroupGenerator.java` | Core recipe group generation logic |
| `SourceTextGenerator` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/generator/SourceTextGenerator.java` | Generates source text |
| `AppConfig` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/AppConfig.java` | Parameter parsing |
| `PhysicsConstants` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/PhysicsConstants.java` | Constants, distributions, element parameters |
| `DopantProperty` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/DopantProperty.java` | Dopant element property definitions |
| `SinteringRange` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/SinteringRange.java` | Sintering range |
| `SynthesisMethod` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/SynthesisMethod.java` | Synthesis method constants |
| `ProcessingRoute` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/config/ProcessingRoute.java` | Processing route constants |
| `RandomUtils` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/util/RandomUtils.java` | Random sampling utilities |
| `WeightedItem` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/util/WeightedItem.java` | Weighted sampling structure |
| `GeneratedGroup` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/GeneratedGroup.java` | Recipe group container |
| `MaterialSampleRow` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/MaterialSampleRow.java` | Main table row model |
| `SampleDopantRow` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/SampleDopantRow.java` | Dopant row model |
| `SinteringStepRow` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/SinteringStepRow.java` | Sintering row model |
| `CrystalPhaseRow` | `data-generator-base-rule/src/main/java/com/lanhung/conductivity/model/CrystalPhaseRow.java` | Crystal phase row model |

### data-sync-mysql2hive

| Class | Path | Responsibility |
|-------|------|----------------|
| `MysqlToHiveSyncApp` | `data-sync-mysql2hive/src/main/java/com/lanhung/conductivity/sync/MysqlToHiveSyncApp.java` | Synchronization entry point |
| `TableSyncExecutor` | `data-sync-mysql2hive/src/main/java/com/lanhung/conductivity/sync/task/TableSyncExecutor.java` | JDBC reading and Parquet writing for 7 tables |
| `SyncConfig` | `data-sync-mysql2hive/src/main/java/com/lanhung/conductivity/sync/config/SyncConfig.java` | JDBC and output path configuration |

### data-validator

| Class | Path | Responsibility |
|-------|------|----------------|
| `DataValidatorApp` | `data-validator/src/main/java/com/lanhung/conductivity/validator/DataValidatorApp.java` | Parameter parsing, workflow orchestration |
| `DataValidator` | `data-validator/src/main/java/com/lanhung/conductivity/validation/DataValidator.java` | HC validation and compliant filtering |
| `FidelityValidator` | `data-validator/src/main/java/com/lanhung/conductivity/validation/FidelityValidator.java` | Fidelity assessment |
| `HdfsResultSink` | `data-validator/src/main/java/com/lanhung/conductivity/validation/HdfsResultSink.java` | Writing results to Parquet directories |
