-- Faculties and specialties become tables, and a fifth role, DEAN, leads one faculty
-- (openspec/changes/complete-java-portal, task group 1). The PostgreSQL copy is
-- db/migration-postgresql/V5__faculties_and_deans.sql.
--
-- Safe while an older replica still runs: every new column allows NULL, and students.specialty
-- (the text) stays. The code writes both the text and specialty_id; a later release makes
-- specialty_id NOT NULL and drops the text.

-- A faculty of the university. The code is the identifier other UniCloud products use.
CREATE TABLE faculties (
    code  VARCHAR(16)   NOT NULL,   -- e.g. FCST
    name  VARCHAR(200)  NOT NULL,
    PRIMARY KEY (code)
);

-- A specialty belongs to one faculty; a student's faculty is the faculty of their specialty.
CREATE TABLE specialties (
    id            INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    faculty_code  VARCHAR(16)   NOT NULL,
    name          VARCHAR(200)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_specialties_faculty_name (faculty_code, name),
    CONSTRAINT fk_specialties_faculty FOREIGN KEY (faculty_code) REFERENCES faculties (code)
);

-- The same reference data as the fork's rust-rewrite branch (migrations/0002_reference_data.sql).
INSERT INTO faculties (code, name) VALUES
('FCST', 'Факултет по компютърни системи и технологии'),
('FAMI', 'Факултет по приложна математика и информатика'),
('FE',   'Електротехнически факултет');

INSERT INTO specialties (faculty_code, name) VALUES
('FCST', 'Компютърни науки'),
('FCST', 'Софтуерно инженерство'),
('FCST', 'Компютърно и софтуерно инженерство'),
('FAMI', 'Информатика'),
('FAMI', 'Приложна математика'),
('FE',   'Електроенергетика');

-- Any other specialty a student already has becomes a specialty of FCST, the only faculty the
-- portal served so far, so every academic record links to a specialty. (The default collation
-- compares case-insensitively, so "компютърни науки" finds "Компютърни науки".)
INSERT INTO specialties (faculty_code, name)
SELECT DISTINCT 'FCST', s.specialty
FROM students s
WHERE NOT EXISTS (SELECT 1 FROM specialties sp WHERE sp.name = s.specialty);

ALTER TABLE students
    ADD COLUMN specialty_id INT UNSIGNED NULL AFTER specialty,
    ADD CONSTRAINT fk_students_specialty FOREIGN KEY (specialty_id) REFERENCES specialties (id);

UPDATE students s
JOIN specialties sp ON sp.name = s.specialty
SET s.specialty_id = sp.id;

-- Teachers, deans and inspectors belong to one faculty. Admins and students have none here
-- (a student's faculty comes from the specialty).
ALTER TABLE users
    ADD COLUMN faculty_code VARCHAR(16) NULL AFTER permissions_mask,
    ADD CONSTRAINT fk_users_faculty FOREIGN KEY (faculty_code) REFERENCES faculties (code);

-- The staff the portal already has worked for FCST, the only faculty it knew.
UPDATE users SET faculty_code = 'FCST' WHERE role IN ('TEACHER', 'INSPECTOR');

ALTER TABLE users DROP CHECK chk_users_role;
ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('STUDENT', 'TEACHER', 'DEAN', 'INSPECTOR', 'ADMIN'));

-- At most one dean per faculty. MySQL has no partial unique index, so a stored generated column
-- holds the faculty for deans only (NULL for everyone else, and a unique key allows many NULLs).
-- The entities do not map it; the database fills it.
ALTER TABLE users
    ADD COLUMN dean_faculty_code VARCHAR(16)
        GENERATED ALWAYS AS (CASE WHEN role = 'DEAN' THEN faculty_code END) STORED,
    ADD UNIQUE KEY uq_users_dean_faculty (dean_faculty_code);

-- A course belongs to one faculty. The courses so far are FCST's.
ALTER TABLE courses
    ADD COLUMN faculty_code VARCHAR(16) NULL AFTER ects_credits,
    ADD CONSTRAINT fk_courses_faculty FOREIGN KEY (faculty_code) REFERENCES faculties (code);

UPDATE courses SET faculty_code = 'FCST';
