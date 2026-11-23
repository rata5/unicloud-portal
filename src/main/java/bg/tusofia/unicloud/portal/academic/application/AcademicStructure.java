package bg.tusofia.unicloud.portal.academic.application;

import bg.tusofia.unicloud.portal.academic.domain.Faculty;
import bg.tusofia.unicloud.portal.academic.domain.Specialty;
import bg.tusofia.unicloud.portal.academic.infrastructure.persistence.FacultyJpaRepository;
import bg.tusofia.unicloud.portal.academic.infrastructure.persistence.SpecialtyJpaRepository;
import bg.tusofia.unicloud.portal.academic.infrastructure.persistence.entity.FacultyJPAEntity;
import bg.tusofia.unicloud.portal.academic.infrastructure.persistence.entity.SpecialtyJPAEntity;
import bg.tusofia.unicloud.portal.exception.DuplicateResourceException;
import bg.tusofia.unicloud.portal.student.domain.Student;
import bg.tusofia.unicloud.portal.student.domain.StudentRepository;
import bg.tusofia.unicloud.portal.user.domain.Role;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Faculties and specialties (V5), and who belongs to which faculty
 * (openspec/changes/complete-java-portal/specs/academic-profile):
 * <ul>
 *   <li>a teacher, dean or inspector belongs to the faculty stored on the user;</li>
 *   <li>a student belongs to the faculty of the student's specialty;</li>
 *   <li>an admin belongs to none.</li>
 * </ul>
 */
@Service
public class AcademicStructure {

    private final FacultyJpaRepository faculties;
    private final SpecialtyJpaRepository specialties;
    private final UserRepository users;
    private final StudentRepository students;

    public AcademicStructure(
            FacultyJpaRepository faculties,
            SpecialtyJpaRepository specialties,
            UserRepository users,
            StudentRepository students) {

        this.faculties = faculties;
        this.specialties = specialties;
        this.users = users;
        this.students = students;
    }

    public List<Faculty> faculties() {
        return faculties.findAllByOrderByCodeAsc().stream().map(FacultyJPAEntity::toDomain).toList();
    }

    public List<Specialty> specialties() {
        return specialties.findAllByOrderByFacultyCodeAscNameAsc().stream()
                .map(SpecialtyJPAEntity::toDomain).toList();
    }

    public Optional<Faculty> faculty(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return faculties.findById(code.strip().toUpperCase(Locale.ROOT)).map(FacultyJPAEntity::toDomain);
    }

    /** A specialty by its id (digits only) or by its name, in any letter case. */
    public Optional<Specialty> specialty(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        String value = idOrName.strip();
        if (value.chars().allMatch(Character::isDigit) && value.length() <= 9) {
            return specialties.findById(Integer.valueOf(value)).map(SpecialtyJPAEntity::toDomain);
        }
        return specialties.findFirstByNameIgnoreCaseOrderByIdAsc(value).map(SpecialtyJPAEntity::toDomain);
    }

    /**
     * The specialty a new academic record names, or a 400 that names {@code field}.
     */
    public Specialty requireSpecialty(String idOrName, String field) {
        return specialty(idOrName).orElseThrow(() -> new IllegalArgumentException(
                field + ": unknown specialty '" + idOrName + "', known: "
                        + specialties().stream().map(Specialty::name).toList()));
    }

    /**
     * Checks the faculty a new user with {@code role} gets, and returns its code: an existing
     * faculty for a teacher, dean or inspector, none for anyone else. A faculty has at most one
     * dean; asking for a second is a 409 with a Bulgarian message (role-access spec). The
     * database refuses a second dean too (uq_users_dean_faculty), in case two requests race.
     */
    public String facultyForNewUser(Role role, String facultyCode) {
        if (!role.belongsToAFaculty()) {
            if (facultyCode != null && !facultyCode.isBlank()) {
                throw new IllegalArgumentException(
                        "faculty: only a TEACHER, DEAN or INSPECTOR belongs to a faculty directly");
            }
            return null;
        }
        if (facultyCode == null || facultyCode.isBlank()) {
            throw new IllegalArgumentException("faculty: a " + role + " belongs to a faculty, give its code");
        }
        Faculty faculty = faculty(facultyCode).orElseThrow(() -> new IllegalArgumentException(
                "faculty: unknown faculty '" + facultyCode + "', known: "
                        + faculties().stream().map(Faculty::code).toList()));
        if (role == Role.DEAN && users.existsDeanOfFaculty(faculty.code())) {
            throw new DuplicateResourceException(secondDeanMessage(faculty.code()));
        }
        return faculty.code();
    }

    /** "FCST already has a dean", in Bulgarian, for the second-dean refusal. */
    public static String secondDeanMessage(String facultyCode) {
        return "Факултет " + facultyCode + " вече има декан.";
    }

    /** The user's faculty: their own, through the specialty, or none. */
    public Optional<Faculty> facultyOf(User user) {
        if (user.getFacultyCode() != null) {
            return faculty(user.getFacultyCode());
        }
        if (user.getRole() == Role.STUDENT) {
            return students.findById(user.getId()).flatMap(this::facultyOf);
        }
        return Optional.empty();
    }

    /** A student's faculty: the faculty of the student's specialty. */
    public Optional<Faculty> facultyOf(Student student) {
        if (student.getSpecialtyId() == null) {
            return Optional.empty();
        }
        return specialties.findById(student.getSpecialtyId())
                .map(SpecialtyJPAEntity::toDomain)
                .flatMap(specialty -> faculty(specialty.facultyCode()));
    }
}
