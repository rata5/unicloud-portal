package bg.tusofia.unicloud.portal.student.infrastructure.persistence;

import bg.tusofia.unicloud.portal.student.domain.Student;
import bg.tusofia.unicloud.portal.student.infrastructure.persistence.entity.StudentJPAEntity;

public final class StudentPersistenceMapper {

    private StudentPersistenceMapper() {
        // Utility class
    }

    public static StudentJPAEntity toJpaEntity(Student student) {
        return new StudentJPAEntity(
                student.getUserId(),
                student.getFacultyNumber(),
                student.getSpecialtyId(),
                student.getSpecialty(),
                student.getStudyYear(),
                student.getStatus()
        );
    }

    public static Student toDomain(StudentJPAEntity entity) {
        return Student.reconstitute(
                entity.getUserId(),
                entity.getFacultyNumber(),
                entity.getSpecialtyId(),
                entity.getSpecialty(),
                entity.getStudyYear(),
                entity.getStatus()
        );
    }
}