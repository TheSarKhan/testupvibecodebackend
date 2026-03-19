package az.testup.service;

import az.testup.dto.request.BankMatchingPairRequest;
import az.testup.dto.request.BankOptionRequest;
import az.testup.dto.request.BankQuestionRequest;
import az.testup.dto.request.BankSubjectRequest;
import az.testup.dto.response.*;
import az.testup.entity.*;
import az.testup.enums.Role;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.BankQuestionRepository;
import az.testup.repository.BankSubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BankService {

    private final BankSubjectRepository subjectRepository;
    private final BankQuestionRepository questionRepository;

    // ─── Subjects ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BankSubjectResponse> getSubjectsForUser(User user) {
        List<BankSubject> own = subjectRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId());

        // Global (admin-created) subjects that the user doesn't already own
        List<BankSubject> global = subjectRepository.findByIsGlobalTrueOrderByCreatedAtDesc()
                .stream()
                .filter(g -> !g.getOwner().getId().equals(user.getId()))
                .toList();

        List<BankSubject> all = new ArrayList<>(own);
        all.addAll(global);
        return all.stream().map(this::mapSubject).collect(Collectors.toList());
    }

    @Transactional
    public BankSubjectResponse createSubject(User user, BankSubjectRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BadRequestException("Fənn adı boş ola bilməz");
        }
        BankSubject subject = BankSubject.builder()
                .name(req.getName().trim())
                .owner(user)
                .isGlobal(user.getRole() == Role.ADMIN)
                .build();
        return mapSubject(subjectRepository.save(subject));
    }

    @Transactional
    public BankSubjectResponse updateSubject(Long id, User user, BankSubjectRequest req) {
        BankSubject subject = findSubjectOwned(id, user);
        if (req.getName() != null && !req.getName().isBlank()) {
            subject.setName(req.getName().trim());
        }
        return mapSubject(subjectRepository.save(subject));
    }

    @Transactional
    public void deleteSubject(Long id, User user) {
        BankSubject subject = findSubjectOwned(id, user);
        subjectRepository.delete(subject);
    }

    // ─── Questions ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BankQuestionResponse> getQuestions(Long subjectId, User user) {
        BankSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        // Access: own subject OR global
        if (!subject.getOwner().getId().equals(user.getId()) && !subject.getIsGlobal()) {
            throw new BadRequestException("Bu fənnə giriş icazəniz yoxdur");
        }
        return questionRepository.findBySubjectIdOrderByOrderIndexAscCreatedAtAsc(subjectId)
                .stream().map(this::mapQuestion).collect(Collectors.toList());
    }

    @Transactional
    public BankQuestionResponse createQuestion(User user, BankQuestionRequest req) {
        BankSubject subject = subjectRepository.findById(req.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        checkWriteAccess(subject, user);

        int nextOrder = (int) questionRepository.countBySubjectId(subject.getId());
        BankQuestion q = BankQuestion.builder()
                .content(req.getContent())
                .attachedImage(req.getAttachedImage())
                .questionType(req.getQuestionType())
                .points(req.getPoints() != null ? req.getPoints() : 1.0)
                .orderIndex(req.getOrderIndex() != null ? req.getOrderIndex() : nextOrder)
                .correctAnswer(req.getCorrectAnswer())
                .topic(req.getTopic())
                .difficulty(req.getDifficulty())
                .subject(subject)
                .build();

        applyOptions(q, req.getOptions());
        applyMatchingPairs(q, req.getMatchingPairs());
        return mapQuestion(questionRepository.save(q));
    }

    @Transactional
    public BankQuestionResponse updateQuestion(Long id, User user, BankQuestionRequest req) {
        BankQuestion q = questionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı"));
        checkWriteAccess(q.getSubject(), user);

        q.setContent(req.getContent());
        q.setAttachedImage(req.getAttachedImage());
        q.setQuestionType(req.getQuestionType());
        q.setPoints(req.getPoints() != null ? req.getPoints() : 1.0);
        if (req.getOrderIndex() != null) q.setOrderIndex(req.getOrderIndex());
        q.setCorrectAnswer(req.getCorrectAnswer());
        q.setTopic(req.getTopic());
        q.setDifficulty(req.getDifficulty());

        q.getOptions().clear();
        applyOptions(q, req.getOptions());
        q.getMatchingPairs().clear();
        applyMatchingPairs(q, req.getMatchingPairs());
        return mapQuestion(questionRepository.save(q));
    }

    @Transactional
    public void deleteQuestion(Long id, User user) {
        BankQuestion q = questionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı"));
        checkWriteAccess(q.getSubject(), user);
        questionRepository.delete(q);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private BankSubject findSubjectOwned(Long id, User user) {
        BankSubject s = subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (!s.getOwner().getId().equals(user.getId()) && user.getRole() != Role.ADMIN) {
            throw new BadRequestException("Bu əməliyyat üçün icazəniz yoxdur");
        }
        return s;
    }

    private void checkWriteAccess(BankSubject subject, User user) {
        if (!subject.getOwner().getId().equals(user.getId()) && user.getRole() != Role.ADMIN) {
            throw new BadRequestException("Bu əməliyyat üçün icazəniz yoxdur");
        }
    }

    private void applyOptions(BankQuestion q, List<BankOptionRequest> opts) {
        if (opts == null) return;
        for (BankOptionRequest o : opts) {
            q.getOptions().add(BankOption.builder()
                    .content(o.getContent() != null ? o.getContent() : "")
                    .isCorrect(o.getIsCorrect() != null ? o.getIsCorrect() : false)
                    .orderIndex(o.getOrderIndex())
                    .attachedImage(o.getAttachedImage())
                    .question(q)
                    .build());
        }
    }

    private void applyMatchingPairs(BankQuestion q, List<BankMatchingPairRequest> pairs) {
        if (pairs == null) return;
        for (BankMatchingPairRequest mp : pairs) {
            q.getMatchingPairs().add(BankMatchingPair.builder()
                    .leftItem(mp.getLeftItem())
                    .rightItem(mp.getRightItem())
                    .orderIndex(mp.getOrderIndex())
                    .question(q)
                    .build());
        }
    }

    private BankSubjectResponse mapSubject(BankSubject s) {
        int count = (int) questionRepository.countBySubjectId(s.getId());
        return new BankSubjectResponse(
                s.getId(), s.getName(),
                s.getOwner().getId(), s.getOwner().getFullName(),
                s.getIsGlobal(), count,
                s.getCreatedAt() != null ? s.getCreatedAt().toString() : null
        );
    }

    public BankQuestionResponse mapQuestion(BankQuestion q) {
        List<BankOptionResponse> opts = q.getOptions().stream()
                .map(o -> new BankOptionResponse(o.getId(), o.getContent(), o.getIsCorrect(), o.getOrderIndex(), o.getAttachedImage()))
                .collect(Collectors.toList());
        List<BankMatchingPairResponse> pairs = q.getMatchingPairs().stream()
                .map(mp -> new BankMatchingPairResponse(mp.getId(), mp.getLeftItem(), mp.getRightItem(), mp.getOrderIndex()))
                .collect(Collectors.toList());
        return new BankQuestionResponse(
                q.getId(), q.getSubject().getId(), q.getSubject().getName(),
                q.getContent(), q.getAttachedImage(), q.getQuestionType(),
                q.getPoints(), q.getOrderIndex(), q.getCorrectAnswer(),
                q.getTopic(), q.getDifficulty(),
                opts, pairs,
                q.getCreatedAt() != null ? q.getCreatedAt().toString() : null
        );
    }
}
