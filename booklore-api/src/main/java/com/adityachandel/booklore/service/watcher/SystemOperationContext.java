package com.adityachandel.booklore.service.watcher;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks system-initiated file operations to prevent file watcher from re-processing them.
 * Prevents duplicate book entries when system modifies files during metadata updates or file moves.
 */
@Slf4j
public class SystemOperationContext {

    private static final long EXPIRATION_MS = 5000;
    private static final ThreadLocal<Set<Path>> threadLocalPaths = ThreadLocal.withInitial(HashSet::new);
    private static final Map<Path, Instant> globalOperations = new ConcurrentHashMap<>();
    
    public static void markSystemOperation(Path path) {
        if (path == null) {
            return;
        }
        
        Path normalizedPath = path.toAbsolutePath().normalize();
        threadLocalPaths.get().add(normalizedPath);
        globalOperations.put(normalizedPath, Instant.now());
        log.debug("Marked system operation: {}", normalizedPath);
    }
    
    public static void markSystemOperations(Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        
        paths.forEach(SystemOperationContext::markSystemOperation);
    }
    
    public static boolean isSystemOperation(Path path) {
        if (path == null) {
            return false;
        }
        
        Path normalizedPath = path.toAbsolutePath().normalize();
        
        if (threadLocalPaths.get().contains(normalizedPath)) {
            return true;
        }
        
        Instant timestamp = globalOperations.get(normalizedPath);
        if (timestamp != null) {
            if (Instant.now().toEpochMilli() - timestamp.toEpochMilli() < EXPIRATION_MS) {
                return true;
            } else {
                globalOperations.remove(normalizedPath);
            }
        }
        
        return false;
    }
    
    public static void unmarkSystemOperation(Path path) {
        if (path == null) {
            return;
        }
        
        Path normalizedPath = path.toAbsolutePath().normalize();
        threadLocalPaths.get().remove(normalizedPath);
        globalOperations.remove(normalizedPath);
        log.debug("Unmarked system operation: {}", normalizedPath);
    }
    
    public static void clearThreadContext() {
        Set<Path> paths = threadLocalPaths.get();
        paths.forEach(globalOperations::remove);
        paths.clear();
        log.trace("Cleared thread context");
    }
    
    public static void cleanupExpiredOperations() {
        Instant cutoff = Instant.now().minusMillis(EXPIRATION_MS);
        globalOperations.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }
    
    public static int getOperationCount() {
        cleanupExpiredOperations();
        return globalOperations.size();
    }
    
    public static void clearAll() {
        threadLocalPaths.remove();
        globalOperations.clear();
    }
}
