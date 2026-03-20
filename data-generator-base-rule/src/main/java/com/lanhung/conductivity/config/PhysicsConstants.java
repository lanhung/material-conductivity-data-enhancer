package com.lanhung.conductivity.config;

import com.lanhung.conductivity.util.WeightedItem;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * ZrO2 基离子导体数据生成所需的所有物理常数、掺杂剂查找表和概率分布。
 *
 * 在热路径采样（每个配方组）中使用的数组已预先配对/预计算，
 * 以避免在生成循环内部进行内存分配。
 */
public final class PhysicsConstants implements Serializable {

    private PhysicsConstants() {}

    // ==================== 基本常数 ====================
    public static final double KB = 8.617333e-5; // 玻尔兹曼常数 (eV/K)
    public static final double ZR_IONIC_RADIUS = 84.0; // Zr4+ 离子半径 (pm)，8 配位

    // ==================== 生成参数 ====================
    public static final long BASE_SAMPLE_ID = 10000001L;
    public static final int MAX_TEMP_POINTS = 8;
    public static final double REFERENCE_TEMP_C = 800.0;
    public static final double REFERENCE_TEMP_K = 1073.15;

    public static final double MIN_OPERATING_TEMP = 300.0;
    public static final double MAX_OPERATING_TEMP = 1400.0;

    public static final double MIN_CONDUCTIVITY = 1e-8;
    public static final double MAX_CONDUCTIVITY = 1.0;

    public static final double MAX_TOTAL_DOPANT_FRACTION = 0.30;
    public static final double MIN_MOLAR_FRACTION = 0.001;

    public static final double ARRHENIUS_SEGMENT_BOUNDARY_C = 600.0;
    public static final double ARRHENIUS_SEGMENT_BOUNDARY_K = 873.15;

    public static final double CONDUCTIVITY_LOG_NOISE_STD = 0.05;

    // 烧结采样中"从常见值中选取"与"均匀随机"的比例
    public static final double COMMON_VALUE_SAMPLE_RATIO = 0.7;

    // 当重试循环未能找到 +2/+3 掺杂剂时的回退元素
    public static final String DEFAULT_FALLBACK_ELEMENT = "Y";

    // 用于确定性分区随机数生成器的种子乘数 (Int.MaxValue，梅森素数)
    public static final long PARTITION_SEED_MULTIPLIER = 2147483647L;
    public static final long PARTITION_SEED_OFFSET = 12345L;

    // ==================== 字典表 ID 映射（与 v2 MySQL 字典表对应） ====================

    public static final Map<String, Integer> SYNTHESIS_METHOD_IDS = new HashMap<>();
    static {
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.UNKNOWN, 1);
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.COMMERCIALIZATION, 2);
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.COPRECIPITATION_SHORT, 3);
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.COPRECIPITATION, 4);
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.DIRECTIONAL_MELT, 5);
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.GLYCINE, 6);
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.HYDROTHERMAL, 7);
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.SOL_GEL, 8);
        SYNTHESIS_METHOD_IDS.put(SynthesisMethod.SOLID_STATE, 9);
    }

    public static final Map<String, Integer> PROCESSING_ROUTE_IDS = new HashMap<>();
    static {
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.UNKNOWN, 1);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.PRINTING_3D, 2);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.CVD, 3);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.CUTTING_POLISHING, 4);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.DRY_PRESSING, 5);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.ISOSTATIC_PRESSING, 6);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.MAGNETRON_SPUTTERING, 7);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.MOCVD, 8);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.PLASMA_SPRAY, 9);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.PLD, 10);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.RF_SPUTTERING, 11);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.SPS, 12);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.SPIN_COATING, 13);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.SPRAY_PYROLYSIS, 14);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.TAPE_CASTING, 15);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.ULTRASONIC_ATOMIZATION, 16);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.ULTRASONIC_SPRAY_PYROLYSIS, 17);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.VACUUM_FILTRATION, 18);
        PROCESSING_ROUTE_IDS.put(ProcessingRoute.VAPOR_DEPOSITION, 19);
    }

    // ==================== 掺杂剂属性查找表 ====================

    public static final Map<String, DopantProperty> DOPANT_PROPS = new HashMap<>();
    static {
        DOPANT_PROPS.put("Y",  new DopantProperty("Y",  101.9, 3, 0.380, 0.08, 0.25, 3.0, 4.0, 0.20, 0.90, 1.05, 1.10, 1.25, -1.70));
        DOPANT_PROPS.put("Sc", new DopantProperty("Sc", 87.0,  3, 0.330, 0.09, 0.12, 4.0, 3.0, 0.15, 0.78, 0.85, 1.05, 1.15, -1.00));
        DOPANT_PROPS.put("Yb", new DopantProperty("Yb", 98.5,  3, 0.090, 0.08, 0.20, 4.0, 3.0, 0.18, 0.85, 0.95, 1.05, 1.20, -1.50));
        DOPANT_PROPS.put("Ce", new DopantProperty("Ce", 105.3, 4, 0.050, 0.10, 0.18, 4.0, 3.0, 0.20, 0.95, 1.10, 1.10, 1.25, -1.80));
        DOPANT_PROPS.put("Dy", new DopantProperty("Dy", 102.7, 3, 0.030, 0.08, 0.20, 4.0, 3.0, 0.18, 0.95, 1.10, 1.10, 1.30, -2.00));
        DOPANT_PROPS.put("Bi", new DopantProperty("Bi", 96.0,  3, 0.030, 0.05, 0.15, 3.0, 4.0, 0.12, 1.00, 1.15, 1.15, 1.30, -2.20));
        DOPANT_PROPS.put("Gd", new DopantProperty("Gd", 97.0,  3, 0.020, 0.08, 0.20, 4.0, 3.0, 0.18, 0.95, 1.10, 1.10, 1.30, -2.00));
        DOPANT_PROPS.put("Er", new DopantProperty("Er", 100.4, 3, 0.015, 0.08, 0.20, 4.0, 3.0, 0.18, 0.90, 1.05, 1.10, 1.25, -1.80));
        DOPANT_PROPS.put("Lu", new DopantProperty("Lu", 97.7,  3, 0.010, 0.08, 0.20, 4.0, 3.0, 0.18, 0.82, 0.90, 1.05, 1.18, -1.20));
        DOPANT_PROPS.put("Pr", new DopantProperty("Pr", 112.6, 3, 0.010, 0.05, 0.15, 3.0, 4.0, 0.12, 1.00, 1.15, 1.15, 1.30, -2.30));
        DOPANT_PROPS.put("Ca", new DopantProperty("Ca", 112.0, 2, 0.010, 0.12, 0.20, 3.0, 2.0, 0.25, 1.00, 1.20, 1.15, 1.35, -2.20));
        DOPANT_PROPS.put("Fe", new DopantProperty("Fe", 64.5,  3, 0.005, 0.02, 0.05, 3.0, 5.0, 0.06, 1.00, 1.15, 1.15, 1.30, -2.00));
        DOPANT_PROPS.put("Mn", new DopantProperty("Mn", 83.0,  2, 0.005, 0.01, 0.05, 3.0, 5.0, 0.04, 1.00, 1.15, 1.10, 1.30, -2.00));
        DOPANT_PROPS.put("Zn", new DopantProperty("Zn", 74.0,  2, 0.005, 0.02, 0.05, 3.0, 5.0, 0.06, 1.05, 1.20, 1.15, 1.35, -2.50));
        DOPANT_PROPS.put("Al", new DopantProperty("Al", 53.5,  3, 0.005, 0.01, 0.03, 3.0, 6.0, 0.04, 1.05, 1.20, 1.20, 1.35, -2.50));
        DOPANT_PROPS.put("In", new DopantProperty("In", 80.0,  3, 0.003, 0.05, 0.10, 3.0, 4.0, 0.12, 0.95, 1.10, 1.10, 1.30, -2.00));
        DOPANT_PROPS.put("Eu", new DopantProperty("Eu", 106.6, 3, 0.003, 0.05, 0.15, 3.0, 4.0, 0.12, 0.95, 1.10, 1.10, 1.30, -2.10));
        DOPANT_PROPS.put("Si", new DopantProperty("Si", 40.0,  4, 0.002, 0.01, 0.02, 3.0, 6.0, 0.03, 1.10, 1.25, 1.20, 1.40, -3.00));
        DOPANT_PROPS.put("Nb", new DopantProperty("Nb", 64.0,  5, 0.002, 0.02, 0.05, 3.0, 5.0, 0.06, 1.10, 1.25, 1.20, 1.40, -2.80));
        DOPANT_PROPS.put("Ti", new DopantProperty("Ti", 60.5,  4, 0.002, 0.02, 0.05, 3.0, 5.0, 0.06, 1.05, 1.20, 1.15, 1.35, -2.50));
    }

    // 按频率预排序，预配对权重 - 避免在热路径中分配内存
    public static final DopantProperty[] ELEMENTS_SORTED;
    @SuppressWarnings("unchecked")
    public static final WeightedItem<DopantProperty>[] ELEM_WEIGHTS;
    static {
        ELEMENTS_SORTED = DOPANT_PROPS.values().stream()
                .sorted((a, b) -> Double.compare(b.frequency, a.frequency))
                .toArray(DopantProperty[]::new);
        ELEM_WEIGHTS = new WeightedItem[ELEMENTS_SORTED.length];
        for (int i = 0; i < ELEMENTS_SORTED.length; i++) {
            ELEM_WEIGHTS[i] = new WeightedItem<>(ELEMENTS_SORTED[i], ELEMENTS_SORTED[i].frequency);
        }
    }

    // ==================== 预计算概率分布 ====================

    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] DOPANT_COUNT_DIST = new WeightedItem[]{
            new WeightedItem<>(1, 0.46), new WeightedItem<>(2, 0.46),
            new WeightedItem<>(3, 0.05), new WeightedItem<>(4, 0.02), new WeightedItem<>(5, 0.01)
    };

    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] TEMP_POINTS_DIST = new WeightedItem[]{
            new WeightedItem<>(3, 0.40), new WeightedItem<>(4, 0.30), new WeightedItem<>(5, 0.20),
            new WeightedItem<>(6, 0.05), new WeightedItem<>(7, 0.03), new WeightedItem<>(8, 0.02)
    };

    @SuppressWarnings("unchecked")
    public static final WeightedItem<String>[] SYNTHESIS_METHOD_DIST = new WeightedItem[]{
            new WeightedItem<>(SynthesisMethod.SOLID_STATE, 0.456),
            new WeightedItem<>(SynthesisMethod.COMMERCIALIZATION, 0.200),
            new WeightedItem<>(SynthesisMethod.SOL_GEL, 0.120),
            new WeightedItem<>(SynthesisMethod.COPRECIPITATION, 0.100),
            new WeightedItem<>(SynthesisMethod.HYDROTHERMAL, 0.050),
            new WeightedItem<>(SynthesisMethod.GLYCINE, 0.030),
            new WeightedItem<>(SynthesisMethod.COPRECIPITATION_SHORT, 0.020),
            new WeightedItem<>(SynthesisMethod.DIRECTIONAL_MELT, 0.014),
            new WeightedItem<>(SynthesisMethod.UNKNOWN, 0.010)
    };

    @SuppressWarnings("unchecked")
    public static final WeightedItem<String>[] PROCESSING_ROUTE_DIST = new WeightedItem[]{
            new WeightedItem<>(ProcessingRoute.DRY_PRESSING, 0.804),
            new WeightedItem<>(ProcessingRoute.SPS, 0.080),
            new WeightedItem<>(ProcessingRoute.RF_SPUTTERING, 0.030),
            new WeightedItem<>(ProcessingRoute.TAPE_CASTING, 0.030),
            new WeightedItem<>(ProcessingRoute.ISOSTATIC_PRESSING, 0.030),
            new WeightedItem<>(ProcessingRoute.SPRAY_PYROLYSIS, 0.026)
    };

    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] CRYSTAL_PHASE_COUNT_DIST = new WeightedItem[]{
            new WeightedItem<>(1, 0.84), new WeightedItem<>(2, 0.155), new WeightedItem<>(3, 0.005)
    };

    @SuppressWarnings("unchecked")
    public static final WeightedItem<Double>[] OPERATING_TEMP_DIST = new WeightedItem[]{
            new WeightedItem<>(300.0, 0.02), new WeightedItem<>(350.0, 0.02),
            new WeightedItem<>(400.0, 0.03), new WeightedItem<>(450.0, 0.04),
            new WeightedItem<>(500.0, 0.06), new WeightedItem<>(550.0, 0.06),
            new WeightedItem<>(600.0, 0.08), new WeightedItem<>(650.0, 0.08),
            new WeightedItem<>(700.0, 0.12), new WeightedItem<>(750.0, 0.10),
            new WeightedItem<>(800.0, 0.12), new WeightedItem<>(850.0, 0.10),
            new WeightedItem<>(900.0, 0.08), new WeightedItem<>(950.0, 0.05),
            new WeightedItem<>(1000.0, 0.04)
    };

    // ==================== 晶相常量 ====================

    public static final int CRYSTAL_CUBIC = 1;
    public static final int CRYSTAL_TETRAGONAL = 2;
    public static final int CRYSTAL_MONOCLINIC = 3;
    public static final int CRYSTAL_ORTHOGONAL = 4;
    public static final int CRYSTAL_RHOMBOHEDRAL = 5;
    public static final int CRYSTAL_BETA = 6;

    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] PHASE_DIST_SC_HIGH = new WeightedItem[]{
            new WeightedItem<>(CRYSTAL_CUBIC, 0.85),
            new WeightedItem<>(CRYSTAL_TETRAGONAL, 0.10),
            new WeightedItem<>(CRYSTAL_RHOMBOHEDRAL, 0.05)
    };
    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] PHASE_DIST_SC_LOW = new WeightedItem[]{
            new WeightedItem<>(CRYSTAL_CUBIC, 0.10),
            new WeightedItem<>(CRYSTAL_TETRAGONAL, 0.50),
            new WeightedItem<>(CRYSTAL_RHOMBOHEDRAL, 0.25),
            new WeightedItem<>(CRYSTAL_MONOCLINIC, 0.15)
    };
    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] PHASE_DIST_HIGH_DOPING = new WeightedItem[]{
            new WeightedItem<>(CRYSTAL_CUBIC, 0.80),
            new WeightedItem<>(CRYSTAL_TETRAGONAL, 0.15),
            new WeightedItem<>(CRYSTAL_MONOCLINIC, 0.05)
    };
    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] PHASE_DIST_MED_DOPING = new WeightedItem[]{
            new WeightedItem<>(CRYSTAL_CUBIC, 0.40),
            new WeightedItem<>(CRYSTAL_TETRAGONAL, 0.45),
            new WeightedItem<>(CRYSTAL_MONOCLINIC, 0.10),
            new WeightedItem<>(CRYSTAL_ORTHOGONAL, 0.05)
    };
    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] PHASE_DIST_LOW_DOPING = new WeightedItem[]{
            new WeightedItem<>(CRYSTAL_CUBIC, 0.05),
            new WeightedItem<>(CRYSTAL_TETRAGONAL, 0.40),
            new WeightedItem<>(CRYSTAL_MONOCLINIC, 0.45),
            new WeightedItem<>(CRYSTAL_ORTHOGONAL, 0.10)
    };

    // 第三相选择的候选项
    public static final int[] TERTIARY_PHASE_CANDIDATES = {
            CRYSTAL_CUBIC, CRYSTAL_TETRAGONAL, CRYSTAL_MONOCLINIC, CRYSTAL_ORTHOGONAL
    };

    // ==================== 烧结范围 ====================

    public static final SinteringRange DEFAULT_SINTERING = new SinteringRange(1300.0, 1550.0, 60.0, 360.0);
    public static final SinteringRange SPS_SINTERING = new SinteringRange(1000.0, 1300.0, 3.0, 30.0);

    public static final Map<String, SinteringRange> SINTERING_RANGES = new HashMap<>();
    static {
        SINTERING_RANGES.put(SynthesisMethod.SOLID_STATE, new SinteringRange(1400.0, 1650.0, 120.0, 600.0));
        SINTERING_RANGES.put(SynthesisMethod.COMMERCIALIZATION, new SinteringRange(1300.0, 1550.0, 60.0, 300.0));
        SINTERING_RANGES.put(SynthesisMethod.SOL_GEL, new SinteringRange(1200.0, 1500.0, 60.0, 360.0));
        SINTERING_RANGES.put(SynthesisMethod.COPRECIPITATION, new SinteringRange(1200.0, 1500.0, 60.0, 360.0));
        SINTERING_RANGES.put(SynthesisMethod.COPRECIPITATION_SHORT, new SinteringRange(1200.0, 1500.0, 60.0, 360.0));
        SINTERING_RANGES.put(SynthesisMethod.HYDROTHERMAL, new SinteringRange(1200.0, 1500.0, 60.0, 360.0));
        SINTERING_RANGES.put(SynthesisMethod.GLYCINE, new SinteringRange(1200.0, 1500.0, 60.0, 360.0));
        SINTERING_RANGES.put(SynthesisMethod.DIRECTIONAL_MELT, new SinteringRange(1300.0, 1550.0, 60.0, 360.0));
    }

    @SuppressWarnings("unchecked")
    public static final WeightedItem<Integer>[] SINTERING_STEP_DIST = new WeightedItem[]{
            new WeightedItem<>(1, 0.97), new WeightedItem<>(2, 0.02), new WeightedItem<>(3, 0.01)
    };

    public static final double[] COMMON_SINTERING_TEMPS = {
            1100.0, 1200.0, 1250.0, 1300.0, 1350.0, 1400.0, 1450.0,
            1500.0, 1550.0, 1600.0, 1650.0
    };

    public static final double[] COMMON_SINTERING_DURATIONS = {
            3.0, 30.0, 60.0, 120.0, 180.0, 240.0, 300.0, 360.0, 480.0, 600.0
    };
}
