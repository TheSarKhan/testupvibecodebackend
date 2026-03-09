package az.testup.service;

import az.testup.dto.request.ExamRequest;
import az.testup.dto.request.MatchingPairRequest;
import az.testup.dto.request.OptionRequest;
import az.testup.dto.request.QuestionRequest;
import az.testup.dto.response.ExamResponse;
import az.testup.dto.response.MatchingPairResponse;
import az.testup.dto.response.OptionResponse;
import az.testup.dto.response.QuestionResponse;
import az.testup.entity.*;
import az.testup.enums.ExamStatus;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ExamRepository;
import az.testup.repository.TemplateRepository;
import az.testup.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository examRepository;
    private final TemplateRepository templateRepository;

    @Transactional
    public ExamResponse createExam(ExamRequest request, User teacher) {
        Exam exam = Exam.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .subject(request.getSubject())
                .visibility(request.getVisibility())
                .examType(request.getExamType())
                .status(request.getStatus())
                .durationMinutes(request.getDurationMinutes())
                .teacher(teacher)
                .shareLink(CodeGenerator.generateShareLink())
                .tags(request.getTags() != null ? new ArrayList<>(request.getTags()) : new ArrayList<>())
                .build();

        if (request.getTemplateId() != null) {
            Template template = templateRepository.findById(request.getTemplateId())
                    .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
            exam.setTemplate(template);
        }

        if (request.getQuestions() != null) {
            for (QuestionRequest qReq : request.getQuestions()) {
                Question question = mapToQuestion(qReq, exam);
                exam.getQuestions().add(question);
            }
        }

        Exam savedExam = examRepository.save(exam);
        return mapToResponse(savedExam);
    }

    public List<ExamResponse> getTeacherExams(User teacher) {
        return examRepository.findByTeacher(teacher).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public ExamResponse getExamById(Long id, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        
        if (!exam.getTeacher().getId().equals(teacher.getId())) {
            throw new RuntimeException("Bu imtahana giriş icazəniz yoxdur");
        }
        
        return mapToResponse(exam);
    }

    @Transactional
    public ExamResponse updateExam(Long id, ExamRequest request, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (!exam.getTeacher().getId().equals(teacher.getId())) {
            throw new RuntimeException("Bu imtahanı redaktə etmək icazəniz yoxdur");
        }

        exam.setTitle(request.getTitle());
        exam.setDescription(request.getDescription());
        exam.setSubject(request.getSubject());
        exam.setVisibility(request.getVisibility());
        exam.setExamType(request.getExamType());
        exam.setStatus(request.getStatus());
        exam.setDurationMinutes(request.getDurationMinutes());
        exam.getTags().clear();
        if (request.getTags() != null) {
            exam.getTags().addAll(request.getTags());
        }

        // Handle questions update (simplest way: clear and recreate, or more complex matching)
        // For now, let's clear and recreate to ensure consistency with the request payload
        exam.getQuestions().clear();
        if (request.getQuestions() != null) {
            for (QuestionRequest qReq : request.getQuestions()) {
                Question question = mapToQuestion(qReq, exam);
                exam.getQuestions().add(question);
            }
        }

        Exam savedExam = examRepository.save(exam);
        return mapToResponse(savedExam);
    }

    public ExamResponse getExamByShareLink(String shareLink) {
        Exam exam = examRepository.findByShareLink(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        return mapToResponse(exam);
    }

    @Transactional
    public void deleteExam(Long id, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        
        if (!exam.getTeacher().getId().equals(teacher.getId())) {
             throw new RuntimeException("Bu əməliyyat üçün icazəniz yoxdur");
        }
        
        examRepository.delete(exam);
    }

    private Question mapToQuestion(QuestionRequest req, Exam exam) {
        Question question = Question.builder()
                .content(req.getContent())
                .attachedImage(req.getAttachedImage())
                .questionType(req.getQuestionType())
                .points(req.getPoints())
                .orderIndex(req.getOrderIndex())
                .correctAnswer(req.getCorrectAnswer())
                .exam(exam)
                .build();

        if (req.getOptions() != null) {
            for (OptionRequest oReq : req.getOptions()) {
                Option option = Option.builder()
                        .content(oReq.getContent())
                        .isCorrect(oReq.getIsCorrect())
                        .orderIndex(oReq.getOrderIndex())
                        .attachedImage(oReq.getAttachedImage())
                        .question(question)
                        .build();
                question.getOptions().add(option);
            }
        }

        if (req.getMatchingPairs() != null) {
            for (MatchingPairRequest pReq : req.getMatchingPairs()) {
                MatchingPair pair = MatchingPair.builder()
                        .leftItem(pReq.getLeftItem())
                        .rightItem(pReq.getRightItem())
                        .orderIndex(pReq.getOrderIndex())
                        .question(question)
                        .build();
                question.getMatchingPairs().add(pair);
            }
        }

        return question;
    }

    private ExamResponse mapToResponse(Exam exam) {
        return ExamResponse.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .description(exam.getDescription())
                .subject(exam.getSubject())
                .visibility(exam.getVisibility())
                .examType(exam.getExamType())
                .status(exam.getStatus())
                .accessCode(exam.getAccessCode())
                .shareLink(exam.getShareLink())
                .durationMinutes(exam.getDurationMinutes())
                .teacherId(exam.getTeacher().getId())
                .teacherName(exam.getTeacher().getFullName())
                .templateId(exam.getTemplate() != null ? exam.getTemplate().getId() : null)
                .questions(exam.getQuestions().stream().map(this::mapToQuestionResponse).collect(Collectors.toList()))
                .createdAt(exam.getCreatedAt())
                .updatedAt(exam.getUpdatedAt())
                .tags(exam.getTags())
                .build();
    }

    private QuestionResponse mapToQuestionResponse(Question q) {
        return QuestionResponse.builder()
                .id(q.getId())
                .content(q.getContent())
                .attachedImage(q.getAttachedImage())
                .questionType(q.getQuestionType())
                .points(q.getPoints())
                .orderIndex(q.getOrderIndex())
                .correctAnswer(q.getCorrectAnswer())
                .options(q.getOptions().stream().map(this::mapToOptionResponse).collect(Collectors.toList()))
                .matchingPairs(q.getMatchingPairs().stream().map(this::mapToPairResponse).collect(Collectors.toList()))
                .build();
    }

    private OptionResponse mapToOptionResponse(Option o) {
        return OptionResponse.builder()
                .id(o.getId())
                .content(o.getContent())
                .isCorrect(o.getIsCorrect())
                .orderIndex(o.getOrderIndex())
                .attachedImage(o.getAttachedImage())
                .build();
    }

    private MatchingPairResponse mapToPairResponse(MatchingPair p) {
        return MatchingPairResponse.builder()
                .id(p.getId())
                .leftItem(p.getLeftItem())
                .rightItem(p.getRightItem())
                .orderIndex(p.getOrderIndex())
                .build();
    }
}
