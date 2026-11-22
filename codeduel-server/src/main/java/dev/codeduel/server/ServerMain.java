package dev.codeduel.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
@EnableScheduling
@RestController
public class ServerMain {
    public static void main(String[] args) { SpringApplication.run(ServerMain.class, args); }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "up"); }
}
