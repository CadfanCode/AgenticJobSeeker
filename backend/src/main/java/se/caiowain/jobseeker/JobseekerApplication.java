package se.caiowain.jobseeker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobseekerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobseekerApplication.class, args);
    }
}
