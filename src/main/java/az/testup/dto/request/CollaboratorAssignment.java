package az.testup.dto.request;

import java.util.List;

public record CollaboratorAssignment(
        String teacherEmail,
        List<String> subjects,          // free mode
        List<Long> templateSectionIds   // template mode
) {}
