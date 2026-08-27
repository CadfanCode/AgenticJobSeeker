package se.caiowain.jobseeker;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("job_db")
                    .withUsername("jobseeker")
                    .withPassword("jobseeker");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jobseeker.ingest.enabled", () -> "false");
        registry.add("jobseeker.ingest.schedule-cron", () -> "-");
        // Global constraint: no test may reach a live job board. Any source bean that is
        // autowired rather than hand-built in a test points at a dead local port.
        registry.add("jobseeker.sources.jobtech.base-url", () -> "http://127.0.0.1:1");
    }
}
