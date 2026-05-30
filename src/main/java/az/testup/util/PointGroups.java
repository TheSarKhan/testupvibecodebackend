package az.testup.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for a {@code TemplateSection.pointGroups} JSON of the form
 * {@code [{"from":1,"to":15,"points":1.0},{"from":16,"to":20,"points":1.5}]}.
 *
 * Shared by the collaborative skeleton builder and the standard
 * template-to-exam flow so both assign per-position points identically
 * (no duplicated parsing logic).
 */
public final class PointGroups {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PointGroups() {}

    /**
     * Parse the pointGroups JSON into a list of {@code [from, to, points]}
     * ranges. Tolerates null/blank/malformed input — returns an empty list,
     * and callers then fall back to 1.0 per question.
     */
    public static List<double[]> parse(String json) {
        List<double[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode g : arr) {
                if (g.has("from") && g.has("to") && g.has("points")) {
                    out.add(new double[] {
                            g.get("from").asDouble(),
                            g.get("to").asDouble(),
                            g.get("points").asDouble(1.0)
                    });
                }
            }
        } catch (Exception ignored) { /* malformed → behave as if no groups defined */ }
        return out;
    }

    /** 1-based question position within a section → its points (1.0 if no range matches). */
    public static double pointsFor(List<double[]> ranges, int positionOneBased) {
        for (double[] r : ranges) {
            if (positionOneBased >= r[0] && positionOneBased <= r[1]) return r[2];
        }
        return 1.0;
    }
}
