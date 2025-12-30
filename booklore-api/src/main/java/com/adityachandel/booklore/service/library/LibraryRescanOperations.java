package com.adityachandel.booklore.service.library;

import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookAdditionalFileEntity;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.repository.BookAdditionalFileRepository;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.repository.LibraryRepository;
import com.adityachandel.booklore.service.file.FileFingerprint;
import com.adityachandel.booklore.util.BookLocationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LibraryRescanOperations {

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository bookAdditionalFileRepository;
    private final BookDeletionService bookDeletionService;
    private final BookRestorationService bookRestorationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDeletions(List<LibraryFile> diskFiles, long libraryId) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElse(null);
        if (libraryEntity == null) {
            return;
        }

        List<Long> additionalFileIds = detectDeletedAdditionalFiles(diskFiles, libraryEntity);
        if (!additionalFileIds.isEmpty()) {
            log.info("Detected {} removed additional files in library: {}", additionalFileIds.size(), libraryEntity.getName());
            bookAdditionalFileRepository.deleteAllById(additionalFileIds);
        }

        List<Long> bookIds = detectDeletedBookIds(diskFiles, libraryEntity);
        if (!bookIds.isEmpty()) {
            log.info("Detected {} removed books in library: {}", bookIds.size(), libraryEntity.getName());
            bookDeletionService.processDeletedLibraryFiles(bookIds, diskFiles);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleRestorations(List<LibraryFile> diskFiles, long libraryId) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElse(null);
        if (libraryEntity == null) {
            return;
        }
        bookRestorationService.restoreDeletedBooks(diskFiles, libraryEntity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<LibraryFile> categorizeAndUpdateExistingFiles(List<LibraryFile> diskFiles, long libraryId) {
        Map<Path, Long> pathToBookId = buildPathToBookIdMap(libraryId);

        Set<Path> additionalFilePaths = bookAdditionalFileRepository.findByLibraryId(libraryId).stream()
                .map(BookAdditionalFileEntity::getFullFilePath)
                .collect(Collectors.toSet());

        List<LibraryFile> filesToProcess = new ArrayList<>();
        int modifiedCount = 0;
        int initializedCount = 0;

        for (LibraryFile diskFile : diskFiles) {
            Path filePath = diskFile.getFullPath();

            Long bookId = pathToBookId.get(filePath);

            if (bookId != null) {
                UpdateResult result = updateHashIfModified(bookId, filePath);
                if (result == UpdateResult.MODIFIED) {
                    modifiedCount++;
                } else if (result == UpdateResult.INITIALIZED) {
                    initializedCount++;
                }
            } else if (!additionalFilePaths.contains(filePath)) {
                filesToProcess.add(diskFile);
            }
        }

        if (modifiedCount > 0) {
            log.info("Updated hashes for {} modified files in library {}", modifiedCount, libraryId);
        }
        if (initializedCount > 0) {
            log.info("Initialized modification tracking for {} files in library {}", initializedCount, libraryId);
        }

        log.debug("Found {} files to process (new or relink candidates) in library {}", filesToProcess.size(), libraryId);
        return filesToProcess;
    }

    private Map<Path, Long> buildPathToBookIdMap(long libraryId) {
        List<Object[]> pathComponents = bookRepository.findBookPathComponentsByLibraryId(libraryId);

        Map<Path, Long> pathToBookId = new HashMap<>();
        for (Object[] row : pathComponents) {
            Long bookId = (Long) row[0];
            String libraryPath = (String) row[1];
            String fileSubPath = (String) row[2];
            String fileName = (String) row[3];

            Path fullPath = buildFullPath(libraryPath, fileSubPath, fileName);
            pathToBookId.put(fullPath, bookId);
        }
        return pathToBookId;
    }

    private Path buildFullPath(String libraryPath, String fileSubPath, String fileName) {
        Path basePath = Path.of(libraryPath);
        if (fileSubPath != null && !fileSubPath.isEmpty()) {
            basePath = basePath.resolve(fileSubPath);
        }
        return basePath.resolve(fileName);
    }

    private enum UpdateResult {
        NO_CHANGE,
        INITIALIZED,
        MODIFIED
    }

    private UpdateResult updateHashIfModified(Long bookId, Path filePath) {
        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        if (bookOpt.isEmpty()) {
            return UpdateResult.NO_CHANGE;
        }

        BookEntity book = bookOpt.get();

        if (!hasStoredModificationInfo(book)) {
            initializeModificationInfo(book, filePath);
            return UpdateResult.INITIALIZED;
        }

        if (isFileModified(book, filePath)) {
            updateBookFileMetadata(book, filePath);
            return UpdateResult.MODIFIED;
        }

        return UpdateResult.NO_CHANGE;
    }

    private List<Long> detectDeletedBookIds(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<Path> currentFullPaths = libraryFiles.stream()
                .map(LibraryFile::getFullPath)
                .collect(Collectors.toSet());

        return libraryEntity.getBookEntities().stream()
                .filter(book -> (book.getDeleted() == null || !book.getDeleted()))
                .filter(book -> !BookLocationUtils.canLocateBookInFiles(book, currentFullPaths))
                .map(BookEntity::getId)
                .collect(Collectors.toList());
    }

    private List<Long> detectDeletedAdditionalFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        Set<String> currentFileNames = libraryFiles.stream()
                .map(LibraryFile::getFileName)
                .collect(Collectors.toSet());

        List<BookAdditionalFileEntity> allAdditionalFiles = bookAdditionalFileRepository.findByLibraryId(libraryEntity.getId());

        return allAdditionalFiles.stream()
                .filter(additionalFile -> !currentFileNames.contains(additionalFile.getFileName()))
                .map(BookAdditionalFileEntity::getId)
                .collect(Collectors.toList());
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
