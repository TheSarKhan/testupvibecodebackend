package az.testup.service;

import az.testup.dto.response.DepotExamResponse;
import az.testup.entity.Exam;
import az.testup.entity.StudentSavedExam;
import az.testup.entity.User;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ExamRepository;
import az.testup.repository.StudentSavedExamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DepotService {

    private final StudentSavedExamRepository savedExamRepository;
    private final ExamRepository examRepository;

    @Transactional
    public void saveExam(String shareLink, User student) {
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (savedExamRepository.existsByStudentIdAndExamId(student.getId(), exam.getId())) {
            return; // already saved — idempotent
        }

        savedExamRepository.save(StudentSavedExam.builder()
                .student(student)
                .exam(exam)
                .build());
    }

    @Transactional
    public void removeExam(String shareLink, User student) {
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        savedExamRepository.deleteByStudentIdAndExamId(student.getId(), exam.getId());
    }

    @Transactional(readOnly = true)
    public List<DepotExamResponse> getDepot(User student) {
        return savedExamRepository.findByStudentIdOrderBySavedAtDesc(student.getId()).stream()
                .map(s -> mapToResponse(s))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public boolean isSaved(String shareLink, User student) {
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        return savedExamRepository.existsByStudentIdAndExamId(student.getId(), exam.getId());
    }

    private DepotExamResponse mapToResponse(StudentSavedExam s) {
        Exam exam = s.getExam();
        boolean isPaid = exam.getPrice() != null && exam.getPrice().compareTo(java.math.BigDecimal.ZERO) > 0;
        return new DepotExamResponse(
                exam.getId(),
                exam.getTitle(),
                exam.getDescription(),
                exam.getSubjects(),
                exam.getShareLink(),
                exam.getQuestions().size(),
                exam.getDurationMinutes(),
                exam.getPrice(),
                isPaid,
                s.getSavedAt()
        );
    }
}
