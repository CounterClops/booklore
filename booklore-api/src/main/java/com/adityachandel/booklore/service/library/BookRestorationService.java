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
    public void restoreDeletedBooks(List<LibraryFile> libraryFiles) {
        if (libraryFiles.isEmpty()) return;

        LibraryEntity libraryEntity = libraryFiles.getFirst().getLibraryEntity();
        Set<Path> currentPaths = libraryFiles.stream()
                .map(LibraryFile::getFullPath)
                .collect(Collectors.toSet());
        
        // Build hash map for moved file detection
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

        List<BookEntity> toRestore = libraryEntity.getBookEntities().stream()
                .filter(book -> Boolean.TRUE.equals(book.getDeleted()))
                .filter(book -> {
                    // Restore if exact path match
                    if (currentPaths.contains(book.getFullFilePath())) {
                        return true;
                    }
                    
                    // Restore if hash matches (file was moved/renamed)
                    String currentHash = book.getCurrentHash();
                    if (currentHash != null && !currentHash.isEmpty() && hashToPathMap.containsKey(currentHash)) {
                        log.debug("Book {} will be restored via hash match '{}'", book.getId(), currentHash);
                        return true;
                    }
                    
                    String initialHash = book.getInitialHash();
                    if (initialHash != null && !initialHash.isEmpty() && hashToPathMap.containsKey(initialHash)) {
                        log.debug("Book {} will be restored via hash match '{}'", book.getId(), initialHash);
                        return true;
                    }
                    
                    return false;
                })
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
}
