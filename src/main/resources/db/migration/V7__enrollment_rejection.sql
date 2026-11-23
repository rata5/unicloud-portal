-- The teacher of an offering may reject an enrollment, which frees the place (enrollment spec).
-- A rejected student cannot enroll in that offering again. decided_by is the teacher who rejected.
-- The PostgreSQL copy is db/migration-postgresql/V7__enrollment_rejection.sql.
ALTER TABLE enrollments DROP CHECK chk_enrollments_status;
ALTER TABLE enrollments
    ADD CONSTRAINT chk_enrollments_status CHECK (status IN ('ENROLLED', 'CANCELLED', 'REJECTED'));

ALTER TABLE enrollments
    ADD COLUMN decided_by BIGINT UNSIGNED NULL AFTER status,
    ADD CONSTRAINT fk_enrollments_decided_by FOREIGN KEY (decided_by) REFERENCES users (id);
