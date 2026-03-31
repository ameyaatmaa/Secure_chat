package com.securechat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SecureChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecureChatApplication.class, args);
    }
}
