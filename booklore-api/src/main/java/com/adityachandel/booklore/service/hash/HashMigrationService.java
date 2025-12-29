package com.adityachandel.booklore.service.hash;

import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.service.NotificationService;
import com.adityachandel.booklore.service.file.FileFingerprint;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@AllArgsConstructor
public class HashMigrationService {

    private static final int BATCH_SIZE = 50;
    private static final long BATCH_DELAY_MS = 100;
    private static final int MAX_BOOKS_PER_RUN = 500;
    
    private final BookRepository bookRepository;
    private final NotificationService notificationService;
    private final AtomicBoolean migrationInProgress = new AtomicBoolean(false);
    private final AtomicBoolean startupMigrationCompleted = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void scheduledHashMigration() {
        if (!startupMigrationCompleted.get()) {
            log.debug("Skipping scheduled migration - startup migration not yet completed");
            return;
        }

        if (migrationInProgress.compareAndSet(false, true)) {
            try {
                log.info("[SCHEDULED_HASH_MIGRATION] Starting background hash regeneration");
                HashMigrationResult result = regenerateMissingHashes(MAX_BOOKS_PER_RUN);
                
                if (result.processed() > 0) {
                    log.info("[SCHEDULED_HASH_MIGRATION] Completed: processed={}, updated={}, failed={}", 
                            result.processed(), result.updated(), result.failed());
                }
            } finally {
                migrationInProgress.set(false);
            }
        } else {
            log.debug("Skipping scheduled migration - another migration already in progress");
        }
    }

    public void performStartupMigration() {
        if (startupMigrationCompleted.get()) {
            log.info("[STARTUP_MIGRATION] Already completed, skipping");
            return;
        }

        if (migrationInProgress.compareAndSet(false, true)) {
            try {
                log.info("[STARTUP_MIGRATION] Starting hash migration for books with missing hashes");
                
                long totalMissing = bookRepository.countBooksWithMissingHashes();
                if (totalMissing == 0) {
                    log.info("[STARTUP_MIGRATION] No books with missing hashes found");
                    startupMigrationCompleted.set(true);
                    return;
                }

                log.info("[STARTUP_MIGRATION] Found {} books with missing hashes", totalMissing);
                
                HashMigrationResult result = regenerateMissingHashes(null);
                
                log.info("[STARTUP_MIGRATION] Completed: processed={}, updated={}, failed={}, duration={}ms", 
                        result.processed(), result.updated(), result.failed(), result.durationMs());
                
                startupMigrationCompleted.set(true);
                
                sendNotification(result, "Startup hash migration completed");
                
            } catch (Exception e) {
                log.error("[STARTUP_MIGRATION] Failed: {}", e.getMessage(), e);
                startupMigrationCompleted.set(true);
            } finally {
                migrationInProgress.set(false);
            }
        }
    }

    public HashMigrationResult regenerateAllHashes() {
        if (!migrationInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Hash migration already in progress");
        }

        try {
            log.info("[HASH_REGENERATE_ALL] Starting full hash regeneration for all books");
            long startTime = System.currentTimeMillis();
            
            AtomicInteger processed = new AtomicInteger(0);
            AtomicInteger updated = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            
            int pageNumber = 0;
            Slice<BookEntity> slice;
            
            do {
                slice = bookRepository.findAllNonDeleted(PageRequest.of(pageNumber, BATCH_SIZE));
                
                for (BookEntity book : slice.getContent()) {
                    if (processBookHash(book, true)) {
                        updated.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                    processed.incrementAndGet();
                }
                
                pageNumber++;
                
                if (slice.hasNext()) {
                    sleep(BATCH_DELAY_MS);
                }
                
            } while (slice.hasNext());
            
            long duration = System.currentTimeMillis() - startTime;
            HashMigrationResult result = new HashMigrationResult(
                    processed.get(), updated.get(), failed.get(), duration);
            
            log.info("[HASH_REGENERATE_ALL] Completed: {}", result);
            sendNotification(result, "Full hash regeneration completed");
            
            return result;
            
        } finally {
            migrationInProgress.set(false);
        }
    }

    public HashMigrationResult regenerateMissingHashes(Integer maxBooks) {
        if (!migrationInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Hash migration already in progress");
        }

        try {
            log.info("[HASH_REGENERATE_MISSING] Starting hash regeneration for books with missing hashes");
            long startTime = System.currentTimeMillis();
            
            AtomicInteger processed = new AtomicInteger(0);
            AtomicInteger updated = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            
            int pageNumber = 0;
            Slice<BookEntity> slice;
            
            do {
                slice = bookRepository.findBooksWithMissingHashes(PageRequest.of(pageNumber, BATCH_SIZE));
                
                for (BookEntity book : slice.getContent()) {
                    if (maxBooks != null && processed.get() >= maxBooks) {
                        log.info("[HASH_REGENERATE_MISSING] Reached max books limit: {}", maxBooks);
                        break;
                    }
                    
                    if (processBookHash(book, false)) {
                        updated.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                    processed.incrementAndGet();
                }
                
                pageNumber++;
                
                if (slice.hasNext() && (maxBooks == null || processed.get() < maxBooks)) {
                    sleep(BATCH_DELAY_MS);
                }
                
            } while (slice.hasNext() && (maxBooks == null || processed.get() < maxBooks));
            
            long duration = System.currentTimeMillis() - startTime;
            HashMigrationResult result = new HashMigrationResult(
                    processed.get(), updated.get(), failed.get(), duration);
            
            log.info("[HASH_REGENERATE_MISSING] Completed: {}", result);
            
            return result;
            
        } finally {
            migrationInProgress.set(false);
        }
    }

    public HashMigrationStats getMigrationStats() {
        long total = bookRepository.count();
        long missing = bookRepository.countBooksWithMissingHashes();
        long brokenPaths = bookRepository.countBooksWithBrokenPaths();
        
        return new HashMigrationStats(
                total,
                total - missing,
                missing,
                brokenPaths,
                migrationInProgress.get(),
                startupMigrationCompleted.get()
        );
    }

    @Transactional
    protected boolean processBookHash(BookEntity book, boolean forceRegenerate) {
        try {
            Path filePath = book.getFullFilePath();
            
            if (!Files.exists(filePath)) {
                log.debug("Book {} has invalid file path: {}", book.getId(), filePath);
                return false;
            }
            
            String currentHash = book.getCurrentHash();
            boolean needsHash = currentHash == null || currentHash.isEmpty() || forceRegenerate;
            
            if (!needsHash) {
                return false;
            }
            
            String newHash = FileFingerprint.generateHash(filePath);
            
            if (newHash == null || newHash.isEmpty()) {
                log.warn("Failed to generate hash for book {}: {}", book.getId(), filePath);
                return false;
            }
            
            if (book.getInitialHash() == null || book.getInitialHash().isEmpty()) {
                book.setInitialHash(newHash);
            }
            
            book.setCurrentHash(newHash);
            bookRepository.save(book);
            
            log.debug("Updated hash for book {}: {}", book.getId(), newHash);
            return true;
            
        } catch (Exception e) {
            log.warn("Error processing hash for book {}: {}", book.getId(), e.getMessage());
            return false;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Hash migration sleep interrupted");
        }
    }

    private void sendNotification(HashMigrationResult result, String message) {
        try {
            notificationService.sendToAdmins(Topic.SYSTEM_NOTIFICATION, 
                    String.format("%s - Processed: %d, Updated: %d, Failed: %d", 
                            message, result.processed(), result.updated(), result.failed()));
        } catch (Exception e) {
            log.warn("Failed to send hash migration notification: {}", e.getMessage());
        }
    }

    public record HashMigrationResult(
            int processed,
            int updated,
            int failed,
            long durationMs
    ) {}

    public record HashMigrationStats(
            long totalBooks,
            long booksWithHashes,
            long booksMissingHashes,
            long booksWithBrokenPaths,
            boolean migrationInProgress,
            boolean startupMigrationCompleted
    ) {}
}
