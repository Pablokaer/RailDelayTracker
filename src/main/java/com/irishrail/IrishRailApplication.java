package com.irishrail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IrishRailApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrishRailApplication.class, args);
    }
}
