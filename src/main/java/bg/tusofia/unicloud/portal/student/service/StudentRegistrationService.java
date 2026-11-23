package bg.tusofia.unicloud.portal.student.service;

import bg.tusofia.unicloud.portal.academic.application.AcademicStructure;
import bg.tusofia.unicloud.portal.academic.domain.Specialty;
import bg.tusofia.unicloud.portal.student.domain.Student;
import bg.tusofia.unicloud.portal.student.domain.StudentRepository;
import bg.tusofia.unicloud.portal.student.domain.StudentStatus;
import bg.tusofia.unicloud.portal.user.domain.Role;
import bg.tusofia.unicloud.portal.user.domain.User;
import bg.tusofia.unicloud.portal.user.service.RegisterUserCommand;
import bg.tusofia.unicloud.portal.user.service.UserRegistrationService;
import org.springframework.stereotype.Service;

@Service
public class StudentRegistrationService {

    private final UserRegistrationService userRegistrationSvc;
    private final StudentRepository studentRepository;
    private final AcademicStructure academicStructure;

    public StudentRegistrationService(UserRegistrationService userRegistrationSvc,
                                      StudentRepository studentRepository,
                                      AcademicStructure academicStructure) {
        this.userRegistrationSvc = userRegistrationSvc;
        this.studentRepository = studentRepository;
        this.academicStructure = academicStructure;
    }

    public Student registerStudent(String email,
                                   String password,
                                   String firstName,
                                   String lastName,
                                   String facultyNumber,
                                   String specialty,
                                   int studyYear,
                                   String studentStatus,
                                   String principalEmail) {

        // Before the user is saved, so an unknown specialty leaves nothing behind
        Specialty known = academicStructure.requireSpecialty(specialty, "specialty");

        User studentUser = userRegistrationSvc.register(
                new RegisterUserCommand(
                        email,
                        password,
                        firstName,
                        lastName,
                        Role.STUDENT.toString(),
                        null), principalEmail);

        Student newStudent = Student.createNew(studentUser.getId(), facultyNumber, known.id(), known.name(),
                studyYear);

        newStudent = studentRepository.save(newStudent);

        return newStudent;

    }
}
