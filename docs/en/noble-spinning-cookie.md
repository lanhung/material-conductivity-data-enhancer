# ZrO₂ Conductivity Data Generation Plan (100 Million Records, Physically Consistent)

## Context

The current project has 1,351 real zirconia-based material conductivity data points, distributed across 4 related tables. The goal is to generate 100 million physically consistent synthetic data records for machine learning training. The table structures are finalized (material_samples, sample_dopants, sintering_steps, sample_crystal_phases). This plan focuses on **how to ensure physical plausibility of generated data**.

---

## 1. Generation Architecture: "Recipe Group" as the Basic Unit

**Core Design**: The generation unit is not a single sample, but rather **a Recipe Group**.

One Recipe Group = one material composition (doping + processing + crystal phase), corresponding to multiple test temperature points.

- 100 million records ~ **~20-30 million recipes x average 3-5 temperature points**
- Samples within the same recipe group share the same doping information, sintering conditions, and crystal phases; only operating_temperature and conductivity differ
- Conductivity strictly follows the Arrhenius relationship, monotonically increasing within a group

---

## 2. Generation Sequence and Physical Constraints for Each Step

### Step 1: Generate Doping Information (sample_dopants)

**1.1 Number of Dopant Elements**
- Distribution: 1(46%), 2(46%), 3(5%), 4(2%), 5(1%)

**1.2 Element Selection**
- Weighted sampling from 20 elements based on real frequency: Y(38%), Sc(33%), Yb(9%), Ce(5%), Dy(3%), Bi(3%), ...
- No duplicate elements within the same recipe
- **Hard constraint (Rule 4)**: Must contain at least one +2 or +3 valence element. If a pure +4/+5 valence combination is randomly selected, resample

**1.3 Ionic Radius and Valence**
- **Hard constraint (Rule 2)**: Fixed mapping from lookup table DOPANT_PROPERTIES; cannot be randomized

**1.4 Molar Fraction**
- Primary dopant: sampled from Beta distribution, centered near the optimal concentration for each element
  - Y: Beta(alpha=4, beta=3) x 0.20 -> center ~0.08
  - Sc: Beta(alpha=5, beta=3) x 0.15 -> center ~0.09
  - Ca: Beta(alpha=3, beta=2) x 0.25 -> center ~0.12
- Secondary dopant (co-dopant): sampled from Beta(2,5) x 0.08 -> center ~0.02
- **Hard constraint (Rule 6)**: SUM(molar_fraction) <= 0.30; clip if exceeded
- **Hard constraint (Rule 5)**: Each element must not exceed its solid solubility limit
  - Sc <= 0.12, Ce <= 0.18, Y <= 0.25 (rarely exceeded in real data), Ca <= 0.20
- **Hard constraint**: Each molar_fraction > 0

### Step 2: Generate Crystal Phase Information (sample_crystal_phases)

**Depends on doping information from Step 1**

**2.1 Number of Crystal Phases**
- Distribution: 1(84%), 2(16%), 3(<1%)

**2.2 Primary Phase Selection -- Conditional Probability Depends on Dopant Concentration (Rule 3)**

Calculate total_dopant_fraction and weighted_avg_valence:

| Condition | Primary Phase Probability Distribution |
|-----------|---------------------------------------|
| Total dopant >= 0.10 and primary dopant is +3 valence | c:80%, t:15%, m:5% |
| Total dopant 0.05-0.10 | c:40%, t:45%, m:10%, o:5% |
| Total dopant < 0.05 | c:5%, t:40%, m:45%, o:10% |
| Primary dopant is Sc and Sc >= 0.10 | c:85%, t:10%, r:5% |
| Primary dopant is Sc and Sc < 0.08 | c:10%, t:50%, r:25%, m:15% |

**Hard constraints**:
- Low dopant (total < 0.05) + pure cubic -> forbidden
- High dopant (total > 0.12) + pure monoclinic -> forbidden
- Each sample must have exactly 1 is_major_phase=1

**2.3 Secondary Phase (if any)**
- Single-phase samples: no secondary phase
- Two-phase samples: secondary phase selected from phases adjacent to the primary phase
  - Primary phase c -> secondary phase t
  - Primary phase t -> secondary phase c(60%) or m(40%)
  - Primary phase m -> secondary phase t

### Step 3: Generate Sintering Information (sintering_steps)

**Depends on synthesis_method and processing_route from the main table**

**3.1 Number of Sintering Steps**
- Distribution: 1(97%), 2(2%), 3(1%); 3% have no sintering data

**3.2 Sintering Temperature and Duration -- Depends on Synthesis Method (Rule 9)**

| synthesis_method + processing_route | Temperature Range (deg C) | Duration Range (min) |
|-------------------------------------|--------------------------|---------------------|
| Solid-state synthesis + dry pressing | 1400-1650 | 120-600 |
| Commercial powder + dry pressing | 1300-1550 | 60-300 |
| Sol-gel + dry pressing | 1200-1500 | 60-360 |
| Co-precipitation + dry pressing | 1200-1500 | 60-360 |
| * + SPS | 1000-1300 | 3-30 |
| * + RF sputtering | No sintering | N/A |

**Temperature sampling**: 70% sampled from real distribution, 30% uniformly sampled within the corresponding range
**Duration sampling**: Similar mixed distribution

### Step 4: Generate Main Table Information (material_samples) -- Except Conductivity

**4.1 Fixed Fields**
- sample_id: incrementing from 10,000,001
- reference: 'SYNTHETIC'

**4.2 Synthesis Method and Processing Route**
- synthesis_method: sampled according to real distribution (solid-state synthesis 45.6%, commercial 20%, sol-gel 12%, co-precipitation 10%, hydrothermal 5%, other 7.4%)
- processing_route: sampled according to real distribution (dry pressing 80.4%, SPS 8%, RF sputtering 3%, other 8.6%)

**4.3 Operating Temperature Series (multiple temperature points within the same recipe group)**
- Each recipe group generates 3-5 temperature points (distribution: 3 points: 40%, 4 points: 30%, 5 points: 20%, 6-8 points: 10%)
- Temperature range: [300, 1400] deg C
- Sampling strategy:
  - First determine a center temperature for each group (sampled from real distribution)
  - Then generate other temperature points at equal intervals or randomly within center temperature +/- 200 deg C
  - Spacing of 50-100 deg C

### Step 5: Generate Conductivity (conductivity) -- Core Physical Constraints

**This is the most critical step and must satisfy multiple physical rules.**

**Approach: Piecewise Arrhenius + LightGBM Hybrid**

**5.1 Determine Arrhenius Parameters for Each Recipe Group**

For each recipe group, two sets of parameters must be determined (piecewise Arrhenius, Rule 1 refinement):
- Low-temperature regime (<600 deg C): Ea_low, sigma_0_low
- High-temperature regime (>=600 deg C): Ea_high, sigma_0_high

**Basis for Determining Ea (Rules 8, 13):**

| Primary Dopant Element | Ea_high (eV) | Ea_low (eV) |
|-----------------------|-------------|-------------|
| Sc | 0.78-0.85 | 1.05-1.15 |
| Y | 0.90-1.05 | 1.10-1.25 |
| Yb | 0.85-0.95 | 1.05-1.20 |
| Gd | 0.95-1.10 | 1.10-1.30 |
| Dy | 0.95-1.10 | 1.10-1.30 |
| Ca | 1.00-1.20 | 1.15-1.35 |
| Other | 0.95-1.15 | 1.10-1.30 |

- Ea is randomly sampled within the corresponding range (normal distribution, centered at the range midpoint)
- The further the dopant concentration deviates from the optimal value -> the higher Ea becomes (Rule 7)

**Determining sigma_0**:
- Use a LightGBM model to predict log10(conductivity) at a reference temperature (e.g., 800 deg C)
- Then back-calculate sigma_0 using the Arrhenius equation
- This leverages the composition-conductivity relationships learned by the ML model while ensuring physical consistency across the temperature series

**5.2 Generate Conductivity Series**

For each temperature T_i in the recipe group:
1. Select the corresponding Ea and sigma_0 based on T_i (high-temperature or low-temperature regime)
2. Calculate sigma(T_i) = sigma_0/T_i x exp(-Ea/k_B T_i)
3. Add small noise: log10(sigma) += N(0, 0.05) (Gaussian noise with sigma=0.05 in log space)
4. Clip to [1e-8, 1.0] S/cm

**5.3 Physical Trend Corrections (Soft Constraint Checks)**

After generation, check each recipe group:
- **Rule 1**: Conductivity must be strictly monotonically increasing across the temperature series -> if violated due to noise, reorder the noise
- **Rule 8 / Conductivity ranking**: Sc-based systems should have overall higher conductivity than Y-based > Gd/Dy-based -> no need to check every record, but verify statistical trends via sampling
- **Rule 10 / Grain boundary effect**: For recipes with sintering temperature < 1300 deg C, multiply conductivity by a decay factor of 0.5-0.8
- **Rule 11 / ScSZ degradation**: If Sc is the primary dopant and Sc < 10% and T < 650 deg C -> reduce conductivity by an additional 50-80%
- **Rule 12 / Co-doping non-additivity**: If number of dopant elements >= 3 -> multiply conductivity by a decay factor of 0.7-0.9
- **Rule 13 / Transition metal electronic conduction**: If Fe/Mn present -> multiply conductivity by an enhancement factor of 1.1-1.3

### Step 6: Generate material_source_and_purity (Template-Based)

Follow the template approach from plan2, selecting the pattern based on synthesis_method:
- Commercial powder pattern (70%)
- Self-prepared powder pattern (15%)
- Brief description pattern (15%)

---

## 3. Mapping of 14 Physical Rules to Generation Steps

| # | Physical Rule | Implementation Step | Method |
|---|--------------|-------------------|--------|
| 1 | Arrhenius temperature dependence | Step 5 | Piecewise Arrhenius formula for temperature series generation |
| 2 | Ionic radius/valence fixed | Step 1 | DOPANT_PROPERTIES lookup table |
| 3 | Crystal phase-doping coupling | Step 2 | Conditional probability table + hard constraint filtering |
| 4 | Valence-oxygen vacancy | Step 1 | Post-sampling check; resample if pure +4/+5 |
| 5 | Solid solubility limit | Step 1 | Per-element upper limit clipping |
| 6 | Total doping upper limit | Step 1 | SUM <= 0.30 clipping |
| 7 | Concentration non-monotonicity | Step 5 | Ea increases as concentration deviates from optimum |
| 8 | Ionic radius-conductivity | Step 5 | Ea assigned by element type |
| 9 | Sintering-synthesis method coupling | Step 3 | Conditional distribution table |
| 10 | Grain boundary effect | Step 5 | Low sintering temperature decay factor |
| 11 | ScSZ phase degradation | Step 5 | Additional decay for Sc < 10% at low temperature |
| 12 | Co-doping non-additivity | Step 5 | Decay factor for >= 3 dopant types |
| 13 | Transition metal electronic conduction | Step 5 | Fe/Mn enhancement factor |
| 14 | Thin film specifics | Steps 3/4 | No sintering for RF sputtering; temperature range adjustment |

---

## 4. Data Volume Planning

| Table | Avg. Rows per Recipe Group | Avg. Rows per Sample | Estimated Total Rows |
|-------|--------------------------|---------------------|---------------------|
| material_samples | 3-5 (temperature points) | 1 | 100 million |
| sample_dopants | 1.68 x (3-5) | ~1.68 | ~168 million |
| sintering_steps | 1 x (3-5) | ~1 | ~100 million |
| sample_crystal_phases | 1.16 x (3-5) | ~1.16 | ~116 million |

Note: All samples within the same recipe group share the same dopant/sintering/crystal_phase data.

Number of recipe groups ~ 100 million / average temperature points (~4) ~ **25 million recipe groups**

---

## 5. Validation Checklist

### Hard Constraint Validation (Must Pass 100%)
1. All conductivity > 0
2. All molar_fraction > 0 and <= corresponding element solid solubility limit
3. SUM(molar_fraction) <= 0.30 for each sample
4. Each sample contains at least one +2 or +3 valence dopant
5. Each sample has exactly 1 is_major_phase = 1
6. All child table sample_id values exist in material_samples
7. Within the same recipe group, temperature up -> conductivity strictly up
8. Low dopant (<0.05) must not have pure cubic; high dopant (>0.12) must not have pure monoclinic

### Statistical Distribution Validation (Spot Checks)
1. Dopant element frequency distribution similar to real data
2. operating_temperature distribution similar to real data
3. conductivity log-distribution similar to real data
4. corr(operating_temperature, ln(conductivity)) ~ 0.7-0.8
5. Sc-based system average conductivity > Y-based > Gd-based (at same temperature and concentration)

---

## 6. Key File Paths

- Table structure definition: `sql/create_db_tb_v2.sql`
- Existing data: `data/*.tsv`
- Previous plan references: `docs/plan1.md`, `docs/plan2.md`, `docs/plan3.md`
- Dopant lookup table: DOPANT_PROPERTIES in `docs/plan2.md`
