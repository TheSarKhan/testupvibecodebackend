package az.testup.service;

import az.testup.entity.SubscriptionPlan;
import az.testup.entity.SubscriptionUsage;
import az.testup.entity.UserSubscription;
import az.testup.exception.SubscriptionLimitExceededException;
import az.testup.repository.ExamRepository;
import az.testup.repository.SubscriptionUsageRepository;
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
                            .build();
                    return subscriptionUsageRepository.save(newUsage);
                });
    }

    public void validateMonthlyExamCreation(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionPlan plan = subscription.getPlan();
        SubscriptionUsage usage = getCurrentUsage(subscription);

        if (usage.getUsedMonthlyExams() >= plan.getMonthlyExamLimit() && plan.getMonthlyExamLimit() != -1) {
            throw new SubscriptionLimitExceededException("Aylıq imtahan yaratma limitinizi (" + plan.getMonthlyExamLimit() + ") aşmısınız.");
        }
    }

    public void validateTotalSavedExams(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionPlan plan = subscription.getPlan();
        
        if (plan.getMaxSavedExamsLimit() == -1) return; // Unlimited
        
        long totalExams = examRepository.countByTeacherId(userId);
        if (totalExams >= plan.getMaxSavedExamsLimit()) {
            throw new SubscriptionLimitExceededException("Maksimum yadda saxlanıla bilən imtahan limitini (" + plan.getMaxSavedExamsLimit() + ") aşmısınız. Yeni imtahan yaratmaq üçün köhnə imtahanlardan bəzilərini silməlisiniz.");
        }
    }

    public void recordMonthlyExamCreated(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionUsage usage = getCurrentUsage(subscription);
        usage.setUsedMonthlyExams(usage.getUsedMonthlyExams() + 1);
        subscriptionUsageRepository.save(usage);
    }

    public void validateMaxQuestionsPerExam(Long userId, int questionCountToBeAdded) {
        UserSubscription subscription = getActiveSubscription(userId);
        SubscriptionPlan plan = subscription.getPlan();
        if (questionCountToBeAdded > plan.getMaxQuestionsPerExam()) {
             throw new SubscriptionLimitExceededException("Bu plan üzrə bir imtahana maksimum " + plan.getMaxQuestionsPerExam() + " sual əlavə edilə bilər.");
        }
    }

    public void validateStudentResultAnalysis(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isStudentResultAnalysis()) {
            throw new SubscriptionLimitExceededException("Şagird nəticə analizi xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateExamEditing(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isExamEditing()) {
            throw new SubscriptionLimitExceededException("İmtahan redaktəsi xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateAddImage(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isAddImage()) {
            throw new SubscriptionLimitExceededException("Şəkil əlavə etmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateAddPassageQuestion(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isAddPassageQuestion()) {
            throw new SubscriptionLimitExceededException("Mətn/Dinləmə sualı əlavə etmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateDownloadPastExams(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isDownloadPastExams()) {
            throw new SubscriptionLimitExceededException("Keçmiş imtahanları yükləmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateDownloadAsPdf(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isDownloadAsPdf()) {
            throw new SubscriptionLimitExceededException("İmtahanı PDF kimi yükləmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateMultipleSubjects(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isMultipleSubjects()) {
            throw new SubscriptionLimitExceededException("Bir imtahanda çox fənn xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateUseTemplateExams(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isUseTemplateExams()) {
            throw new SubscriptionLimitExceededException("Şablon imtahanlardan istifadə xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateManualChecking(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isManualChecking()) {
            throw new SubscriptionLimitExceededException("Manual yoxlama xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateSelectExamDuration(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isSelectExamDuration()) {
            throw new SubscriptionLimitExceededException("İmtahan müddətini seçmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateUseQuestionBank(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isUseQuestionBank()) {
            throw new SubscriptionLimitExceededException("Sual bazasından istifadə xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateCreateQuestionBank(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isCreateQuestionBank()) {
            throw new SubscriptionLimitExceededException("Sual bazası hazırlamaq xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }

    public void validateImportQuestionsFromPdf(Long userId) {
        UserSubscription subscription = getActiveSubscription(userId);
        if (!subscription.getPlan().isImportQuestionsFromPdf()) {
            throw new SubscriptionLimitExceededException("PDF-dən çoxlu sual əlavə etmək xüsusiyyəti cari planınızda mövcud deyil.");
        }
    }
}
