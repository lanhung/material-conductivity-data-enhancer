package com.lanhung.conductivity.generator;

import com.lanhung.conductivity.config.*;
import com.lanhung.conductivity.model.*;
import com.lanhung.conductivity.util.RandomUtils;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 核心数据生成引擎。生成一个完整的配方组
 * （掺杂剂 + 晶相 + 烧结 + 温度序列 + 电导率），
 * 并强制执行全部 14 条物理规则。
 *
 * 物理规则:
 *   1  - 阿伦尼乌斯温度依赖性（分段式）
 *   2  - 离子半径/化合价来自查找表
 *   3  - 晶相-掺杂剂耦合
 *   4  - 至少一个 +2/+3 价掺杂剂（氧空位）
 *   5  - 各元素的固溶度极限
 *   6  - 总掺杂分数 <= 0.30
 *   7  - 浓度非单调性（活化能与最优偏差）
 *   8  - 离子半径-电导率相关性（按元素类型的活化能）
 *   9  - 烧结-合成方法耦合
 *   10 - 晶界效应（低烧结温度惩罚）
 *   11 - ScSZ 相退化（Sc<10%, T<650C）
 *   12 - 共掺杂非加性（>=3 掺杂剂惩罚）
 *   13 - 过渡金属电子电导率（Fe/Mn 增益）
 *   14 - 薄膜: RF 溅射无烧结
 */
public class RecipeGroupGenerator implements Serializable {

    private final Random rng;

    public RecipeGroupGenerator(Random rng) {
        this.rng = rng;
    }

    public GeneratedGroup generate(long recipeGroupId) {
        String synthesisMethod = RandomUtils.sampleCategorical(rng, PhysicsConstants.SYNTHESIS_METHOD_DIST);
        String processingRoute = RandomUtils.sampleCategorical(rng, PhysicsConstants.PROCESSING_ROUTE_DIST);

        // 第1步: 掺杂剂
        List<DopantInfo> dopants = generateDopants();
        // 预计算共享的派生值（仅一次）
        DopantInfo primaryDopant = dopants.stream()
                .max(Comparator.comparingDouble(d -> d.molarFraction))
                .orElse(dopants.get(0));
        double totalFrac = dopants.stream().mapToDouble(d -> d.molarFraction).sum();

        // 第2步: 晶相 [规则 3]
        List<CrystalPhaseInfo> crystalPhases = generateCrystalPhases(dopants, primaryDopant, totalFrac);
        int majorPhaseId = crystalPhases.stream()
                .filter(p -> p.isMajorPhase)
                .mapToInt(p -> p.crystalId)
                .findFirst()
                .orElse(PhysicsConstants.CRYSTAL_CUBIC);

        // 第3步: 烧结 [规则 9, 14]
        List<SinteringStepInfo> sinteringSteps = generateSintering(synthesisMethod, processingRoute);
        double maxSinteringTemp = sinteringSteps.stream()
                .mapToDouble(s -> s.temperature).max().orElse(0.0);

        // 第4步: 温度序列
        int numTempPoints = RandomUtils.sampleCategorical(rng, PhysicsConstants.TEMP_POINTS_DIST);
        double[] temperatures = generateTemperatureSequence(numTempPoints, processingRoute);

        // 第5步: 电导率 [规则 1,7,8,10,11,12,13]
        double[] conductivities = generateConductivities(
                dopants, primaryDopant, totalFrac, majorPhaseId, maxSinteringTemp, temperatures);

        // 第6步: 来源文本
        String sourceText = SourceTextGenerator.generate(synthesisMethod, dopants, rng);

        return assembleRows(recipeGroupId, synthesisMethod, processingRoute, sourceText,
                dopants, crystalPhases, sinteringSteps, temperatures, conductivities);
    }

    // ==================== 第1步: 掺杂剂生成 ====================

    private List<DopantInfo> generateDopants() {
        for (int attempt = 0; attempt < 50; attempt++) {
            List<DopantInfo> ds = tryGenerateDopants();
            if (!ds.isEmpty() && ds.stream().anyMatch(d -> d.valence == 2 || d.valence == 3)) {
                return ds;
            }
        }

        // 规则 4 回退: 强制使用默认 +3 元素
        DopantProperty prop = PhysicsConstants.DOPANT_PROPS.get(PhysicsConstants.DEFAULT_FALLBACK_ELEMENT);
        double frac = RandomUtils.nextBeta(rng, prop.betaAlpha, prop.betaBeta) * prop.betaScale;
        double clipped = RandomUtils.clamp(frac, PhysicsConstants.MIN_MOLAR_FRACTION, prop.maxSolubility);
        return Collections.singletonList(new DopantInfo(prop.element, prop.radius, prop.valence,
                RandomUtils.roundTo(clipped, 4), prop));
    }

    private List<DopantInfo> tryGenerateDopants() {
        int numDopants = RandomUtils.sampleCategorical(rng, PhysicsConstants.DOPANT_COUNT_DIST);
        Object[] selectedRaw = RandomUtils.sampleWithoutReplacement(rng, PhysicsConstants.ELEM_WEIGHTS, numDopants);

        List<DopantInfo> dopants = new ArrayList<>(numDopants);
        double totalFraction = 0.0;

        for (int i = 0; i < selectedRaw.length; i++) {
            DopantProperty prop = (DopantProperty) selectedRaw[i];
            double fraction;
            if (i == 0) {
                fraction = RandomUtils.nextBeta(rng, prop.betaAlpha, prop.betaBeta) * prop.betaScale;
            } else {
                fraction = RandomUtils.nextBeta(rng, 2.0, 5.0) * 0.08;
            }

            // 规则 5: 固溶度极限; 规则 6: 总量 <= 0.30
            double clipped = RandomUtils.clamp(fraction, PhysicsConstants.MIN_MOLAR_FRACTION, prop.maxSolubility);
            double allowed = Math.min(clipped, PhysicsConstants.MAX_TOTAL_DOPANT_FRACTION - totalFraction);

            if (allowed > PhysicsConstants.MIN_MOLAR_FRACTION) {
                totalFraction += allowed;
                // 规则 2: 半径和化合价来自查找表
                dopants.add(new DopantInfo(prop.element, prop.radius, prop.valence,
                        RandomUtils.roundTo(allowed, 4), prop));
            }
        }
        return dopants;
    }

    // ==================== 第2步: 晶相生成 ====================

    private List<CrystalPhaseInfo> generateCrystalPhases(List<DopantInfo> dopants,
                                                          DopantInfo primaryDopant,
                                                          double totalFrac) {
        int numPhases = RandomUtils.sampleCategorical(rng, PhysicsConstants.CRYSTAL_PHASE_COUNT_DIST);
        int majorPhaseId = selectMajorPhase(totalFrac, primaryDopant);

        List<CrystalPhaseInfo> phases = new ArrayList<>();
        phases.add(new CrystalPhaseInfo(majorPhaseId, true));

        if (numPhases >= 2) {
            int secondaryId = selectSecondaryPhase(majorPhaseId);
            if (secondaryId != majorPhaseId) {
                phases.add(new CrystalPhaseInfo(secondaryId, false));
            }
        }
        if (numPhases >= 3) {
            Set<Integer> existingIds = phases.stream().map(p -> p.crystalId).collect(Collectors.toSet());
            List<Integer> candidates = new ArrayList<>();
            for (int c : PhysicsConstants.TERTIARY_PHASE_CANDIDATES) {
                if (!existingIds.contains(c)) candidates.add(c);
            }
            if (!candidates.isEmpty()) {
                phases.add(new CrystalPhaseInfo(candidates.get(rng.nextInt(candidates.size())), false));
            }
        }

        // 硬约束: 禁止的组合
        if (totalFrac < 0.05 && phases.size() == 1 && majorPhaseId == PhysicsConstants.CRYSTAL_CUBIC) {
            return Collections.singletonList(new CrystalPhaseInfo(PhysicsConstants.CRYSTAL_TETRAGONAL, true));
        }
        if (totalFrac > 0.12 && phases.size() == 1 && majorPhaseId == PhysicsConstants.CRYSTAL_MONOCLINIC) {
            return Collections.singletonList(new CrystalPhaseInfo(PhysicsConstants.CRYSTAL_CUBIC, true));
        }

        return phases;
    }

    private int selectMajorPhase(double totalFrac, DopantInfo primary) {
        if (primary.element.equals("Sc")) {
            if (primary.molarFraction >= 0.10)
                return RandomUtils.sampleCategorical(rng, PhysicsConstants.PHASE_DIST_SC_HIGH);
            else if (primary.molarFraction < 0.08)
                return RandomUtils.sampleCategorical(rng, PhysicsConstants.PHASE_DIST_SC_LOW);
        }

        if (totalFrac >= 0.10 && (primary.valence == 2 || primary.valence == 3))
            return RandomUtils.sampleCategorical(rng, PhysicsConstants.PHASE_DIST_HIGH_DOPING);
        else if (totalFrac >= 0.05)
            return RandomUtils.sampleCategorical(rng, PhysicsConstants.PHASE_DIST_MED_DOPING);
        else
            return RandomUtils.sampleCategorical(rng, PhysicsConstants.PHASE_DIST_LOW_DOPING);
    }

    private int selectSecondaryPhase(int majorId) {
        switch (majorId) {
            case PhysicsConstants.CRYSTAL_CUBIC: return PhysicsConstants.CRYSTAL_TETRAGONAL;
            case PhysicsConstants.CRYSTAL_TETRAGONAL:
                return rng.nextDouble() < 0.6 ? PhysicsConstants.CRYSTAL_CUBIC : PhysicsConstants.CRYSTAL_MONOCLINIC;
            case PhysicsConstants.CRYSTAL_MONOCLINIC: return PhysicsConstants.CRYSTAL_TETRAGONAL;
            case PhysicsConstants.CRYSTAL_RHOMBOHEDRAL: return PhysicsConstants.CRYSTAL_CUBIC;
            default: return PhysicsConstants.CRYSTAL_TETRAGONAL;
        }
    }

    // ==================== 第3步: 烧结生成 ====================

    private List<SinteringStepInfo> generateSintering(String synthesisMethod, String processingRoute) {
        // 规则 14: RF 溅射 = 无烧结（薄膜）
        if (processingRoute.equals(ProcessingRoute.RF_SPUTTERING)) return Collections.emptyList();

        int numSteps = RandomUtils.sampleCategorical(rng, PhysicsConstants.SINTERING_STEP_DIST);
        if (numSteps == 0) return Collections.emptyList();

        SinteringRange range = processingRoute.equals(ProcessingRoute.SPS)
                ? PhysicsConstants.SPS_SINTERING
                : PhysicsConstants.SINTERING_RANGES.getOrDefault(synthesisMethod, PhysicsConstants.DEFAULT_SINTERING);

        List<SinteringStepInfo> steps = new ArrayList<>(numSteps);
        for (int step = 1; step <= numSteps; step++) {
            steps.add(new SinteringStepInfo(step,
                    sampleCommonOrUniform(PhysicsConstants.COMMON_SINTERING_TEMPS, range.tempMin, range.tempMax),
                    sampleCommonOrUniform(PhysicsConstants.COMMON_SINTERING_DURATIONS, range.durMin, range.durMax)));
        }
        return steps;
    }

    /** 烧结温度/时长的共享逻辑: 70% 从常见值中选取，30% 均匀随机取整。 */
    private double sampleCommonOrUniform(double[] commonValues, double min, double max) {
        if (rng.nextDouble() < PhysicsConstants.COMMON_VALUE_SAMPLE_RATIO) {
            List<Double> candidates = new ArrayList<>();
            for (double v : commonValues) {
                if (v >= min && v <= max) candidates.add(v);
            }
            if (!candidates.isEmpty()) {
                return candidates.get(rng.nextInt(candidates.size()));
            }
            return min + rng.nextDouble() * (max - min);
        } else {
            return Math.round((min + rng.nextDouble() * (max - min)) / 10.0) * 10.0;
        }
    }

    // ==================== 第4步: 温度序列 ====================

    private double[] generateTemperatureSequence(int numPoints, String processingRoute) {
        double centerTemp = RandomUtils.sampleCategorical(rng, PhysicsConstants.OPERATING_TEMP_DIST);
        double adjustedCenter = processingRoute.equals(ProcessingRoute.RF_SPUTTERING)
                ? Math.min(centerTemp, 800.0) : centerTemp;

        double spacing = 50.0 + rng.nextDouble() * 50.0;
        int halfRange = (int) ((numPoints - 1) / 2.0 * spacing);
        double startTemp = Math.max(PhysicsConstants.MIN_OPERATING_TEMP, adjustedCenter - halfRange);

        List<Double> temps = new ArrayList<>(numPoints);
        for (int i = 0; i < numPoints; i++) {
            double t = startTemp + i * spacing + rng.nextGaussian() * 5.0;
            temps.add((double) Math.round(RandomUtils.clamp(t,
                    PhysicsConstants.MIN_OPERATING_TEMP, PhysicsConstants.MAX_OPERATING_TEMP)));
        }

        double[] sorted = temps.stream().distinct().sorted().mapToDouble(Double::doubleValue).toArray();
        if (sorted.length < 2) {
            double base = Math.max(PhysicsConstants.MIN_OPERATING_TEMP, adjustedCenter - 150);
            double[] fallback = new double[numPoints];
            for (int i = 0; i < numPoints; i++) {
                fallback[i] = Math.min(PhysicsConstants.MAX_OPERATING_TEMP, base + i * 75.0);
            }
            return fallback;
        }
        return sorted;
    }

    // ==================== 第5步: 电导率生成 ====================

    private double[] generateConductivities(
            List<DopantInfo> dopants,
            DopantInfo primaryDopant,
            double totalFrac,
            int majorPhaseId,
            double maxSinteringTemp,
            double[] temperatures) {

        DopantProperty primaryProp = primaryDopant.property;

        // --- 活化能（规则 7, 8）---
        double eaHighBase = primaryProp.eaHighMin + rng.nextDouble() * (primaryProp.eaHighMax - primaryProp.eaHighMin);
        double eaLowBase = primaryProp.eaLowMin + rng.nextDouble() * (primaryProp.eaLowMax - primaryProp.eaLowMin);
        double concDeviation = Math.abs(primaryDopant.molarFraction - primaryProp.optimalFraction);
        double eaPenalty = concDeviation * 2.0;
        double eaHigh = eaHighBase + eaPenalty;
        double eaLow = eaLowBase + eaPenalty;

        // --- 800C 参考电导率（规则 8, 10, 12, 13）---
        double sigmaRefLog10 = primaryProp.baseSigmaLog10;
        sigmaRefLog10 -= 15.0 * concDeviation * concDeviation;

        switch (majorPhaseId) {
            case PhysicsConstants.CRYSTAL_CUBIC: break; // 基线
            case PhysicsConstants.CRYSTAL_TETRAGONAL: sigmaRefLog10 -= 0.3; break;
            case PhysicsConstants.CRYSTAL_MONOCLINIC: sigmaRefLog10 -= 1.0; break;
            case PhysicsConstants.CRYSTAL_RHOMBOHEDRAL: sigmaRefLog10 -= 0.5; break;
            case PhysicsConstants.CRYSTAL_ORTHOGONAL: sigmaRefLog10 -= 0.8; break;
            default: sigmaRefLog10 -= 0.5; break;
        }

        // 规则 10: 晶界效应
        if (maxSinteringTemp > 0 && maxSinteringTemp < 1300.0) {
            sigmaRefLog10 -= 0.1 + (1300.0 - maxSinteringTemp) / 1000.0 * 0.3;
        }

        // 规则 12: 共掺杂非加性
        if (dopants.size() >= 3) {
            sigmaRefLog10 -= 0.05 + (dopants.size() - 2) * 0.05;
        }

        // 规则 13: 过渡金属电子电导率
        if (dopants.stream().anyMatch(d -> d.element.equals("Fe") || d.element.equals("Mn"))) {
            sigmaRefLog10 += 0.04 + rng.nextDouble() * 0.1;
        }

        sigmaRefLog10 += rng.nextGaussian() * 0.15;
        double sigmaRef = Math.pow(10.0, sigmaRefLog10);

        // --- 阿伦尼乌斯参数 ---
        double sigma0High = sigmaRef * PhysicsConstants.REFERENCE_TEMP_K
                * Math.exp(eaHigh / (PhysicsConstants.KB * PhysicsConstants.REFERENCE_TEMP_K));
        double sigma0Low = sigma0High
                * Math.exp((eaLow - eaHigh) / (PhysicsConstants.KB * PhysicsConstants.ARRHENIUS_SEGMENT_BOUNDARY_K));

        // --- 生成各温度点的电导率 ---
        double[] conductivities = new double[temperatures.length];
        for (int i = 0; i < temperatures.length; i++) {
            double tempC = temperatures[i];
            double tempK = tempC + 273.15;
            double ea = tempC >= PhysicsConstants.ARRHENIUS_SEGMENT_BOUNDARY_C ? eaHigh : eaLow;
            double sigma0 = tempC >= PhysicsConstants.ARRHENIUS_SEGMENT_BOUNDARY_C ? sigma0High : sigma0Low;

            double sigma = (sigma0 / tempK) * Math.exp(-ea / (PhysicsConstants.KB * tempK));

            // 规则 11: ScSZ 退化
            if (primaryDopant.element.equals("Sc") && primaryDopant.molarFraction < 0.10 && tempC < 650.0) {
                sigma *= 0.2 + rng.nextDouble() * 0.3;
            }

            double logSigma = Math.log10(Math.max(sigma, 1e-15))
                    + rng.nextGaussian() * PhysicsConstants.CONDUCTIVITY_LOG_NOISE_STD;
            conductivities[i] = Math.pow(10.0, logSigma);
        }

        // 先裁剪到有效范围
        for (int i = 0; i < conductivities.length; i++) {
            conductivities[i] = RandomUtils.clamp(conductivities[i],
                    PhysicsConstants.MIN_CONDUCTIVITY, PhysicsConstants.MAX_CONDUCTIVITY);
        }

        // 规则 1: 裁剪后强制严格单调性（确保裁剪不会破坏单调）
        for (int i = 1; i < conductivities.length; i++) {
            if (conductivities[i] <= conductivities[i - 1]) {
                double bump = conductivities[i - 1] * (1.0 + 0.01 + rng.nextDouble() * 0.05);
                conductivities[i] = Math.min(bump, PhysicsConstants.MAX_CONDUCTIVITY);
            }
        }

        return conductivities;
    }

    // ==================== 组装 ====================

    private GeneratedGroup assembleRows(
            long recipeGroupId,
            String synthesisMethod,
            String processingRoute,
            String sourceText,
            List<DopantInfo> dopants,
            List<CrystalPhaseInfo> crystalPhases,
            List<SinteringStepInfo> sinteringSteps,
            double[] temperatures,
            double[] conductivities) {

        int numSamples = temperatures.length;
        int numDopants = dopants.size();
        int numSteps = sinteringSteps.size();
        int numPhases = crystalPhases.size();

        int synthesisMethodId = PhysicsConstants.SYNTHESIS_METHOD_IDS.getOrDefault(synthesisMethod, 1);
        int processingRouteId = PhysicsConstants.PROCESSING_ROUTE_IDS.getOrDefault(processingRoute, 1);

        MaterialSampleRow[] sampleArr = new MaterialSampleRow[numSamples];
        SampleDopantRow[] dopantArr = new SampleDopantRow[numSamples * numDopants];
        SinteringStepRow[] sinterArr = new SinteringStepRow[numSamples * numSteps];
        CrystalPhaseRow[] phaseArr = new CrystalPhaseRow[numSamples * numPhases];

        for (int i = 0; i < numSamples; i++) {
            long sampleId = PhysicsConstants.BASE_SAMPLE_ID
                    + recipeGroupId * PhysicsConstants.MAX_TEMP_POINTS + i;

            sampleArr[i] = new MaterialSampleRow(sampleId, recipeGroupId, "RULE_BASED_SYNTHETIC",
                    sourceText, synthesisMethodId, processingRouteId, temperatures[i],
                    conductivities[i]);

            for (int j = 0; j < numDopants; j++) {
                DopantInfo d = dopants.get(j);
                dopantArr[i * numDopants + j] =
                        new SampleDopantRow(sampleId, recipeGroupId, d.element, d.ionicRadius,
                                d.valence, d.molarFraction);
            }
            for (int j = 0; j < numSteps; j++) {
                SinteringStepInfo s = sinteringSteps.get(j);
                sinterArr[i * numSteps + j] =
                        new SinteringStepRow(sampleId, recipeGroupId, s.stepOrder, s.temperature,
                                s.duration);
            }
            for (int j = 0; j < numPhases; j++) {
                CrystalPhaseInfo p = crystalPhases.get(j);
                phaseArr[i * numPhases + j] =
                        new CrystalPhaseRow(sampleId, recipeGroupId, p.crystalId, p.isMajorPhase);
            }
        }

        return new GeneratedGroup(sampleArr, dopantArr, sinterArr, phaseArr);
    }
}
