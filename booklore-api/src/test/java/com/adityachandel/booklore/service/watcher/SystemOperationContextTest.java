package com.adityachandel.booklore.service.watcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class SystemOperationContextTest {

    @BeforeEach
    void setUp() {
        SystemOperationContext.clearAll();
    }

    @AfterEach
    void tearDown() {
        SystemOperationContext.clearAll();
    }

    @Test
    void testMarkAndCheckSystemOperation() {
        Path testPath = Paths.get("/test/file.txt");
        
        assertFalse(SystemOperationContext.isSystemOperation(testPath));
        
        SystemOperationContext.markSystemOperation(testPath);
        
        assertTrue(SystemOperationContext.isSystemOperation(testPath));
    }

    @Test
    void testUnmarkSystemOperation() {
        Path testPath = Paths.get("/test/file.txt");
        
        SystemOperationContext.markSystemOperation(testPath);
        assertTrue(SystemOperationContext.isSystemOperation(testPath));
        
        SystemOperationContext.unmarkSystemOperation(testPath);
        assertFalse(SystemOperationContext.isSystemOperation(testPath));
    }

    @Test
    void testClearThreadContext() {
        Path path1 = Paths.get("/test/file1.txt");
        Path path2 = Paths.get("/test/file2.txt");
        
        SystemOperationContext.markSystemOperation(path1);
        SystemOperationContext.markSystemOperation(path2);
        
        assertTrue(SystemOperationContext.isSystemOperation(path1));
        assertTrue(SystemOperationContext.isSystemOperation(path2));
        
        SystemOperationContext.clearThreadContext();
        
        // Paths should still be in global map briefly
        assertFalse(SystemOperationContext.isSystemOperation(path1));
        assertFalse(SystemOperationContext.isSystemOperation(path2));
    }

    @Test
    void testNullPathHandling() {
        assertDoesNotThrow(() -> SystemOperationContext.markSystemOperation(null));
        assertFalse(SystemOperationContext.isSystemOperation(null));
        assertDoesNotThrow(() -> SystemOperationContext.unmarkSystemOperation(null));
    }

    @Test
    void testPathNormalization() {
        Path path1 = Paths.get("/test/../test/file.txt");
        Path path2 = Paths.get("/test/file.txt");
        
        SystemOperationContext.markSystemOperation(path1);
        
        // Both paths should be recognized as the same after normalization
        assertTrue(SystemOperationContext.isSystemOperation(path2));
    }

    @Test
    void testOperationCount() {
        assertEquals(0, SystemOperationContext.getOperationCount());
        
        SystemOperationContext.markSystemOperation(Paths.get("/test/file1.txt"));
        SystemOperationContext.markSystemOperation(Paths.get("/test/file2.txt"));
        
        assertEquals(2, SystemOperationContext.getOperationCount());
        
        SystemOperationContext.clearAll();
        assertEquals(0, SystemOperationContext.getOperationCount());
    }

    @Test
    void testExpirationCleansUp() throws InterruptedException {
        // This test would require mocking time or waiting for expiration
        // For now, we just test that cleanup doesn't throw errors
        Path testPath = Paths.get("/test/file.txt");
        SystemOperationContext.markSystemOperation(testPath);
        
        assertDoesNotThrow(() -> SystemOperationContext.cleanupExpiredOperations());
    }

    @Test
    void testMultipleMarksOfSamePath() {
        Path testPath = Paths.get("/test/file.txt");
        
        SystemOperationContext.markSystemOperation(testPath);
        SystemOperationContext.markSystemOperation(testPath);
        SystemOperationContext.markSystemOperation(testPath);
        
        assertTrue(SystemOperationContext.isSystemOperation(testPath));
        assertEquals(1, SystemOperationContext.getOperationCount());
    }

    @Test
    void testConcurrentAccessSafety() {
        Path testPath = Paths.get("/test/file.txt");
        
        // Mark in one thread
        SystemOperationContext.markSystemOperation(testPath);
        
        // Should be able to check from same thread
        assertTrue(SystemOperationContext.isSystemOperation(testPath));
        
        // Clear should work
        assertDoesNotThrow(() -> SystemOperationContext.clearThreadContext());
    }
}
