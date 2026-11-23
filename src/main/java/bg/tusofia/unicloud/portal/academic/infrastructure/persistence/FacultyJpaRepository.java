package bg.tusofia.unicloud.portal.academic.infrastructure.persistence;

import bg.tusofia.unicloud.portal.academic.infrastructure.persistence.entity.FacultyJPAEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FacultyJpaRepository extends JpaRepository<FacultyJPAEntity, String> {

    List<FacultyJPAEntity> findAllByOrderByCodeAsc();
}
