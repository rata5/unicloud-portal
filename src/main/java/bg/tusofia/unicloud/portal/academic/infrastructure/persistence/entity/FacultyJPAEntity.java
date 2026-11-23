package bg.tusofia.unicloud.portal.academic.infrastructure.persistence.entity;

import bg.tusofia.unicloud.portal.academic.domain.Faculty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of {@code faculties} (V5). Reference data: the portal reads it and never writes it. */
@Entity
@Table(name = "faculties")
public class FacultyJPAEntity {

    @Id
    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    protected FacultyJPAEntity() {
        // Required by JPA
    }

    public Faculty toDomain() {
        return new Faculty(code, name);
    }
}
