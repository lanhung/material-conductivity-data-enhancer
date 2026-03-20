package com.lanhung.conductivity.generator;

import com.lanhung.conductivity.config.SynthesisMethod;
import com.lanhung.conductivity.model.DopantInfo;
import com.lanhung.conductivity.util.RandomUtils;
import com.lanhung.conductivity.util.WeightedItem;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 使用从真实实验数据中提取的模板，
 * 生成逼真的 material_source_and_purity 文本。
 */
public final class SourceTextGenerator implements Serializable {

    private SourceTextGenerator() {}

    private static final Map<String, String> ELEMENT_TO_OXIDE = new HashMap<>();
    static {
        ELEMENT_TO_OXIDE.put("Y",  "Y2O3");  ELEMENT_TO_OXIDE.put("Sc", "Sc2O3");
        ELEMENT_TO_OXIDE.put("Dy", "Dy2O3"); ELEMENT_TO_OXIDE.put("Bi", "Bi2O3");
        ELEMENT_TO_OXIDE.put("Pr", "Pr6O11"); ELEMENT_TO_OXIDE.put("Lu", "Lu2O3");
        ELEMENT_TO_OXIDE.put("Mn", "MnO");   ELEMENT_TO_OXIDE.put("Fe", "Fe2O3");
        ELEMENT_TO_OXIDE.put("Zn", "ZnO");   ELEMENT_TO_OXIDE.put("Ce", "CeO2");
        ELEMENT_TO_OXIDE.put("Gd", "Gd2O3"); ELEMENT_TO_OXIDE.put("Yb", "Yb2O3");
        ELEMENT_TO_OXIDE.put("Er", "Er2O3"); ELEMENT_TO_OXIDE.put("Al", "Al2O3");
        ELEMENT_TO_OXIDE.put("Ca", "CaO");   ELEMENT_TO_OXIDE.put("In", "In2O3");
        ELEMENT_TO_OXIDE.put("Eu", "Eu2O3"); ELEMENT_TO_OXIDE.put("Si", "SiO2");
        ELEMENT_TO_OXIDE.put("Nb", "Nb2O5"); ELEMENT_TO_OXIDE.put("Ti", "TiO2");
    }

    private static final String[] SUPPLIERS = {
            "Alfa Aesar", "Sigma Aldrich", "Tosoh", "Fluka",
            "StanfordMaterial", "Innovnano", "Z-Tech", "Kceracell",
            "Praxair", "SCRC", "Adventech", "Kerafol GmbH",
            "Toyo Soda", "Daiichi Kigenso", "Inframat Advanced Materials",
            "Nano One Materials", "Stanford Advanced Materials"
    };

    private static final String[] PRECURSORS = {
            "nitrate", "chloride", "acetate", "carbonate", "alkoxide"
    };

    @SuppressWarnings("unchecked")
    private static final WeightedItem<String>[] PURITY_DIST = new WeightedItem[]{
            new WeightedItem<>("99%", 0.15), new WeightedItem<>("99.5%", 0.25),
            new WeightedItem<>("99.9%", 0.35), new WeightedItem<>("99.95%", 0.15),
            new WeightedItem<>("99.99%", 0.10)
    };

    private static String oxideFormula(String element) {
        return ELEMENT_TO_OXIDE.getOrDefault(element, element + "Ox");
    }

    public static String generate(String synthesisMethod, List<DopantInfo> dopants, Random rng) {
        double r = rng.nextDouble();
        if (synthesisMethod.equals(SynthesisMethod.COMMERCIALIZATION)) {
            return r < 0.7 ? commercialTemplate(dopants, rng) : simpleTemplate(dopants, rng);
        } else {
            if (r < 0.55) return commercialTemplate(dopants, rng);
            else if (r < 0.85) return selfPreparedTemplate(dopants, rng);
            else return simpleTemplate(dopants, rng);
        }
    }

    private static String commercialTemplate(List<DopantInfo> dopants, Random rng) {
        StringBuilder sb = new StringBuilder();
        String zrSupplier = SUPPLIERS[rng.nextInt(SUPPLIERS.length)];
        String zrPurity = samplePurity(rng);
        sb.append("Commercial ZrO2 powder supplied by ").append(zrSupplier)
                .append(" with ").append(zrPurity).append(" purity");

        for (DopantInfo d : dopants) {
            String oxide = oxideFormula(d.element);
            String supplier = SUPPLIERS[rng.nextInt(SUPPLIERS.length)];
            String purity = samplePurity(rng);
            sb.append(", and commercial ").append(oxide).append(" powder supplied by ")
                    .append(supplier).append(" with ").append(purity).append(" purity");
        }
        sb.append(".");
        return sb.toString();
    }

    private static String selfPreparedTemplate(List<DopantInfo> dopants, Random rng) {
        String precursor = PRECURSORS[rng.nextInt(PRECURSORS.length)];

        if (rng.nextDouble() < 0.5) {
            String elements = dopants.stream().map(d -> d.element).collect(Collectors.joining(", "));
            return "Zirconium " + precursor + " and " + elements.toLowerCase()
                    + " " + precursor + " were used as starting materials.";
        } else {
            String supplier = SUPPLIERS[rng.nextInt(SUPPLIERS.length)];
            String purity = samplePurity(rng);
            String dopantDesc = dopants.stream().map(d -> {
                String s = SUPPLIERS[rng.nextInt(SUPPLIERS.length)];
                return "commercial " + oxideFormula(d.element) + " powder supplied by " + s;
            }).collect(Collectors.joining(", and "));
            return "Self-prepared ZrO2 powder with " + purity + " purity from "
                    + supplier + ", and " + dopantDesc + ".";
        }
    }

    private static String simpleTemplate(List<DopantInfo> dopants, Random rng) {
        DopantInfo primary = dopants.stream()
                .max((a, b) -> Double.compare(a.molarFraction, b.molarFraction))
                .orElse(dopants.get(0));
        String oxide = oxideFormula(primary.element);
        String concPct = String.format("%.1f", primary.molarFraction * 100);

        if (rng.nextDouble() < 0.5) {
            return "Commercial " + primary.element + "-doped ZrO2 material with "
                    + concPct + " mol% " + oxide + ".";
        } else {
            String supplier = SUPPLIERS[rng.nextInt(SUPPLIERS.length)];
            return "Commercial " + oxide + "-stabilized ZrO2 powder supplied by " + supplier + ".";
        }
    }

    private static String samplePurity(Random rng) {
        return RandomUtils.sampleCategorical(rng, PURITY_DIST);
    }
}
