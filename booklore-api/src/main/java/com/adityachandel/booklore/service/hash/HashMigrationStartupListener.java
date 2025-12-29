package com.adityachandel.booklore.service.hash;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@AllArgsConstructor
public class HashMigrationStartupListener {

    private final HashMigrationService hashMigrationService;

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onApplicationReady() {
        log.info("Application ready - triggering startup hash migration");
        
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                hashMigrationService.performStartupMigration();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Startup hash migration interrupted");
            } catch (Exception e) {
                log.error("Startup hash migration failed: {}", e.getMessage(), e);
            }
        }, "hash-migration-startup").start();
    }
}
