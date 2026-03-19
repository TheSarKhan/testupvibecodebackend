package az.testup.dto.response;

import az.testup.enums.QuestionType;
import az.testup.enums.Difficulty;

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
        String topic,
        Difficulty difficulty,
        List<BankOptionResponse> options,
        List<BankMatchingPairResponse> matchingPairs,
        String createdAt
) {}
