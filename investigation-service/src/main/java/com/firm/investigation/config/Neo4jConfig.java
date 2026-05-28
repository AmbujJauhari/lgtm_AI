package com.firm.investigation.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(InvestigationProperties properties) {
        InvestigationProperties.Neo4jProperties n = properties.neo4j();
        return GraphDatabase.driver(n.uri(), AuthTokens.basic(n.username(), n.password()));
    }
}
