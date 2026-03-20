-- Hive external tables for ods_zirconia_conductivity_v2
-- These tables read Parquet files written by the MySQL-to-HDFS sync job.
-- HDFS base path: /data/material_conductivity_data

CREATE DATABASE IF NOT EXISTS ods_zirconia_conductivity_v2
LOCATION '/data/material_conductivity_data/ods_zirconia_conductivity_v2';
USE ods_zirconia_conductivity_v2;


-- Crystal structure dictionary table
CREATE EXTERNAL TABLE IF NOT EXISTS crystal_structure_dict (
    id        INT,
    code      STRING,
    full_name STRING
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/ods_zirconia_conductivity_v2/crystal_structure_dict';


-- Synthesis method dictionary table
CREATE EXTERNAL TABLE IF NOT EXISTS synthesis_method_dict (
    id   INT,
    name STRING
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/ods_zirconia_conductivity_v2/synthesis_method_dict';


-- Processing route dictionary table
CREATE EXTERNAL TABLE IF NOT EXISTS processing_route_dict (
    id   INT,
    name STRING
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/ods_zirconia_conductivity_v2/processing_route_dict';


-- Sample main table
CREATE EXTERNAL TABLE IF NOT EXISTS material_samples (
    sample_id                  INT,
    reference                  STRING,
    material_source_and_purity STRING,
    synthesis_method_id        INT,
    processing_route_id        INT,
    operating_temperature      DOUBLE,
    conductivity               DOUBLE
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/ods_zirconia_conductivity_v2/material_samples';


-- Dopant detail table
CREATE EXTERNAL TABLE IF NOT EXISTS sample_dopants (
    id                    INT,
    sample_id             INT,
    dopant_element        STRING,
    dopant_ionic_radius   DOUBLE,
    dopant_valence        INT,
    dopant_molar_fraction DOUBLE
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/ods_zirconia_conductivity_v2/sample_dopants';


-- Sintering steps table
CREATE EXTERNAL TABLE IF NOT EXISTS sintering_steps (
    id                    INT,
    sample_id             INT,
    step_order            INT    COMMENT 'Sintering step number',
    sintering_temperature DOUBLE,
    sintering_duration    DOUBLE
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/ods_zirconia_conductivity_v2/sintering_steps';


-- Sample-crystal phase association table
CREATE EXTERNAL TABLE IF NOT EXISTS sample_crystal_phases (
    sample_id      INT,
    crystal_id     INT,
    is_major_phase BOOLEAN COMMENT 'Whether it is the major phase'
)
STORED AS PARQUET
LOCATION '/data/material_conductivity_data/ods_zirconia_conductivity_v2/sample_crystal_phases';
