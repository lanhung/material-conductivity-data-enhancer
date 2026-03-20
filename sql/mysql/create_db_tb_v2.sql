-- drop database zirconia_conductivity_v2;
create database if not exists zirconia_conductivity_v2;

use zirconia_conductivity_v2;


-- Crystal structure dictionary table
CREATE TABLE crystal_structure_dict
(
    id        INT AUTO_INCREMENT PRIMARY KEY,
    code      VARCHAR(10) NOT NULL UNIQUE,
    full_name VARCHAR(50)
);

INSERT INTO crystal_structure_dict (code, full_name)
VALUES ('c', 'Cubic'),
       ('t', 'Tetragonal'),
       ('m', 'Monoclinic'),
       ('o', 'Orthogonal'),
       ('r', 'Rhombohedral'),
       ('β', 'Beta-phase');


-- Synthesis method dictionary table
CREATE TABLE synthesis_method_dict
(
    id   INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

INSERT INTO synthesis_method_dict (name)
VALUES ('/'),
       ('Commercialization'),
       ('Coprecipitation'),
       ('Coprecipitation method'),
       ('Directional melt crystallization'),
       ('Glycine method'),
       ('Hydrothermal synthesis'),
       ('Sol–gel method'),
       ('Solid-state synthesis');


-- Processing route dictionary table
CREATE TABLE processing_route_dict
(
    id   INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

INSERT INTO processing_route_dict (name)
VALUES ('/'),
       ('3D printing'),
       ('chemical vapor deposition'),
       ('cutting and polishing'),
       ('dry pressing'),
       ('isostatic pressing'),
       ('magnetron sputtering'),
       ('metal-organic chemical vapor deposition'),
       ('plasma spray deposition'),
       ('pulsed laser deposition'),
       ('RF sputtering'),
       ('spark plasma sintering'),
       ('spin coating'),
       ('spray pyrolysis'),
       ('tape casting'),
       ('ultrasonic atomization'),
       ('ultrasonic spray pyrolysis'),
       ('vacuum filtration'),
       ('vapor deposition');


-- Sample main table
CREATE TABLE material_samples
(
    sample_id                  INT PRIMARY KEY,
    reference                  VARCHAR(255),
    material_source_and_purity TEXT,
    synthesis_method_id        INT,
    processing_route_id        INT,
    operating_temperature      FLOAT,
    conductivity               DOUBLE,
    FOREIGN KEY (synthesis_method_id) REFERENCES synthesis_method_dict (id),
    FOREIGN KEY (processing_route_id) REFERENCES processing_route_dict (id)
);


-- Dopant detail table
CREATE TABLE sample_dopants
(
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    sample_id             INT NOT NULL,
    dopant_element        VARCHAR(10),
    dopant_ionic_radius   FLOAT,
    dopant_valence        INT,
    dopant_molar_fraction FLOAT,
    FOREIGN KEY (sample_id) REFERENCES material_samples (sample_id) ON DELETE CASCADE
);


-- Sintering steps table
CREATE TABLE sintering_steps
(
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    sample_id             INT NOT NULL,
    step_order            INT COMMENT 'Sintering step number',
    sintering_temperature FLOAT,
    sintering_duration    FLOAT,
    FOREIGN KEY (sample_id) REFERENCES material_samples (sample_id) ON DELETE CASCADE
);


-- Sample-crystal phase association table
CREATE TABLE sample_crystal_phases
(
    sample_id      INT NOT NULL,
    crystal_id     INT NOT NULL,
    is_major_phase BOOLEAN DEFAULT TRUE COMMENT 'Whether it is the major phase',
    PRIMARY KEY (sample_id, crystal_id),
    FOREIGN KEY (sample_id) REFERENCES material_samples (sample_id) ON DELETE CASCADE,
    FOREIGN KEY (crystal_id) REFERENCES crystal_structure_dict (id)
);
