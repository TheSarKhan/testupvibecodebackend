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
import java.util.Objects;
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

    /**
     * Returns all ACTIVE exams created by ADMIN users — visible to students on the main exam listing.
     */
    public List<ExamResponse> getPublicExams() {
        return examRepository.findAll().stream()
                .filter(e -> e.getStatus() == ExamStatus.ACTIVE
                        && e.getTeacher().getRole().name().equals("ADMIN"))
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

        // Handle questions update using ID matching to avoid DataIntegrityViolation
        if (request.getQuestions() != null) {
            List<Long> requestQuestionIds = request.getQuestions().stream()
                    .map(QuestionRequest::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            // Remove questions that are no longer in the request
            exam.getQuestions().removeIf(q -> !requestQuestionIds.contains(q.getId()));

            for (QuestionRequest qReq : request.getQuestions()) {
                if (qReq.getId() != null) {
                    Question existing = exam.getQuestions().stream()
                            .filter(q -> q.getId().equals(qReq.getId()))
                            .findFirst()
                            .orElse(null);
                    if (existing != null) {
                        updateQuestionFromRequest(existing, qReq);
                    } else {
                        exam.getQuestions().add(mapToQuestion(qReq, exam));
                    }
                } else {
                    exam.getQuestions().add(mapToQuestion(qReq, exam));
                }
            }
        } else {
            exam.getQuestions().clear();
        }

        Exam savedExam = examRepository.save(exam);
        return mapToResponse(savedExam);
    }

    private void updateQuestionFromRequest(Question question, QuestionRequest req) {
        question.setContent(req.getContent());
        question.setAttachedImage(req.getAttachedImage());
        question.setQuestionType(req.getQuestionType());
        question.setPoints(req.getPoints());
        question.setOrderIndex(req.getOrderIndex());
        question.setCorrectAnswer(req.getCorrectAnswer());

        // Update options
        if (req.getOptions() != null) {
            List<Long> reqOptionIds = req.getOptions().stream()
                    .map(OptionRequest::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            question.getOptions().removeIf(o -> !reqOptionIds.contains(o.getId()));

            for (OptionRequest oReq : req.getOptions()) {
                if (oReq.getId() != null) {
                    Option existingOpt = question.getOptions().stream()
                            .filter(o -> o.getId().equals(oReq.getId()))
                            .findFirst().orElse(null);
                    if (existingOpt != null) {
                        existingOpt.setContent(oReq.getContent());
                        existingOpt.setIsCorrect(oReq.getIsCorrect());
                        existingOpt.setOrderIndex(oReq.getOrderIndex());
                        existingOpt.setAttachedImage(oReq.getAttachedImage());
                    } else {
                        question.getOptions().add(mapToOption(oReq, question));
                    }
                } else {
                    question.getOptions().add(mapToOption(oReq, question));
                }
            }
        } else {
            question.getOptions().clear();
        }

        // Update matching pairs
        if (req.getMatchingPairs() != null) {
            List<Long> reqPairIds = req.getMatchingPairs().stream()
                    .map(MatchingPairRequest::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
            question.getMatchingPairs().removeIf(p -> !reqPairIds.contains(p.getId()));

            for (MatchingPairRequest pReq : req.getMatchingPairs()) {
                if (pReq.getId() != null) {
                    MatchingPair existingPair = question.getMatchingPairs().stream()
                            .filter(p -> p.getId().equals(pReq.getId()))
                            .findFirst().orElse(null);
                    if (existingPair != null) {
                        existingPair.setLeftItem(pReq.getLeftItem());
                        existingPair.setRightItem(pReq.getRightItem());
                        existingPair.setOrderIndex(pReq.getOrderIndex());
                    } else {
                        question.getMatchingPairs().add(mapToPair(pReq, question));
                    }
                } else {
                    question.getMatchingPairs().add(mapToPair(pReq, question));
                }
            }
        } else {
            question.getMatchingPairs().clear();
        }
    }

    private Option mapToOption(OptionRequest oReq, Question question) {
        return Option.builder()
                .content(oReq.getContent())
                .isCorrect(oReq.getIsCorrect())
                .orderIndex(oReq.getOrderIndex())
                .attachedImage(oReq.getAttachedImage())
                .question(question)
                .build();
    }

    private MatchingPair mapToPair(MatchingPairRequest pReq, Question question) {
        return MatchingPair.builder()
                .leftItem(pReq.getLeftItem())
                .rightItem(pReq.getRightItem())
                .orderIndex(pReq.getOrderIndex())
                .question(question)
                .build();
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
                question.getOptions().add(mapToOption(oReq, question));
            }
        }

        if (req.getMatchingPairs() != null) {
            for (MatchingPairRequest pReq : req.getMatchingPairs()) {
                question.getMatchingPairs().add(mapToPair(pReq, question));
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

