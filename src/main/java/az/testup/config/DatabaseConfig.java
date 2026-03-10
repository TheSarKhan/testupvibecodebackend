package az.testup.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Configuration to handle database-level fixes that Hibernate's ddl-auto:update 
 * might miss, such as updating enum check constraints or foreign key behaviors.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class DatabaseConfig {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void fixSchema() {
        fixQuestionTypeConstraint();
        fixAnswerForeignKey();
    }

    private void fixQuestionTypeConstraint() {
        try {
            log.info("Checking and fixing database constraints for MULTI_SELECT...");
            
            // This query finds the check constraint name for question_type column in questions table
            String findConstraintSql = "SELECT constraint_name FROM information_schema.constraint_column_usage " +
                                       "WHERE table_name = 'questions' AND column_name = 'question_type'";
            
            List<String> constraints = jdbcTemplate.queryForList(findConstraintSql, String.class);
            
            for (String constraint : constraints) {
                if (constraint.toLowerCase().contains("check") || constraint.toLowerCase().contains("question_type")) {
                    log.info("Dropping outdated enum check constraint: {}", constraint);
                    jdbcTemplate.execute("ALTER TABLE questions DROP CONSTRAINT " + constraint);
                }
            }
        } catch (Exception e) {
            log.warn("Could not fix question_type constraint: {}", e.getMessage());
        }
    }

    private void fixAnswerForeignKey() {
        try {
            log.info("Checking and fixing answer foreign key to support question deletion (ON DELETE CASCADE)...");
            
            // Find the foreign key constraint from answers to questions
            String findFkSql = "SELECT tc.constraint_name " +
                               "FROM information_schema.table_constraints AS tc " +
                               "JOIN information_schema.key_column_usage AS kcu ON tc.constraint_name = kcu.constraint_name " +
                               "WHERE tc.table_name = 'answers' AND kcu.column_name = 'question_id' AND tc.constraint_type = 'FOREIGN KEY'";
            
            List<String> fkNames = jdbcTemplate.queryForList(findFkSql, String.class);
            
            for (String fkName : fkNames) {
                log.info("Updating foreign key {} to ON DELETE CASCADE", fkName);
                jdbcTemplate.execute("ALTER TABLE answers DROP CONSTRAINT " + fkName);
                jdbcTemplate.execute("ALTER TABLE answers ADD CONSTRAINT " + fkName + 
                                     " FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE");
            }
            log.info("Answer foreign key fix completed successfully.");
        } catch (Exception e) {
            log.warn("Could not update answer foreign key: {}", e.getMessage());
        }
    }
}

