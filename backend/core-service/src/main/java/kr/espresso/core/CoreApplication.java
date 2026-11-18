package kr.espresso.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "kr.espresso")
@EnableScheduling
public class CoreApplication {
    public static void main(String[] args) { SpringApplication.run(CoreApplication.class, args); }
}
