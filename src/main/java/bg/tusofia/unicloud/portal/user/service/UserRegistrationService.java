package bg.tusofia.unicloud.portal.user.service;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.exception.DuplicateResourceException;
import bg.tusofia.unicloud.portal.user.domain.Role;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.Principal;

@Service
public class UserRegistrationService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final AcademicStructure academicStructure;


    public UserRegistrationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AcademicStructure academicStructure) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.academicStructure = academicStructure;
    }

    @Transactional
    public User register(
            RegisterUserCommand registerUserCommand,
            String principalId) {

        User principal = userRepository.findById(Long.valueOf(principalId)).orElseThrow( () ->
                new IllegalArgumentException("Invalid principal"));

        checkUserRegistrationAllowed(registerUserCommand, principal);

        if (userRepository.existsByEmail(registerUserCommand.email())) {
            throw new IllegalArgumentException("Email already registered");
        }


        Role role = Role.valueOf(registerUserCommand.role());
        String faculty = academicStructure.facultyForNewUser(role, registerUserCommand.faculty());

        User user = User.createNew(
                registerUserCommand.email(),
                passwordEncoder.encode(registerUserCommand.password()),
                registerUserCommand.firstName(),
                registerUserCommand.lastName(),
                registerUserCommand.role(),
                faculty
        );

        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Two requests for the same faculty's dean at once: the database's unique key decides
            if (role == Role.DEAN) {
                throw new DuplicateResourceException(AcademicStructure.secondDeanMessage(faculty));
            }
            throw e;
        }
    }

    private void checkUserRegistrationAllowed(RegisterUserCommand command, User principal) {
        Role principalRole = principal.getRole();
        Role targetRole = Role.valueOf(command.role());

        boolean allowed = switch (principalRole) {
            case ADMIN -> targetRole == Role.ADMIN
                    || targetRole == Role.TEACHER
                    || targetRole == Role.DEAN
                    || targetRole == Role.INSPECTOR;

            case INSPECTOR -> targetRole == Role.STUDENT;

            default -> false;
        };

        if (!allowed) {
            throw new AccessDeniedException(
                    "You are not allowed to register users with role: " + targetRole
            );
        }
    }
}
