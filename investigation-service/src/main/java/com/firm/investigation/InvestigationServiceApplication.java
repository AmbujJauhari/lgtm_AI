package com.firm.investigation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.firm.investigation.config.InvestigationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InvestigationProperties.class)
public class InvestigationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InvestigationServiceApplication.class, args);
    }
}
