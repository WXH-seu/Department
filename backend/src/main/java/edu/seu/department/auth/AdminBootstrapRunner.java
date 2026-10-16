package edu.seu.department.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
@ConditionalOnProperty(name = "department.bootstrap.enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private final AdminBootstrapService service;
    private final Environment environment;

    public AdminBootstrapRunner(AdminBootstrapService service, Environment environment) {
        this.service = service;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean created = service.initialize(environment.getProperty("department.bootstrap.username"),
            environment.getProperty("department.bootstrap.password"));
        log.info(created ? "Initial administrator created. Disable bootstrap and remove its credentials."
            : "Administrator initialization skipped: installation already contains accounts.");
    }
}
