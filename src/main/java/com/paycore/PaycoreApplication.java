package com.paycore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaycoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaycoreApplication.class, args);
    }
}
