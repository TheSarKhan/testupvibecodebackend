package az.testup.util;

import java.security.SecureRandom;
import java.util.UUID;

public class CodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generates a 6-digit numeric access code for private exams.
     */
    public static String generateAccessCode() {
        int code = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(code);
    }

    /**
     * Generates a unique share link identifier.
     */
    public static String generateShareLink() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
