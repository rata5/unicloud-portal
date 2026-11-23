-- The PostgreSQL copy of db/migration/V7__enrollment_rejection.sql.
-- The teacher of an offering may reject an enrollment, which frees the place (enrollment spec).
-- A rejected student cannot enroll in that offering again. decided_by is the teacher who rejected.
ALTER TABLE enrollments DROP CONSTRAINT chk_enrollments_status;
ALTER TABLE enrollments
    ADD CONSTRAINT chk_enrollments_status CHECK (status IN ('ENROLLED', 'CANCELLED', 'REJECTED'));

ALTER TABLE enrollments
    ADD COLUMN decided_by BIGINT NULL,
    ADD CONSTRAINT fk_enrollments_decided_by FOREIGN KEY (decided_by) REFERENCES users (id);
