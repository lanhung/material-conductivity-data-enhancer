# Fidelity Evaluation Methodology

This document describes in detail the fidelity evaluation framework for comparing generated data against real experimental data. The evaluation is performed by `FidelityValidator`, which compares the statistical distributions of 1,351 real ZrO₂ ionic conductivity experimental records with those of the generated data, and outputs a composite confidence score ranging from 0 to 1 (real data = 1.0).

## 1. Evaluation Dimensions and Weights

| # | Dimension | Type | Weight | Evaluation Method |
|---|-----------|------|--------|-------------------|
| 1 | Synthesis Method | Categorical | 15% | Jensen-Shannon Divergence |
| 2 | Processing Route | Categorical | 10% | Jensen-Shannon Divergence |
| 3 | Dopant Element | Categorical | 15% | Jensen-Shannon Divergence |
| 4 | Crystal Phase / Major | Categorical | 10% | Jensen-Shannon Divergence |
| 5 | log₁₀(Conductivity) | Numerical | 20% | Quantile RMSE Composite Score |
| 6 | Operating Temperature | Numerical | 10% | Quantile RMSE Composite Score |
| 7 | Dopant Molar Fraction | Numerical | 10% | Quantile RMSE Composite Score |
| 8 | Sintering Temperature | Numerical | 5% | Quantile RMSE Composite Score |
| 9 | Dopant-Conductivity Correlation | Joint | 5% | Pearson Correlation + Normalized RMSE |

**Overall Score** = Sum(dimension score x weight), with weights totaling 100%.

## 2. Categorical Distribution Evaluation (Dimensions 1-4)

### Method: Jensen-Shannon Divergence (JSD)

For both real and generated data, compute the frequency distribution P and Q over each category, then calculate JSD:

```
M = (P + Q) / 2
JSD(P, Q) = 0.5 × KL(P ‖ M) + 0.5 × KL(Q ‖ M)
```

Where KL divergence is: `KL(P ‖ M) = Σ pᵢ × ln(pᵢ / mᵢ)`

JSD ranges over [0, ln2]. After normalization:

```
Normalized JSD = JSD / ln(2)      ∈ [0, 1]
Similarity = 1 - Normalized JSD   ∈ [0, 1]
```

- **1.0** = the two distributions are identical
- **0.0** = the two distributions are completely disjoint

### Data Sources for Each Dimension

| Dimension | Real Data SQL | Generated Data SQL |
|-----------|---------------|-------------------|
| Synthesis Method | `material_samples.synthesis_method` frequency | Same |
| Processing Route | `material_samples.processing_route` frequency | Same |
| Dopant Element | `sample_dopants.dopant_element` frequency | Same |
| Crystal Phase (Major) | `crystal_id` frequency where `is_major_phase=1` in `sample_crystal_phases` | Same |

### Notes

- Category matching uses **exact string matching**; differences in case or punctuation (e.g., en-dash `–` vs hyphen `-`) will be treated as different categories
- Categories that exist in only one side will have a probability of 0 on the other side, significantly increasing JSD

## 3. Numerical Distribution Evaluation (Dimensions 5-8)

### Method: Quantile Vector Normalized RMSE Composite Score

For both real and generated data, compute 7 quantiles (P5, P10, P25, P50, P75, P90, P95) along with basic statistics.

#### 3.1 Percentile RMSE

```
range = max(|real_max - real_min|, 1e-10)

pct_rmse = sqrt( (1/7) × Σᵢ ((gen_pctᵢ - real_pctᵢ) / range)² )
```

This measures the shape difference between the two distributions at each quantile point, normalized by the real data range.

#### 3.2 Mean Difference

```
mean_diff = |gen_mean - real_mean| / range
```

#### 3.3 Std Ratio

```
std_ratio = min(gen_std, real_std) / max(gen_std, real_std)
```

Range is [0, 1], where 1.0 indicates identical dispersion.

#### 3.4 Composite Score Formula

```
similarity = 0.6 × max(0, 1 - min(pct_rmse × 3, 1))
           + 0.2 × max(0, 1 - min(mean_diff × 3, 1))
           + 0.2 × std_ratio
```

Sub-component weights: **quantile shape 60% + mean location 20% + dispersion 20%**.

The scaling factor of 3 means:
- `pct_rmse ≥ 0.33` or `mean_diff ≥ 0.33` → that sub-component scores 0
- `pct_rmse = 0` → that sub-component scores the full 0.6

### Data Sources for Each Dimension

| Dimension | Data Value | Source Table |
|-----------|------------|--------------|
| log₁₀(Conductivity) | `LOG10(conductivity)` (where > 0) | `material_samples` |
| Operating Temperature | `operating_temperature` | `material_samples` |
| Dopant Molar Fraction | `dopant_molar_fraction` | `sample_dopants` |
| Sintering Temperature | `sintering_temperature` | `sintering_steps` |

## 4. Joint Distribution Evaluation (Dimension 9)

### Method: Dopant Element-Conductivity Correlation

Evaluates whether the average log₁₀(conductivity) for each primary dopant element within the 700-900°C temperature window is consistent with real data.

#### Data Filtering

1. For each sample, select the dopant element with the highest molar fraction as the "primary dopant" (`ROW_NUMBER() OVER (PARTITION BY sample_id ORDER BY dopant_molar_fraction DESC)`, taking rn=1)
2. Temperature range restricted to 700-900°C (mid-temperature range, where data is most dense)
3. Conductivity > 0
4. At least 3 samples per element (`HAVING COUNT(*) >= 3`)

#### Scoring Formula

For dopant elements that appear in both datasets:

**Pearson correlation coefficient r**: measures consistency of conductivity ranking across elements

```
corr_score = max(0, (r + 1) / 2)    ∈ [0, 1]
```

- r = 1 → ranking is perfectly consistent, scores 1.0
- r = 0 → no correlation, scores 0.5
- r = -1 → ranking is perfectly reversed, scores 0.0

**Normalized RMSE**: measures absolute value deviation

```
range = max(real_max - real_min, 1.0)
rmse = sqrt( (1/n) × Σ ((realᵢ - genᵢ) / range)² )
rmse_score = max(0, 1 - rmse × 2)
```

**Final Score**:

```
similarity = 0.5 × corr_score + 0.5 × rmse_score
```

Correlation (ranking) and absolute value deviation each account for 50%.

## 5. Overall Scoring and Rating

```
overall = Σ(dimensionᵢ.score × dimensionᵢ.weight) / Σ(dimensionᵢ.weight)
```

| Score Range | Rating | Meaning |
|-------------|--------|---------|
| ≥ 0.90 | EXCELLENT | Generated data closely matches real experimental data |
| ≥ 0.75 | GOOD | Reasonable match with minor deviations |
| ≥ 0.60 | FAIR | Notable differences; distribution details should be reviewed |
| < 0.60 | POOR | Significant deviation; generator parameters need adjustment |

## 6. Output Files

Evaluation results are written to the `{outputPath}/fidelity_report/` directory:

| File | Contents |
|------|----------|
| `fidelity_summary.csv` | Per-dimension scores, weights, weighted scores, ratings, and overall score |
| `fidelity_categorical.csv` | Real and generated proportions and their differences for each category within each categorical dimension |
| `fidelity_numerical.csv` | Count/Mean/Std/Min/P5-P95/Max, Pct_RMSE, and Std_Ratio for each numerical dimension |
| `fidelity_correlation.csv` | Real and generated average log₁₀(conductivity) and their differences for each dopant element at 700-900°C |

## 7. Real Data Baseline

Real data is sourced from TSV files in the `real-data/` directory (1,351 experimental records exported from MySQL), comprising 4 tables:

- `material_samples.tsv` — Sample master table
- `sample_dopants.tsv` — Dopant information
- `sintering_steps.tsv` — Sintering steps
- `sample_crystal_phases.tsv` — Crystal phase information

These data serve as the "gold standard" for evaluation, with a defined confidence of 1.0.
