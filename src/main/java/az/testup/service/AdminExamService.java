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
        boolean newSitePublished = !exam.isSitePublished();
        exam.setSitePublished(newSitePublished);

        // Site-publish only flips a flag; the public catalog ALSO filters by status —
        // exams that are still DRAFT never appear even when sitePublished=true. For
        // collaborative exams (admin-owned parent), the parent stays DRAFT until someone
        // explicitly moves it past DRAFT. Auto-promote on first site-publish so admins
        // don't have to chase a second "publish" button to make the exam visible to
        // students. Unpublishing leaves status alone — the admin can re-publish later.
        if (newSitePublished && exam.getStatus() == ExamStatus.DRAFT) {
            exam.setStatus(ExamStatus.PUBLISHED);
        }

        AdminExamResponse response = toResponse(examRepository.save(exam));
        auditLogService.log(
                newSitePublished ? AuditAction.EXAM_SITE_PUBLISHED : AuditAction.EXAM_SITE_UNPUBLISHED,
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
        // Soft-delete, mirroring the teacher delete path (ExamService): the exam
        // is hidden everywhere via the global isDeleted() filters (catalog, depot,
        // purchased list, start guard), while student results and purchase/payment
        // history stay intact. A hard DELETE here used to fail anyway — exams is
        // still referenced by exam_purchases / payment_orders / questions / etc.
        // (FK violation) — and would have destroyed paid students' access and
        // their results. Soft-delete is reversible and FK-safe.
        exam.setDeleted(true);
        examRepository.save(exam);
        auditLogService.log(AuditAction.EXAM_DELETED, "admin", "Admin", "EXAM", exam.getTitle(), null);
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
