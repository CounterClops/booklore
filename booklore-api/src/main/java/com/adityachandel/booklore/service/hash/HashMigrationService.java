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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
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

    public HashMigrationResult regenerateAllHashes() {
        return executeMigrationWithLock(() -> {
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
        });
    }

    public HashMigrationResult regenerateMissingHashes(Integer maxBooks) {
        return executeMigrationWithLock(() -> regenerateMissingHashesInternal(maxBooks));
    }

    private HashMigrationResult regenerateMissingHashesInternal(Integer maxBooks) {
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
    }

    private <T> T executeMigrationWithLock(java.util.function.Supplier<T> migrationWork) {
        if (!migrationInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Hash migration already in progress");
        }
        try {
            return migrationWork.get();
        } finally {
            migrationInProgress.set(false);
        }
    }

    private <T> T tryExecuteMigrationWithLock(java.util.function.Supplier<T> migrationWork, String skipMessage) {
        if (!migrationInProgress.compareAndSet(false, true)) {
            log.debug(skipMessage);
            return null;
        }
        try {
            return migrationWork.get();
        } finally {
            migrationInProgress.set(false);
        }
    }

    public HashMigrationStats getMigrationStats() {
        long total = bookRepository.count();
        long missing = bookRepository.countBooksWithMissingHashes();
        long softDeleted = bookRepository.countSoftDeletedBooks();
        
        return new HashMigrationStats(
                total,
                total - missing,
                missing,
                softDeleted,
                migrationInProgress.get()
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
            long booksSoftDeleted,
            boolean migrationInProgress
    ) {}
}
