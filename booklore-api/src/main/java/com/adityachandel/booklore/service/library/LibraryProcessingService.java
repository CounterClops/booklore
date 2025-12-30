package com.adityachandel.booklore.service.library;

import com.adityachandel.booklore.exception.ApiError;
import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookAdditionalFileEntity;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.websocket.LogNotification;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.BookAdditionalFileRepository;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.repository.LibraryRepository;
import com.adityachandel.booklore.service.NotificationService;
import com.adityachandel.booklore.service.file.FileFingerprint;
import com.adityachandel.booklore.task.options.RescanLibraryContext;
import com.adityachandel.booklore.util.BookLocationUtils;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
public class LibraryProcessingService {

    private final LibraryRepository libraryRepository;
    private final NotificationService notificationService;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final BookRepository bookRepository;
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
        bookRestorationService.restoreDeletedBooks(libraryFiles, libraryEntity);
        
        int modifiedCount = updateModifiedFileHashes(libraryFiles, libraryEntity);
        if (modifiedCount > 0) {
            log.info("Updated hashes for {} modified files in library: {}", modifiedCount, libraryEntity.getName());
        }
        
        entityManager.flush();
        entityManager.clear();
        processor.processLibraryFiles(detectNewBookPaths(libraryFiles, libraryEntity), libraryEntity);

        notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished refreshing library: " + libraryEntity.getName()));
    }

    public void processLibraryFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        LibraryFileProcessor processor = fileProcessorRegistry.getProcessor(libraryEntity);
        processor.processLibraryFiles(libraryFiles, libraryEntity);
    }

    protected List<Long> detectDeletedBookIds(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<Path> currentFullPaths = libraryFiles.stream()
                .map(LibraryFile::getFullPath)
                .collect(Collectors.toSet());

        return libraryEntity.getBookEntities().stream()
                .filter(book -> (book.getDeleted() == null || !book.getDeleted()))
                .filter(book -> !BookLocationUtils.canLocateBookInFiles(book, currentFullPaths))
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

        return libraryFiles.stream()
                .filter(file -> {
                    if (existingFullPaths.contains(file.getFullPath())) {
                        return false;
                    }
                    
                    if (isFileHashKnown(file, libraryEntity)) {
                        log.debug("File '{}' has known hash - will be relinked", file.getFileName());
                        return true;
                    }
                    
                    return true;
                })
                .collect(Collectors.toList());
    }

    private boolean isFileHashKnown(LibraryFile file, LibraryEntity libraryEntity) {
        try {
            if (!Files.exists(file.getFullPath())) {
                return false;
            }
            
            String fileHash = FileFingerprint.generateHash(file.getFullPath());
            if (fileHash == null || fileHash.isEmpty()) {
                return false;
            }
            
            return libraryEntity.getBookEntities().stream()
                    .anyMatch(book -> fileHash.equals(book.getCurrentHash()) || 
                                      fileHash.equals(book.getInitialHash()));
                                      
        } catch (Exception e) {
            log.debug("Could not calculate hash for '{}': {}", file.getFullPath(), e.getMessage());
            return false;
        }
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

    protected int updateModifiedFileHashes(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Map<Path, LibraryFile> filesByPath = libraryFiles.stream()
                .collect(Collectors.toMap(LibraryFile::getFullPath, f -> f, (a, b) -> a));

        int updatedCount = 0;
        int initializedCount = 0;
        
        for (BookEntity book : libraryEntity.getBookEntities()) {
            if (book.getDeleted() != null && book.getDeleted()) {
                continue;
            }
            
            Path bookPath = book.getFullFilePath();
            LibraryFile libraryFile = filesByPath.get(bookPath);
            
            if (libraryFile == null || !Files.exists(bookPath)) {
                continue;
            }
            
            if (!hasStoredModificationInfo(book)) {
                initializeModificationInfo(book, bookPath);
                initializedCount++;
            } else if (isFileModified(book, bookPath)) {
                updateBookFileMetadata(book, bookPath);
                updatedCount++;
            }
        }
        
        if (initializedCount > 0) {
            log.info("Initialized modification tracking for {} files in library '{}'", 
                    initializedCount, libraryEntity.getName());
        }
        
        return updatedCount;
    }

    private boolean hasStoredModificationInfo(BookEntity book) {
        return book.getLastModifiedTime() != null && book.getFileSizeKb() != null;
    }

    private void initializeModificationInfo(BookEntity book, Path filePath) {
        try {
            Instant fileMtime = Files.getLastModifiedTime(filePath).toInstant().truncatedTo(ChronoUnit.SECONDS);
            long fileSizeKb = Files.size(filePath) / 1024;
            
            book.setLastModifiedTime(fileMtime);
            book.setFileSizeKb(fileSizeKb);
            bookRepository.save(book);
        } catch (IOException e) {
            log.debug("Could not initialize modification info for '{}': {}", filePath, e.getMessage());
        }
    }

    private boolean isFileModified(BookEntity book, Path filePath) {
        try {
            Instant fileMtime = Files.getLastModifiedTime(filePath).toInstant().truncatedTo(ChronoUnit.SECONDS);
            long fileSizeKb = Files.size(filePath) / 1024;
            
            Instant storedMtime = book.getLastModifiedTime();
            Long storedSize = book.getFileSizeKb();
            
            boolean mtimeChanged = !fileMtime.equals(storedMtime);
            boolean sizeChanged = storedSize == null || fileSizeKb != storedSize;
            
            return mtimeChanged || sizeChanged;
        } catch (IOException e) {
            log.debug("Could not check modification for '{}': {}", filePath, e.getMessage());
            return false;
        }
    }

    private void updateBookFileMetadata(BookEntity book, Path filePath) {
        try {
            Instant fileMtime = Files.getLastModifiedTime(filePath).toInstant().truncatedTo(ChronoUnit.SECONDS);
            long fileSizeKb = Files.size(filePath) / 1024;
            String newHash = FileFingerprint.generateHash(filePath);
            
            log.debug("Updating modified file '{}': mtime={}, size={}", 
                    book.getFileName(), fileMtime, fileSizeKb);
            
            book.setLastModifiedTime(fileMtime);
            book.setFileSizeKb(fileSizeKb);
            if (newHash != null) {
                book.setCurrentHash(newHash);
            }
            bookRepository.save(book);
        } catch (IOException e) {
            log.warn("Failed to update modified file metadata for '{}': {}", filePath, e.getMessage());
        }
    }
}
