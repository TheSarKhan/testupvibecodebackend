package az.testup.dto.response;

import az.testup.enums.QuestionType;

import java.util.List;

public record BankQuestionResponse(
        Long id,
        Long subjectId,
        String subjectName,
        String content,
        String attachedImage,
        QuestionType questionType,
        Double points,
        Integer orderIndex,
        String correctAnswer,
        List<BankOptionResponse> options,
        List<BankMatchingPairResponse> matchingPairs,
        String createdAt
) {}
