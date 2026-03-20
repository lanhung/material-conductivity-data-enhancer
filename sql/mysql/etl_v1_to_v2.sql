USE zirconia_conductivity_v2;

-- 1. Migrate material_samples (JOIN dict tables to convert strings to IDs)
INSERT INTO zirconia_conductivity_v2.material_samples
    (sample_id, reference, material_source_and_purity,
     synthesis_method_id, processing_route_id,
     operating_temperature, conductivity)
SELECT ms.sample_id, ms.reference, ms.material_source_and_purity,
       sm.id, pr.id,
       ms.operating_temperature, ms.conductivity
FROM zirconia_conductivity.material_samples ms
LEFT JOIN zirconia_conductivity_v2.synthesis_method_dict sm
    ON ms.synthesis_method = sm.name
LEFT JOIN zirconia_conductivity_v2.processing_route_dict pr
    ON ms.processing_route = pr.name;

-- 2. Copy sample_dopants
INSERT INTO zirconia_conductivity_v2.sample_dopants
    (id, sample_id, dopant_element, dopant_ionic_radius, dopant_valence, dopant_molar_fraction)
SELECT id, sample_id, dopant_element, dopant_ionic_radius, dopant_valence, dopant_molar_fraction
FROM zirconia_conductivity.sample_dopants;

-- 3. Copy sintering_steps
INSERT INTO zirconia_conductivity_v2.sintering_steps
    (id, sample_id, step_order, sintering_temperature, sintering_duration)
SELECT id, sample_id, step_order, sintering_temperature, sintering_duration
FROM zirconia_conductivity.sintering_steps;

-- 4. Copy sample_crystal_phases
INSERT INTO zirconia_conductivity_v2.sample_crystal_phases
    (sample_id, crystal_id, is_major_phase)
SELECT sample_id, crystal_id, is_major_phase
FROM zirconia_conductivity.sample_crystal_phases;
