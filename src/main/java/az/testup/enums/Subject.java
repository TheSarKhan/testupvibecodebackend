package az.testup.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Subject {
    RIYAZIYYAT("Riyaziyyat"),
    FIZIKA("Fizika"),
    KIMYA("Kimya"),
    BIOLOGIYA("Biologiya"),
    AZERBAYCAN_DILI("Azərbaycan dili"),
    INGILIS_DILI("İngilis dili"),
    TARIX("Tarix"),
    COGRAFIYA("Coğrafiya"),
    INFORMATIKA("Informatika"),
    MANTIQ("Məntiq");

    private final String displayName;
}
