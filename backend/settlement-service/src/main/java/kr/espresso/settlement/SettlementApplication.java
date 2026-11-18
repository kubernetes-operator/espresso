package kr.espresso.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "kr.espresso")
@EnableScheduling
public class SettlementApplication {
    public static void main(String[] args) { SpringApplication.run(SettlementApplication.class, args); }
}
