package db.migration;

import az.testup.entity.Exam;
import az.testup.entity.Passage;
import az.testup.entity.Question;
import az.testup.util.SubjectRegrouper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One-time repair for multi-subject exams whose orderIndex space was scrambled
 * by the original BUG-253 resort: subjectGroup == null ranked last instead of
 * as the main section (subjects[0]), and passages were never renumbered — so
 * subjects rendered interleaved on the site and in the PDF ("fənlər qarışıq",
 * exam 102750c39e3e). Create/update now regroup correctly via
 * {@link az.testup.util.SubjectRegrouper}; this migration pushes the same
 * regrouping through every existing non-deleted multi-subject exam so stored
 * data matches what the renderers expect without waiting for a re-save.
 *
 * Idempotent by construction — an already-grouped exam produces zero updates.
 * Answers reference questions by id, so in-flight submissions keep their
 * answers; only the display order of previously-broken exams changes.
 */
public class V36__Regroup_multi_subject_exam_order extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();

        List<Long> examIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT l.exam_id FROM exam_subject_list l " +
                "JOIN exams e ON e.id = l.exam_id AND e.deleted = FALSE " +
                "GROUP BY l.exam_id HAVING COUNT(*) > 1 ORDER BY l.exam_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) examIds.add(rs.getLong(1));
        }

        for (Long examId : examIds) {
            regroupExam(conn, examId);
        }
    }

    private void regroupExam(Connection conn, long examId) throws Exception {
        // Detached entity instances only — no JPA session at migration time.
        // SubjectRegrouper needs subjects, questions (with passage links) and
        // passages; nothing else is read, so the rest of Exam stays unset.
        Exam exam = Exam.builder().build();

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT subject FROM exam_subject_list WHERE exam_id = ?")) {
            ps.setLong(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) exam.getSubjects().add(rs.getString(1));
            }
        }

        Map<Long, Passage> passagesById = new HashMap<>();
        Map<Long, Integer> oldPassageOrder = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, order_index, subject_group FROM passages WHERE exam_id = ?")) {
            ps.setLong(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Passage p = Passage.builder()
                            .id(rs.getLong(1))
                            .orderIndex((Integer) rs.getObject(2))
                            .subjectGroup(rs.getString(3))
                            .build();
                    passagesById.put(p.getId(), p);
                    oldPassageOrder.put(p.getId(), p.getOrderIndex());
                    exam.getPassages().add(p);
                }
            }
        }

        Map<Long, Integer> oldQuestionOrder = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, order_index, subject_group, passage_id FROM questions WHERE exam_id = ?")) {
            ps.setLong(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Question q = Question.builder()
                            .id(rs.getLong(1))
                            .orderIndex((Integer) rs.getObject(2))
                            .subjectGroup(rs.getString(3))
                            .build();
                    Long passageId = (Long) rs.getObject(4);
                    if (passageId != null) q.setPassage(passagesById.get(passageId));
                    oldQuestionOrder.put(q.getId(), q.getOrderIndex());
                    exam.getQuestions().add(q);
                }
            }
        }

        SubjectRegrouper.regroup(exam);

        try (PreparedStatement up = conn.prepareStatement(
                "UPDATE questions SET order_index = ? WHERE id = ?")) {
            for (Question q : exam.getQuestions()) {
                if (!Objects.equals(q.getOrderIndex(), oldQuestionOrder.get(q.getId()))) {
                    up.setInt(1, q.getOrderIndex());
                    up.setLong(2, q.getId());
                    up.addBatch();
                }
            }
            up.executeBatch();
        }
        try (PreparedStatement up = conn.prepareStatement(
                "UPDATE passages SET order_index = ? WHERE id = ?")) {
            for (Passage p : exam.getPassages()) {
                if (!Objects.equals(p.getOrderIndex(), oldPassageOrder.get(p.getId()))) {
                    up.setInt(1, p.getOrderIndex());
                    up.setLong(2, p.getId());
                    up.addBatch();
                }
            }
            up.executeBatch();
        }
    }
}
