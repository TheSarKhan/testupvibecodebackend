package az.testup;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class    TestupApplication {

    public static void main(String[] args) {
        // Entities store wall-clock LocalDateTime, so every timestamp we persist is
        // implicitly in the JVM's zone. The production host runs Europe/Berlin while
        // the database is Etc/UTC and the frontend reads a naked timestamp as UTC
        // (utils/date.js), so rows landed two hours ahead: every audit log looked
        // like it happened in the future and rendered as "İndicə".
        //
        // Pin the JVM to UTC so the stored wall-clock really is UTC and all three
        // agree. Must run before Spring starts — beans capture the default zone.
        // Code that needs Azerbaijan time converts explicitly (EmailService,
        // SubmissionService use ZoneId.of("Asia/Baku")).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(TestupApplication.class, args);
   System.out.println("TestupApplication started successfully!");
    }
}
