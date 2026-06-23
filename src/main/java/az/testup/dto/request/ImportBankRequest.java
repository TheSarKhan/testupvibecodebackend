package az.testup.dto.request;

import java.util.List;

/**
 * Admin request to import a teacher's bank questions into the site (global) bank.
 *
 * @param sourceSubjectId   the teacher's subject to copy questions from (required)
 * @param targetSubjectId   an existing global/admin subject to import into; null → create a new one
 * @param targetSubjectName name for the new global subject when targetSubjectId is null; falls back to the source subject's name
 * @param questionIds       specific questions to import; null/empty → import all questions of the source subject
 */
public record ImportBankRequest(
        Long sourceSubjectId,
        Long targetSubjectId,
        String targetSubjectName,
        List<Long> questionIds
) {}
