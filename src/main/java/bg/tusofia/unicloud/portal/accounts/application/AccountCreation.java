package bg.tusofia.unicloud.portal.accounts.application;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.academic.domain.Faculty;
import bg.tusofia.unicloud.portal.academic.domain.Specialty;
import bg.tusofia.unicloud.portal.student.domain.Student;
import bg.tusofia.unicloud.portal.student.domain.StudentRepository;
import bg.tusofia.unicloud.portal.user.domain.Role;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Creating accounts from the portal (accounts-and-sign-in spec): inspectors create students,
 * admins create staff, there is no public sign-up, and every user made here must change the
 * first password at the first sign-in. Ported from the fork's {@code accounts/admin.rs}, with its
 * rules and Bulgarian messages. Who may create whom is the caller's check.
 */
@Service
public class AccountCreation {

    /** A refused account: {@code duplicate} for an email, faculty number or dean already taken. */
    public static final class Refused extends RuntimeException {
        private final boolean duplicate;

        private Refused(String message, boolean duplicate) {
            super(message);
            this.duplicate = duplicate;
        }

        static Refused invalid(String message) {
            return new Refused(message, false);
        }

        static Refused duplicate(String message) {
            return new Refused(message, true);
        }

        public boolean duplicate() {
            return duplicate;
        }
    }

    public record NewAccount(String email, String password, String firstName, String lastName, Role role,
                             String facultyCode, NewRecord student) {
    }

    public record NewRecord(String facultyNumber, Integer specialtyId, Integer studyYear) {
    }

    private final UserRepository users;
    private final StudentRepository students;
    private final AcademicStructure academicStructure;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public AccountCreation(
            UserRepository users,
            StudentRepository students,
            AcademicStructure academicStructure,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbc) {

        this.users = users;
        this.students = students;
        this.academicStructure = academicStructure;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    /** The new user's id. In one transaction: a refusal leaves no user and no record behind. */
    @Transactional
    public long create(NewAccount account) {
        String email = account.email() == null ? "" : account.email().strip().toLowerCase(Locale.ROOT);
        if (!validEmail(email)) {
            throw Refused.invalid("Невалиден имейл.");
        }
        String first = account.firstName() == null ? "" : account.firstName().strip();
        String last = account.lastName() == null ? "" : account.lastName().strip();
        if (first.isEmpty() || last.isEmpty() || first.length() > 100 || last.length() > 100) {
            throw Refused.invalid("Името и фамилията са задължителни (до 100 знака).");
        }
        String password = account.password() == null ? "" : account.password();
        int length = password.codePointCount(0, password.length());
        if (length < 8) {
            throw Refused.invalid("Паролата трябва да е поне 8 знака.");
        }
        if (length > 72) {
            throw Refused.invalid("Паролата трябва да е най-много 72 знака.");
        }
        Role role = account.role();
        String faculty = null;
        if (role.belongsToAFaculty()) {
            if (account.facultyCode() == null || account.facultyCode().isBlank()) {
                throw Refused.invalid("Изберете факултет.");
            }
            faculty = academicStructure.faculty(account.facultyCode())
                    .map(Faculty::code)
                    .orElseThrow(() -> Refused.invalid("Непознат факултет."));
            if (role == Role.DEAN && users.existsDeanOfFaculty(faculty)) {
                throw Refused.duplicate(AcademicStructure.secondDeanMessage(faculty));
            }
        }
        if ((role == Role.STUDENT) != (account.student() != null)) {
            throw Refused.invalid("Само студент има академичен профил.");
        }
        Specialty specialty = null;
        String facultyNumber = null;
        if (account.student() != null) {
            NewRecord record = account.student();
            facultyNumber = record.facultyNumber() == null ? "" : record.facultyNumber().strip();
            if (facultyNumber.isEmpty() || facultyNumber.length() > 20) {
                throw Refused.invalid("Факултетният номер е задължителен (до 20 знака).");
            }
            if (record.studyYear() == null || record.studyYear() < 1 || record.studyYear() > 6) {
                throw Refused.invalid("Курсът трябва да е от 1 до 6.");
            }
            specialty = record.specialtyId() == null ? null
                    : academicStructure.specialty(String.valueOf(record.specialtyId())).orElse(null);
            if (specialty == null) {
                throw Refused.invalid("Непозната специалност.");
            }
            Integer taken = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM students WHERE faculty_number = ?", Integer.class, facultyNumber);
            if (taken != null && taken > 0) {
                throw Refused.duplicate("Факултетният номер вече съществува.");
            }
        }
        if (users.existsByEmail(email)) {
            throw Refused.duplicate("Имейлът вече се използва.");
        }

        User user = User.createNew(email, passwordEncoder.encode(password), first, last, role.name(), faculty);
        user.requirePasswordChange();
        User saved;
        try {
            saved = users.save(user);
        } catch (DataIntegrityViolationException e) {
            // A parallel request took the email, or the faculty's dean, a moment earlier
            throw Refused.duplicate(role == Role.DEAN
                    ? "Имейлът вече се използва или факултетът вече има декан."
                    : "Имейлът вече се използва.");
        }
        if (specialty != null) {
            students.save(Student.createNew(saved.getId(), facultyNumber, specialty.id(), specialty.name(),
                    account.student().studyYear()));
        }
        return saved.getId();
    }

    /** As the fork checks it: one @, something before it, a dot after it, no spaces, 254 at most. */
    static boolean validEmail(String email) {
        int at = email.indexOf('@');
        if (email.length() > 254 || at <= 0 || email.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        String domain = email.substring(at + 1);
        return domain.contains(".") && !domain.startsWith(".");
    }
}
