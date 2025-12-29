package com.adityachandel.booklore.service.watcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFileEventProcessorTest {

    @BeforeEach
    void setUp() {
        SystemOperationContext.clearAll();
    }

    @AfterEach
    void tearDown() {
        SystemOperationContext.clearAll();
    }

    @Test
    void systemOperationContext_markedPath_isRecognizedAsSystemOperation() {
        Path filePath = Paths.get("/library/root/book.epub");
        
        assertFalse(SystemOperationContext.isSystemOperation(filePath));
        
        SystemOperationContext.markSystemOperation(filePath);
        
        assertTrue(SystemOperationContext.isSystemOperation(filePath));
    }

    @Test
    void systemOperationContext_unmarkedPath_isNotSystemOperation() {
        Path filePath = Paths.get("/library/root/book.epub");
        
        assertFalse(SystemOperationContext.isSystemOperation(filePath));
    }

    @Test
    void systemOperationContext_clearedContext_removesMarkedPaths() {
        Path filePath = Paths.get("/library/root/book.epub");
        
        SystemOperationContext.markSystemOperation(filePath);
        assertTrue(SystemOperationContext.isSystemOperation(filePath));
        
        SystemOperationContext.clearThreadContext();
        
        assertFalse(SystemOperationContext.isSystemOperation(filePath));
    }

    @Test
    void systemOperationContext_normalizedPaths_areRecognizedAsSame() {
        Path unnormalizedPath = Paths.get("/library/root/../root/book.epub");
        Path normalizedPath = unnormalizedPath.toAbsolutePath().normalize();
        
        SystemOperationContext.markSystemOperation(unnormalizedPath);
        
        assertTrue(SystemOperationContext.isSystemOperation(normalizedPath));
    }

    @Test
    void systemOperationContext_multiplePathsMarked_allAreRecognized() {
        Path path1 = Paths.get("/library/root/book1.epub");
        Path path2 = Paths.get("/library/root/book2.epub");
        
        SystemOperationContext.markSystemOperation(path1);
        SystemOperationContext.markSystemOperation(path2);
        
        assertTrue(SystemOperationContext.isSystemOperation(path1));
        assertTrue(SystemOperationContext.isSystemOperation(path2));
        
        assertEquals(2, SystemOperationContext.getOperationCount());
    }
}
