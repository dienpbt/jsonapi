package vn.etc.jsonapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JsonApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(JsonApiApplication.class, args);
    }
}
