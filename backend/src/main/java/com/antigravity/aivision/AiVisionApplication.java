package com.antigravity.aivision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;

@SpringBootApplication
public class AiVisionApplication {

    @Value("${proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${proxy.port:7890}")
    private int proxyPort;

    @PostConstruct
    public void init() {
        if (proxyEnabled) {
            System.setProperty("https.proxyHost", proxyHost);
            System.setProperty("https.proxyPort", String.valueOf(proxyPort));
            System.out.println("Proxy configured: " + proxyHost + ":" + proxyPort);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(AiVisionApplication.class, args);
    }
}
