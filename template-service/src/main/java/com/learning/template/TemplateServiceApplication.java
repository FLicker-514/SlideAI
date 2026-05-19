package com.learning.template;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class TemplateServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TemplateServiceApplication.class, args);
    }
}
