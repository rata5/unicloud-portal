package bg.tusofia.unicloud.portal.academic.infrastructure.persistence;

import bg.tusofia.unicloud.portal.academic.infrastructure.persistence.entity.SpecialtyJPAEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecialtyJpaRepository extends JpaRepository<SpecialtyJPAEntity, Integer> {

    Optional<SpecialtyJPAEntity> findFirstByNameIgnoreCaseOrderByIdAsc(String name);

    List<SpecialtyJPAEntity> findAllByOrderByFacultyCodeAscNameAsc();
}
