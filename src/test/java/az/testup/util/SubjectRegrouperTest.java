package az.testup.util;

import az.testup.entity.Exam;
import az.testup.entity.Passage;
import az.testup.entity.Question;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SubjectRegrouperTest {

    private Question q(String content, String subjectGroup, Integer orderIndex) {
        return Question.builder()
                .content(content)
                .subjectGroup(subjectGroup)
                .orderIndex(orderIndex)
                .build();
    }

    private Exam exam(List<String> subjects) {
        return Exam.builder().subjects(new ArrayList<>(subjects)).build();
    }

    /**
     * Renders the exam the way the session view and PdfService do: standalone
     * questions and passages merged by orderIndex, passage children riding
     * with their passage. Returns the subjectGroup sequence of that walk so
     * tests can assert sections are contiguous.
     */
    private List<String> renderedSubjects(Exam exam) {
        List<Object> items = new ArrayList<>();
        items.addAll(exam.getQuestions().stream().filter(x -> x.getPassage() == null).toList());
        items.addAll(exam.getPassages());
        items.sort(Comparator.comparingInt(it -> it instanceof Question qu
                ? qu.getOrderIndex() : ((Passage) it).getOrderIndex()));
        List<String> subjects = new ArrayList<>();
        for (Object it : items) {
            if (it instanceof Question qu) {
                subjects.add(qu.getSubjectGroup());
            } else {
                Passage p = (Passage) it;
                exam.getQuestions().stream()
                        .filter(c -> c.getPassage() == p)
                        .sorted(Comparator.comparing(Question::getOrderIndex))
                        .forEach(c -> subjects.add(c.getSubjectGroup()));
            }
        }
        return subjects;
    }

    @Test
    void nullSubjectGroupRanksAsMainSectionNotLast() {
        // Editor convention: main-subject questions carry subjectGroup == null.
        // They must stay FIRST (subjects[0]), not get pushed to the exam's end.
        Exam exam = exam(List.of("Riyaziyyat", "İngilis dili"));
        exam.getQuestions().add(q("eng1", "İngilis dili", 2));
        exam.getQuestions().add(q("main1", null, 0));
        exam.getQuestions().add(q("main2", null, 1));

        SubjectRegrouper.regroup(exam);

        List<Question> byIndex = exam.getQuestions().stream()
                .sorted(Comparator.comparing(Question::getOrderIndex)).toList();
        assertEquals("main1", byIndex.get(0).getContent());
        assertEquals("main2", byIndex.get(1).getContent());
        assertEquals("eng1", byIndex.get(2).getContent());
    }

    @Test
    void explicitMainSubjectAndNullMergeIntoOneBlock() {
        // Template mode stores the main subject explicitly, bank additions store
        // null — both mean subjects[0] and must land in a single contiguous block.
        Exam exam = exam(List.of("Riyaziyyat", "Tarix"));
        exam.getQuestions().add(q("m-explicit", "Riyaziyyat", 0));
        exam.getQuestions().add(q("t1", "Tarix", 1));
        exam.getQuestions().add(q("m-null", null, 2));

        SubjectRegrouper.regroup(exam);

        List<Question> byIndex = exam.getQuestions().stream()
                .sorted(Comparator.comparing(Question::getOrderIndex)).toList();
        assertEquals("m-explicit", byIndex.get(0).getContent());
        assertEquals("m-null", byIndex.get(1).getContent());
        assertEquals("t1", byIndex.get(2).getContent());
    }

    @Test
    void passagesAreRenumberedIntoTheSameSpaceAsQuestions() {
        // A passage keeping a stale orderIndex must not land inside another
        // subject's block when the renderers merge questions and passages.
        Exam exam = exam(List.of("Riyaziyyat", "İngilis dili"));
        Passage engPassage = Passage.builder()
                .title("Reading").subjectGroup("İngilis dili").orderIndex(1).build();
        exam.getPassages().add(engPassage);
        Question child1 = q("c1", "İngilis dili", 2);
        child1.setPassage(engPassage);
        Question child2 = q("c2", "İngilis dili", 3);
        child2.setPassage(engPassage);
        exam.getQuestions().add(child1);
        exam.getQuestions().add(child2);
        exam.getQuestions().add(q("m1", null, 0));
        exam.getQuestions().add(q("m2", null, 4));

        SubjectRegrouper.regroup(exam);

        assertEquals(java.util.Arrays.asList(null, null, "İngilis dili", "İngilis dili"),
                renderedSubjects(exam));
        // Children follow their passage immediately in the shared space.
        assertEquals(engPassage.getOrderIndex() + 1, child1.getOrderIndex());
        assertEquals(engPassage.getOrderIndex() + 2, child2.getOrderIndex());
    }

    @Test
    void blankSubjectPassageInheritsFirstChildsSubject() {
        Exam exam = exam(List.of("Riyaziyyat", "İngilis dili"));
        Passage p = Passage.builder().title("Listening").orderIndex(0).build();
        exam.getPassages().add(p);
        Question child = q("c1", "İngilis dili", 1);
        child.setPassage(p);
        exam.getQuestions().add(child);
        exam.getQuestions().add(q("m1", null, 2));

        SubjectRegrouper.regroup(exam);

        // Math (main) first, then the passage block — despite its lower old index.
        assertEquals(List.of("İngilis dili"),
                renderedSubjects(exam).stream().filter(s -> s != null).distinct().toList());
        assertEquals(0, exam.getQuestions().stream()
                .filter(qu -> qu.getContent().equals("m1")).findFirst().get().getOrderIndex());
        assertEquals(1, p.getOrderIndex());
        assertEquals(2, child.getOrderIndex());
    }

    @Test
    void intraSubjectOrderIsPreservedAndNewQuestionFallsToSubjectTail() {
        Exam exam = exam(List.of("Az dili", "Tarix"));
        exam.getQuestions().add(q("az1", null, 0));
        exam.getQuestions().add(q("t1", "Tarix", 1));
        exam.getQuestions().add(q("t2", "Tarix", 2));
        exam.getQuestions().add(q("az-new", null, 3)); // added at exam end by the editor

        SubjectRegrouper.regroup(exam);

        List<Question> byIndex = exam.getQuestions().stream()
                .sorted(Comparator.comparing(Question::getOrderIndex)).toList();
        assertEquals(List.of("az1", "az-new", "t1", "t2"),
                byIndex.stream().map(Question::getContent).toList());
    }

    @Test
    void singleSubjectExamIsLeftUntouched() {
        Exam exam = exam(List.of("Riyaziyyat"));
        exam.getQuestions().add(q("q1", null, 5));

        SubjectRegrouper.regroup(exam);

        assertEquals(5, exam.getQuestions().get(0).getOrderIndex());
        assertNull(exam.getQuestions().get(0).getSubjectGroup());
    }
}
