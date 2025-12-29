package com.adityachandel.booklore.service.library;

import com.adityachandel.booklore.mapper.BookMapper;
import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.service.NotificationService;
import com.adityachandel.booklore.service.file.FileFingerprint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookRestorationService {

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final NotificationService notificationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void restoreDeletedBooks(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        if (libraryFiles.isEmpty()) return;

        Set<Path> currentPaths = libraryFiles.stream()
                .map(LibraryFile::getFullPath)
                .collect(Collectors.toSet());

        List<BookEntity> toRestore = libraryEntity.getBookEntities().stream()
                .filter(book -> Boolean.TRUE.equals(book.getDeleted()))
                .filter(book -> isBookFoundInCurrentFiles(book, currentPaths))
                .collect(Collectors.toList());

        if (toRestore.isEmpty()) return;

        toRestore.forEach(book -> {
            book.setDeleted(false);
            book.setDeletedAt(null);
            book.setAddedOn(Instant.now());
            notificationService.sendMessage(Topic.BOOK_ADD, bookMapper.toBookWithDescription(book, false));
        });
        bookRepository.saveAll(toRestore);

        List<Long> restoredIds = toRestore.stream()
                .map(BookEntity::getId)
                .toList();

        log.info("Restored {} books in library: {}", restoredIds.size(), libraryEntity.getName());
    }

    private boolean isBookFoundInCurrentFiles(BookEntity book, Set<Path> currentPaths) {
        if (book == null || currentPaths == null) {
            return false;
        }
        
        Path bookPath = book.getFullFilePath();
        if (bookPath != null && currentPaths.contains(bookPath)) {
            return true;
        }
        
        String currentHash = book.getCurrentHash();
        if (currentHash != null && !currentHash.isEmpty() && 
            bookHashExistsInPaths(currentHash, currentPaths, book.getId())) {
            return true;
        }
        
        String initialHash = book.getInitialHash();
        if (initialHash != null && !initialHash.isEmpty() && 
            !initialHash.equals(currentHash)) {
            return bookHashExistsInPaths(initialHash, currentPaths, book.getId());
        }
        
        return false;
    }

    private boolean bookHashExistsInPaths(String hash, Set<Path> paths, Long bookId) {
        if (hash == null || hash.isEmpty() || paths == null) {
            return false;
        }
        
        boolean exists = paths.stream().anyMatch(path -> pathMatchesHash(path, hash));
        
        if (exists) {
            log.debug("Book {} will be restored via hash match '{}'", bookId, hash);
        }
        
        return exists;
    }

    private boolean pathMatchesHash(Path path, String expectedHash) {
        if (path == null || expectedHash == null) {
            return false;
        }
        
        try {
            if (!Files.exists(path)) {
                return false;
            }
            String fileHash = FileFingerprint.generateHash(path);
            return expectedHash.equals(fileHash);
        } catch (Exception e) {
            log.trace("Could not check hash for '{}': {}", path, e.getMessage());
            return false;
        }
    }
}
