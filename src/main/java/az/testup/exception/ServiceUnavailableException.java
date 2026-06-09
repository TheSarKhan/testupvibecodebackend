package az.testup.exception;

/**
 * Thrown when an external dependency (AI provider, payment gateway) or a
 * server-side generation step (Excel export) fails in a way the user can't
 * fix by changing input. Carries a user-readable Azerbaijani message; the
 * technical cause must be logged at the throw site, never put in the message.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
