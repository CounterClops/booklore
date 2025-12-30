package com.adityachandel.booklore.service.watcher;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SystemOperationContext {

    private static final long EXPIRATION_MS = 5000;
    private static final Map<Path, Instant> activeOperations = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<Path>> threadMarkedPaths = ThreadLocal.withInitial(ConcurrentHashMap::newKeySet);

    public static void markSystemOperation(Path path) {
        if (path == null) {
            return;
        }
        
        Path normalizedPath = path.toAbsolutePath().normalize();
        activeOperations.put(normalizedPath, Instant.now());
        threadMarkedPaths.get().add(normalizedPath);
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
        Instant timestamp = activeOperations.get(normalizedPath);

        if (timestamp == null) {
            return false;
        }

        if (Instant.now().toEpochMilli() - timestamp.toEpochMilli() < EXPIRATION_MS) {
            return true;
        }

        activeOperations.remove(normalizedPath);
        return false;
    }
    
    public static void unmarkSystemOperation(Path path) {
        if (path == null) {
            return;
        }
        
        Path normalizedPath = path.toAbsolutePath().normalize();
        activeOperations.remove(normalizedPath);
        threadMarkedPaths.get().remove(normalizedPath);
        log.debug("Unmarked system operation: {}", normalizedPath);
    }
    
    public static void clearThreadContext() {
        Set<Path> paths = threadMarkedPaths.get();
        paths.forEach(activeOperations::remove);
        paths.clear();
        log.trace("Cleared thread context");
    }
    
    public static void cleanupExpiredOperations() {
        Instant cutoff = Instant.now().minusMillis(EXPIRATION_MS);
        activeOperations.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }
    
    public static int getOperationCount() {
        cleanupExpiredOperations();
        return activeOperations.size();
    }
    
    public static void clearAll() {
        threadMarkedPaths.remove();
        activeOperations.clear();
    }
}
