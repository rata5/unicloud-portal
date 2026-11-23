package bg.tusofia.unicloud.portal.academic.infrastructure.persistence.entity;

import bg.tusofia.unicloud.portal.academic.domain.Specialty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of {@code specialties} (V5). Reference data: the portal reads it and never writes it. */
@Entity
@Table(name = "specialties")
public class SpecialtyJPAEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "faculty_code", nullable = false, length = 16)
    private String facultyCode;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    protected SpecialtyJPAEntity() {
        // Required by JPA
    }

    public Specialty toDomain() {
        return new Specialty(id, facultyCode, name);
    }
}
