package com.adityachandel.booklore.service.library;

import com.adityachandel.booklore.exception.ApiError;
import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookAdditionalFileEntity;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.websocket.LogNotification;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.BookAdditionalFileRepository;
import com.adityachandel.booklore.repository.LibraryRepository;
import com.adityachandel.booklore.service.NotificationService;
import com.adityachandel.booklore.service.file.FileFingerprint;
import com.adityachandel.booklore.task.options.RescanLibraryContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
public class LibraryProcessingService {

    private final LibraryRepository libraryRepository;
    private final NotificationService notificationService;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final LibraryFileProcessorRegistry fileProcessorRegistry;
    private final BookRestorationService bookRestorationService;
    private final BookDeletionService bookDeletionService;
    private final LibraryFileHelper libraryFileHelper;
    @PersistenceContext
    private final EntityManager entityManager;

    @Transactional
    public void processLibrary(long libraryId) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Started processing library: " + libraryEntity.getName()));
        LibraryFileProcessor processor = fileProcessorRegistry.getProcessor(libraryEntity);
        try {
            List<LibraryFile> libraryFiles = libraryFileHelper.getLibraryFiles(libraryEntity, processor);
            processor.processLibraryFiles(libraryFiles, libraryEntity);
            notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished processing library: " + libraryEntity.getName()));
        } catch (IOException e) {
            log.error("Failed to process library {}: {}", libraryEntity.getName(), e.getMessage(), e);
            notificationService.sendMessage(Topic.LOG, LogNotification.error("Failed to process library: " + libraryEntity.getName() + " - " + e.getMessage()));
            throw new UncheckedIOException("Library processing failed", e);
        }
    }

    @Transactional
    public void rescanLibrary(RescanLibraryContext context) throws IOException {
        LibraryEntity libraryEntity = libraryRepository.findById(context.getLibraryId()).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(context.getLibraryId()));
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Started refreshing library: " + libraryEntity.getName()));
        LibraryFileProcessor processor = fileProcessorRegistry.getProcessor(libraryEntity);
        List<LibraryFile> libraryFiles = libraryFileHelper.getLibraryFiles(libraryEntity, processor);
        List<Long> additionalFileIds = detectDeletedAdditionalFiles(libraryFiles, libraryEntity);
        if (!additionalFileIds.isEmpty()) {
            log.info("Detected {} removed additional files in library: {}", additionalFileIds.size(), libraryEntity.getName());
            bookDeletionService.deleteRemovedAdditionalFiles(additionalFileIds);
        }
        List<Long> bookIds = detectDeletedBookIds(libraryFiles, libraryEntity);
        if (!bookIds.isEmpty()) {
            log.info("Detected {} removed books in library: {}", bookIds.size(), libraryEntity.getName());
            bookDeletionService.processDeletedLibraryFiles(bookIds, libraryFiles);
        }
        bookRestorationService.restoreDeletedBooks(libraryFiles);
        entityManager.clear();
        processor.processLibraryFiles(detectNewBookPaths(libraryFiles, libraryEntity), libraryEntity);

        notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished refreshing library: " + libraryEntity.getName()));
    }

    public void processLibraryFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        LibraryFileProcessor processor = fileProcessorRegistry.getProcessor(libraryEntity);
        processor.processLibraryFiles(libraryFiles, libraryEntity);
    }

    protected static List<Long> detectDeletedBookIds(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<Path> currentFullPaths = libraryFiles.stream()
                .map(LibraryFile::getFullPath)
                .collect(Collectors.toSet());
        
        // Build hash map of current files for moved file detection
        Map<String, Path> hashToPathMap = new HashMap<>();
        for (LibraryFile file : libraryFiles) {
            try {
                if (Files.exists(file.getFullPath())) {
                    String hash = FileFingerprint.generateHash(file.getFullPath());
                    if (hash != null && !hash.isEmpty()) {
                        hashToPathMap.put(hash, file.getFullPath());
                    }
                }
            } catch (Exception e) {
                log.debug("Could not calculate hash for '{}': {}", file.getFullPath(), e.getMessage());
            }
        }

        return libraryEntity.getBookEntities().stream()
                .filter(book -> (book.getDeleted() == null || !book.getDeleted()))
                .filter(book -> {
                    // Don't mark as deleted if path exists
                    if (currentFullPaths.contains(book.getFullFilePath())) {
                        return false;
                    }
                    
                    // Don't mark as deleted if hash exists elsewhere (file was moved/renamed)
                    String currentHash = book.getCurrentHash();
                    if (currentHash != null && !currentHash.isEmpty() && hashToPathMap.containsKey(currentHash)) {
                        log.debug("Book {} not marked as deleted - hash '{}' found at new path: {}", 
                                book.getId(), currentHash, hashToPathMap.get(currentHash));
                        return false;
                    }
                    
                    String initialHash = book.getInitialHash();
                    if (initialHash != null && !initialHash.isEmpty() && hashToPathMap.containsKey(initialHash)) {
                        log.debug("Book {} not marked as deleted - hash '{}' found at new path: {}", 
                                book.getId(), initialHash, hashToPathMap.get(initialHash));
                        return false;
                    }
                    
                    // File truly deleted - not found by path or hash
                    return true;
                })
                .map(BookEntity::getId)
                .collect(Collectors.toList());
    }

    protected List<LibraryFile> detectNewBookPaths(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<Path> existingFullPaths = libraryEntity.getBookEntities().stream()
                .map(BookEntity::getFullFilePath)
                .collect(Collectors.toSet());

        Set<Path> additionalFilePaths = bookAdditionalFileRepository.findByLibraryId(libraryEntity.getId()).stream()
                .map(BookAdditionalFileEntity::getFullFilePath)
                .collect(Collectors.toSet());

        existingFullPaths.addAll(additionalFilePaths);
        
        // Build set of existing hashes to detect moved files
        Set<String> existingHashes = new HashSet<>();
        for (BookEntity book : libraryEntity.getBookEntities()) {
            if (book.getCurrentHash() != null && !book.getCurrentHash().isEmpty()) {
                existingHashes.add(book.getCurrentHash());
            }
            if (book.getInitialHash() != null && !book.getInitialHash().isEmpty()) {
                existingHashes.add(book.getInitialHash());
            }
        }

        return libraryFiles.stream()
                .filter(file -> {
                    // Already exists at this exact path
                    if (existingFullPaths.contains(file.getFullPath())) {
                        return false;
                    }
                    
                    // Check if this file's hash already exists (moved file)
                    // If it does, still process it so AbstractFileProcessor.processFile() can relink it
                    try {
                        if (Files.exists(file.getFullPath())) {
                            String hash = FileFingerprint.generateHash(file.getFullPath());
                            if (hash != null && !hash.isEmpty() && existingHashes.contains(hash)) {
                                log.debug("File '{}' has matching hash '{}' - will be relinked", 
                                        file.getFileName(), hash);
                                return true; // Include for processing so it gets relinked
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Could not calculate hash for '{}': {}", file.getFullPath(), e.getMessage());
                    }
                    
                    // Truly new file
                    return true;
                })
                .collect(Collectors.toList());
    }

    protected List<Long> detectDeletedAdditionalFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<String> currentFileNames = libraryFiles.stream()
                .map(LibraryFile::getFileName)
                .collect(Collectors.toSet());

        List<BookAdditionalFileEntity> allAdditionalFiles = bookAdditionalFileRepository.findByLibraryId(libraryEntity.getId());

        return allAdditionalFiles.stream()
                .filter(additionalFile -> !currentFileNames.contains(additionalFile.getFileName()))
                .map(BookAdditionalFileEntity::getId)
                .collect(Collectors.toList());
    }
}
