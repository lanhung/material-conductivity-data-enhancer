-- Hive external tables for compliant rule-based output
-- Database: conductivity_compliant_rule_based
-- HDFS base path: /data/material_conductivity_data/conductivity_compliant_rule_based

CREATE DATABASE IF NOT EXISTS conductivity_compliant_rule_based;
USE conductivity_compliant_rule_based;

-- Crystal structure dictionary table
CREATE EXTERNAL TABLE IF NOT EXISTS crystal_structure_dict (
    id        INT,
    code      STRING,
    full_name STRING
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_compliant_rule_based/crystal_structure_dict';

INSERT OVERWRITE TABLE crystal_structure_dict VALUES
    (1, 'c', 'Cubic'),
    (2, 't', 'Tetragonal'),
    (3, 'm', 'Monoclinic'),
    (4, 'o', 'Orthogonal'),
    (5, 'r', 'Rhombohedral'),
    (6, 'β', 'Beta-phase');


-- Synthesis method dictionary table
CREATE EXTERNAL TABLE IF NOT EXISTS synthesis_method_dict (
    id   INT,
    name STRING
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_compliant_rule_based/synthesis_method_dict';

INSERT OVERWRITE TABLE synthesis_method_dict VALUES
    (1, '/'),
    (2, 'Commercialization'),
    (3, 'Coprecipitation'),
    (4, 'Coprecipitation method'),
    (5, 'Directional melt crystallization'),
    (6, 'Glycine method'),
    (7, 'Hydrothermal synthesis'),
    (8, 'Sol–gel method'),
    (9, 'Solid-state synthesis');


-- Processing route dictionary table
CREATE EXTERNAL TABLE IF NOT EXISTS processing_route_dict (
    id   INT,
    name STRING
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_compliant_rule_based/processing_route_dict';

INSERT OVERWRITE TABLE processing_route_dict VALUES
    (1, '/'),
    (2, '3D printing'),
    (3, 'chemical vapor deposition'),
    (4, 'cutting and polishing'),
    (5, 'dry pressing'),
    (6, 'isostatic pressing'),
    (7, 'magnetron sputtering'),
    (8, 'metal-organic chemical vapor deposition'),
    (9, 'plasma spray deposition'),
    (10, 'pulsed laser deposition'),
    (11, 'RF sputtering'),
    (12, 'spark plasma sintering'),
    (13, 'spin coating'),
    (14, 'spray pyrolysis'),
    (15, 'tape casting'),
    (16, 'ultrasonic atomization'),
    (17, 'ultrasonic spray pyrolysis'),
    (18, 'vacuum filtration'),
    (19, 'vapor deposition');


-- Sample main table
CREATE EXTERNAL TABLE IF NOT EXISTS material_samples (
    sample_id                  BIGINT,
    reference                  STRING,
    material_source_and_purity STRING,
    synthesis_method_id        INT,
    processing_route_id        INT,
    operating_temperature      DOUBLE,
    conductivity               DOUBLE
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_compliant_rule_based/material_samples';


-- Dopant detail table
CREATE EXTERNAL TABLE IF NOT EXISTS sample_dopants (
    sample_id             BIGINT,
    dopant_element        STRING,
    dopant_ionic_radius   DOUBLE,
    dopant_valence        INT,
    dopant_molar_fraction DOUBLE
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_compliant_rule_based/sample_dopants';


-- Sintering steps table
CREATE EXTERNAL TABLE IF NOT EXISTS sintering_steps (
    sample_id             BIGINT,
    step_order            INT,
    sintering_temperature DOUBLE,
    sintering_duration    DOUBLE
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_compliant_rule_based/sintering_steps';


-- Sample-crystal phase association table
CREATE EXTERNAL TABLE IF NOT EXISTS sample_crystal_phases (
    sample_id      BIGINT,
    crystal_id     INT,
    is_major_phase BOOLEAN
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/conductivity_compliant_rule_based/sample_crystal_phases';
