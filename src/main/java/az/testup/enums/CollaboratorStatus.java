package az.testup.enums;

public enum CollaboratorStatus {
    ASSIGNED,    // Admin təyin edib, müəllim hələ sual göndərməyib
    SUBMITTED,   // Müəllim suallarını admin-ə göndərib, təsdiq gözlənilir
    APPROVED,    // Admin təsdiqlədi, suallar əsl imtahana əlavə edildi
    REJECTED     // Admin rədd etdi, müəllimə geri qaytarıldı
}
