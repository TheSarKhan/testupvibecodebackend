package az.testup.service;

import az.testup.entity.SubscriptionPlan;
import az.testup.entity.SubscriptionUsage;
import az.testup.entity.UserSubscription;
import az.testup.enums.Role;
import az.testup.exception.SubscriptionLimitExceededException;
import az.testup.repository.ExamRepository;
import az.testup.repository.SubscriptionUsageRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class SubscriptionValidatorService {

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionUsageRepository subscriptionUsageRepository;
    private final ExamRepository examRepository;
    private final UserRepository userRepository;

    private boolean isAdmin(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getRole() == Role.ADMIN)
                .orElse(false);
    }

    public UserSubscription getActiveSubscription(Long userId) {
        return userSubscriptionRepository.findActiveSubscriptionByUserIdAndDate(userId, LocalDateTime.now())
                .orElseThrow(() -> new SubscriptionLimitExceededException("Aktiv abunəlik planı tapılmadı. Zəhmət olmasa bir plan əldə edin."));
    }

    private SubscriptionUsage getCurrentUsage(UserSubscription userSubscription) {
        String currentMonthYear = YearMonth.now().toString(); // e.g. "2026-03"
        return subscriptionUsageRepository.findByUserSubscriptionIdAndMonthYear(userSubscription.getId(), currentMonthYear)
                .orElseGet(() -> {
                    SubscriptionUsage newUsage = SubscriptionUsage.builder()
                            .userSubscription(userSubscription)
                            .monthYear(currentMonthYear)
                            .usedMonthlyExams(0)
                            .usedSavedExams(0)
                            .usedAiQuestions(0)
                            .build();
                    return subscriptionUsageRepository.save(newUsage);
                });
    }

    public void validateMonthlyExamCreation(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionPlan plan = subscription.getPlan();
        SubscriptionUsage usage = getCurrentUsage(subscription);

        if (usage.getUsedMonthlyExams() >= plan.getMonthlyExamLimit() && plan.getMonthlyExamLimit() != -1) {
            throw new SubscriptionLimitExceededException("Aylıq imtahan yaratma limitinizi (" + plan.getMonthlyExamLimit() + ") aşmısınız.");
        }
    }

    public void validateTotalSavedExams(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionPlan plan = subscription.getPlan();
        
        if (plan.getMaxSavedExamsLimit() == -1) return; // Unlimited
        
        long totalExams = examRepository.countByTeacherId(userId);
        if (totalExams >= plan.getMaxSavedExamsLimit()) {
            throw new SubscriptionLimitExceededException("Maksimum yadda saxlanıla bilən imtahan limitini (" + plan.getMaxSavedExamsLimit() + ") aşmısınız. Yeni imtahan yaratmaq üçün köhnə imtahanlardan bəzilərini silməlisiniz.");
        }
    }

    public void recordMonthlyExamCreated(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionUsage usage = getCurrentUsage(subscription);
        usage.setUsedMonthlyExams(usage.getUsedMonthlyExams() + 1);
        subscriptionUsageRepository.save(usage);
    }

    public void recordMonthlyExamDeleted(Long userId) {
        if (isAdmin(userId)) return;
        userSubscriptionRepository.findActiveSubscriptionByUserIdAndDate(userId, LocalDateTime.now()).ifPresent(subscription -> {
            String currentMonthYear = YearMonth.now().toString();
            subscriptionUsageRepository.findByUserSubscriptionIdAndMonthYear(subscription.getId(), currentMonthYear).ifPresent(usage -> {
                usage.setUsedMonthlyExams(Math.max(0, usage.getUsedMonthlyExams() - 1));
                subscriptionUsageRepository.save(usage);
            });
        });
    }

    public void validateMaxQuestionsPerExam(Long userId, int questionCountToBeAdded) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionPlan plan = subscription.getPlan();
        if (plan.getMaxQuestionsPerExam() == -1) return; // Unlimited
        if (questionCountToBeAdded > plan.getMaxQuestionsPerExam()) {
             throw new SubscriptionLimitExceededException("Bu plan üzrə bir imtahana maksimum " + plan.getMaxQuestionsPerExam() + " sual əlavə edilə bilər.");
        }
    }

    public void validateStudentResultAnalysis(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isStudentResultAnalysis()) {
            throw new SubscriptionLimitExceededException("Şagird nəticə analizi xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateExamEditing(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isExamEditing()) {
            throw new SubscriptionLimitExceededException("İmtahan redaktəsi xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateAddImage(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isAddImage()) {
            throw new SubscriptionLimitExceededException("Şəkil əlavə etmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateAddPassageQuestion(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isAddPassageQuestion()) {
            throw new SubscriptionLimitExceededException("Mətn/Dinləmə sualı əlavə etmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateDownloadPastExams(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isDownloadPastExams()) {
            throw new SubscriptionLimitExceededException("Keçmiş imtahanları yükləmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateDownloadAsPdf(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isDownloadAsPdf()) {
            throw new SubscriptionLimitExceededException("İmtahanı PDF kimi yükləmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateMultipleSubjects(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isMultipleSubjects()) {
            throw new SubscriptionLimitExceededException("Bir imtahanda çox fənn xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateUseTemplateExams(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isUseTemplateExams()) {
            throw new SubscriptionLimitExceededException("Şablon imtahanlardan istifadə xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateManualChecking(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isManualChecking()) {
            throw new SubscriptionLimitExceededException("Manual yoxlama xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateSelectExamDuration(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isSelectExamDuration()) {
            throw new SubscriptionLimitExceededException("İmtahan müddətini seçmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateUseQuestionBank(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isUseQuestionBank()) {
            throw new SubscriptionLimitExceededException("Sual bazasından istifadə xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateCreateQuestionBank(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isCreateQuestionBank()) {
            throw new SubscriptionLimitExceededException("Sual bazası hazırlamaq xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateImportQuestionsFromPdf(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isImportQuestionsFromPdf()) {
            throw new SubscriptionLimitExceededException("PDF-dən çoxlu sual əlavə etmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateAiExamGeneration(Long userId) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isUseAiExamGeneration()) {
            throw new SubscriptionLimitExceededException("AI ilə imtahan yaratmaq xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    /** Checks monthly AI question limit (does NOT record — call recordAiQuestions after success). */
    public void validateAiQuestions(Long userId, int count) {
        if (isAdmin(userId)) return;
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionPlan plan = subscription.getPlan();
        Integer limit = plan.getMonthlyAiQuestionLimit();
        if (limit == null || limit == 0) {
            throw new SubscriptionLimitExceededException("AI ilə sual yaratmaq xüsusiyyəti cari planınızda mövcud deyil.");
        }
        if (limit != -1) {
            SubscriptionUsage usage = getCurrentUsage(subscription);
            if (usage.getUsedAiQuestions() + count > limit) {
                int remaining = Math.max(0, limit - usage.getUsedAiQuestions());
                throw new SubscriptionLimitExceededException(
                        "Aylıq AI sual yaratma limitinizi aşırsınız. Limit: " + limit + ", qalan: " + remaining + ".");
            }
        }
    }

    /** Records AI question usage after successful generation. */
    public void recordAiQuestions(Long userId, int count) {
        if (isAdmin(userId)) return;
        userSubscriptionRepository.findActiveSubscriptionByUserIdAndDate(userId, LocalDateTime.now()).ifPresent(subscription -> {
            SubscriptionPlan plan = subscription.getPlan();
            Integer limit = plan.getMonthlyAiQuestionLimit();
            if (limit == null || limit == 0 || limit == -1) return; // unlimited or disabled — nothing to record
            SubscriptionUsage usage = getCurrentUsage(subscription);
            usage.setUsedAiQuestions(usage.getUsedAiQuestions() + count);
            subscriptionUsageRepository.save(usage);
        });
    }
}
