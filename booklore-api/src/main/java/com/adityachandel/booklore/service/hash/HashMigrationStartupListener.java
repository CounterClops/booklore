package com.adityachandel.booklore.service.hash;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

@Component
@Slf4j
@AllArgsConstructor
public class HashMigrationStartupListener {

    private static final long STARTUP_DELAY_MS = 5000;

    private final HashMigrationService hashMigrationService;
    private final Executor taskExecutor;

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onApplicationReady() {
        log.info("Application ready - scheduling startup hash migration");
        
        taskExecutor.execute(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MS);
                hashMigrationService.performStartupMigration();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Startup hash migration interrupted");
            } catch (Exception e) {
                log.error("Startup hash migration failed: {}", e.getMessage(), e);
            }
        });
    }
}
