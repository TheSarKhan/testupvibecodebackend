package az.testup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class    TestupApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestupApplication.class, args);
    }
}
