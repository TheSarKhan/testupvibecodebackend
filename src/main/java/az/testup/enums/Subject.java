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
    MANTIQ("Məntiq"),
    EDEBIYYAT("Ədəbiyyat"),
    XARICI_DILL("Xarici dil"),
    RUS_DILI("Rus dili"),
    ALMAN_DILI("Alman dili"),
    FRANSIZ_DILI("Fransız dili"),
    HAYAT_BILGISI("Həyat bilgisi"),
    INCASANAT("İncəsənət"),
    MUSIQI("Musiqi"),
    FIZIKI_TERBIYE("Fiziki tərbiyə"),
    TEXNOLOGIYA("Texnologiya");

    private final String displayName;
}
