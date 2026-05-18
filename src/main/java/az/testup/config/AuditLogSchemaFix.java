package az.testup.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drops the stale CHECK constraint on {@code audit_logs.action} that Hibernate
 * auto-generated for {@code @Enumerated(EnumType.STRING)}. The constraint is
 * frozen at the moment the table was first created, so every new value added
 * to the {@code AuditAction} enum afterwards (e.g. {@code BANK_SUBJECT_CREATED})
 * fails inserts with {@code DataIntegrityViolationException}.
 *
 * Validation still happens at the JVM layer (enum parsing rejects unknown
 * names), so dropping the DB-level CHECK is safe.
 *
 * Uses {@link ApplicationRunner} (runs AFTER Hibernate finishes its schema
 * management AND the full Spring context is up) together with
 * {@link JdbcTemplate} (auto-commits per statement). {@code @PostConstruct}
 * was not reliable here because the @Transactional proxy isn't applied to
 * the bean itself, so the EntityManager native query never committed.
 *
 * Idempotent: {@code DROP CONSTRAINT IF EXISTS} is a no-op on re-runs.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AuditLogSchemaFix {

    @Bean
    public ApplicationRunner auditLogSchemaFixer(JdbcTemplate jdbc) {
        return args -> {
            try {
                jdbc.execute("ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_action_check");
                log.info("audit_logs_action_check constraint dropped (or already absent) — AuditAction enum additions are now safe");
            } catch (Exception e) {
                // Don't block startup. Likely a non-Postgres environment (e.g. H2 tests).
                log.warn("AuditLogSchemaFix: could not drop constraint: {}", e.getMessage());
            }
        };
    }
}
