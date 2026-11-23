
package bg.tusofia.unicloud.portal.user.domain;

import bg.tusofia.unicloud.portal.user.domain.User;

import java.util.Optional;

public interface UserRepository {

    User save(User user);

    Optional<User> findById(Long id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Whether the faculty already has its one dean. */
    boolean existsDeanOfFaculty(String facultyCode);
}
