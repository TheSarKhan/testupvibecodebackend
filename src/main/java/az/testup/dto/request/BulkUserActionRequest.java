package az.testup.dto.request;

import java.util.List;

public record BulkUserActionRequest(
        List<Long> userIds,
        Long planId,
        Integer durationMonths,
        Boolean enabled
) {}
