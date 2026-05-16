package az.testup.service;

import az.testup.dto.response.AdminExamResponse;
import az.testup.entity.Exam;
import az.testup.enums.AuditAction;
import az.testup.enums.ExamStatus;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ExamRepository;
import az.testup.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AdminExamService {

    private final ExamRepository examRepository;
    private final SubmissionRepository submissionRepository;
    private final AuditLogService auditLogService;

    public Page<AdminExamResponse> getExams(String search, ExamStatus status, Long teacherId,
                                            String teacherRoleName, Pageable pageable) {
        return examRepository.searchExams(
                (search != null && !search.isBlank()) ? search : null,
                status,
                teacherId,
                teacherRoleName,
                pageable
        ).map(this::toResponse);
    }

    @Transactional
    public AdminExamResponse toggleSitePublished(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        exam.setSitePublished(!exam.isSitePublished());
        AdminExamResponse response = toResponse(examRepository.save(exam));
        auditLogService.log(
                exam.isSitePublished() ? AuditAction.EXAM_SITE_PUBLISHED : AuditAction.EXAM_SITE_UNPUBLISHED,
                "admin", "Admin", "EXAM", exam.getTitle(), null);
        return response;
    }

    @Transactional
    public AdminExamResponse setExamPrice(Long examId, BigDecimal price) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        exam.setPrice(price);
        return toResponse(examRepository.save(exam));
    }

    @Transactional
    public void deleteExam(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        long submittedCount = submissionRepository.countByExamIdAndSubmittedAtIsNotNull(examId);
        if (submittedCount > 0) {
            throw new BadRequestException(
                    submittedCount + " tələbənin nəticəsi olan imtahanı silmək olmaz. Əvvəlcə nəticələri export edin.");
        }
        auditLogService.log(AuditAction.EXAM_DELETED, "admin", "Admin", "EXAM", exam.getTitle(), null);
        submissionRepository.deleteByExamId(examId);
        examRepository.deleteById(examId);
    }

    public AdminExamResponse toResponse(Exam exam) {
        return new AdminExamResponse(
                exam.getId(),
                exam.getTitle(),
                exam.getTeacher().getFullName(),
                exam.getTeacher().getEmail(),
                exam.getSubjects(),
                exam.getStatus(),
                exam.isSitePublished(),
                exam.getPrice(),
                exam.getQuestions().size(),
                exam.getShareLink(),
                exam.getCreatedAt()
        );
    }
}
