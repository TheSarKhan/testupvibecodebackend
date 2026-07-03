package az.testup.util;

import az.testup.entity.Exam;
import az.testup.entity.Passage;
import az.testup.entity.Question;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regroups a multi-subject exam so every subject sits in one contiguous
 * orderIndex block, ordered by the subject's position in exam.subjects.
 *
 * Both renderers (student session view and PdfService) build ONE display list —
 * standalone questions and passages merged by orderIndex, passage children
 * riding with their passage — and start a new subject section at every
 * subjectGroup change along that list. Regrouping therefore has to renumber
 * that same merged space: questions AND passages together. Resorting questions
 * alone leaves passages at stale positions inside other subjects' blocks,
 * splitting their sections apart (the "fənlər qarışıq" regression of the
 * BUG-253 fix).
 *
 * Convention: subjectGroup == null/blank means "main section" = subjects[0]
 * (the editor stores `isMain ? null : section`; the session view and
 * PdfService resolve labels the same way), so null ranks FIRST, not last.
 * A passage without its own subjectGroup inherits its first child's, mirroring
 * PdfService — a passage can never split its own subject section.
 *
 * Stable sort throughout: each subject's internal order is preserved, and a
 * fresh max-orderIndex question still falls to its own subject's tail.
 */
public final class SubjectRegrouper {

    private SubjectRegrouper() {}

    public static void regroup(Exam exam) {
        List<String> subjectOrder = exam.getSubjects() != null
                ? exam.getSubjects() : List.of();
        if (subjectOrder.size() <= 1) return;

        List<Passage> passages = exam.getPassages() != null
                ? exam.getPassages() : List.of();

        // Split questions into standalone vs. per-passage children. Matching is
        // by reference first — freshly created passages have no id before the
        // flush. A question whose passage is missing from exam.passages counts
        // as standalone, same as the renderers treat orphaned links (BUG-248).
        Comparator<Question> byOrder = Comparator.comparing(
                Question::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder()));
        Map<Passage, List<Question>> childrenOf = new IdentityHashMap<>();
        List<Question> standalone = new ArrayList<>();
        for (Question q : exam.getQuestions()) {
            Passage owner = findOwner(q, passages);
            if (owner == null) standalone.add(q);
            else childrenOf.computeIfAbsent(owner, k -> new ArrayList<>()).add(q);
        }
        standalone.sort(byOrder);
        childrenOf.values().forEach(kids -> kids.sort(byOrder));

        // Merge exactly the way the renderers do, stable-sort by subject rank,
        // then renumber the whole space sequentially.
        List<Object> items = new ArrayList<>(standalone.size() + passages.size());
        items.addAll(standalone);
        items.addAll(passages);
        items.sort(Comparator
                .comparingInt((Object it) -> rank(subjectOf(it, childrenOf), subjectOrder))
                .thenComparingInt(SubjectRegrouper::orderOf));

        int next = 0;
        for (Object it : items) {
            if (it instanceof Question q) {
                apply(q, next++);
            } else {
                Passage p = (Passage) it;
                if (p.getOrderIndex() == null || p.getOrderIndex() != next) p.setOrderIndex(next);
                next++;
                for (Question child : childrenOf.getOrDefault(p, List.of())) {
                    apply(child, next++);
                }
            }
        }
    }

    private static Passage findOwner(Question q, List<Passage> passages) {
        if (q.getPassage() == null) return null;
        for (Passage p : passages) {
            if (p == q.getPassage()
                    || (p.getId() != null && p.getId().equals(q.getPassage().getId()))) {
                return p;
            }
        }
        return null;
    }

    private static String subjectOf(Object item, Map<Passage, List<Question>> childrenOf) {
        if (item instanceof Question q) return q.getSubjectGroup();
        Passage p = (Passage) item;
        String s = p.getSubjectGroup();
        if (s == null || s.isBlank()) {
            List<Question> kids = childrenOf.get(p);
            if (kids != null && !kids.isEmpty()) s = kids.get(0).getSubjectGroup();
        }
        return s;
    }

    private static int rank(String subjectGroup, List<String> subjectOrder) {
        if (subjectGroup == null || subjectGroup.isBlank()) return 0;
        int idx = subjectOrder.indexOf(subjectGroup);
        return idx < 0 ? subjectOrder.size() : idx;
    }

    private static int orderOf(Object item) {
        Integer oi = (item instanceof Question q)
                ? q.getOrderIndex() : ((Passage) item).getOrderIndex();
        return oi == null ? Integer.MAX_VALUE : oi;
    }

    private static void apply(Question q, int idx) {
        if (q.getOrderIndex() == null || q.getOrderIndex() != idx) q.setOrderIndex(idx);
    }
}
