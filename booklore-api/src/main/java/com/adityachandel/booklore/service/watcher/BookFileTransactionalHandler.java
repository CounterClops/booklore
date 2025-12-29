package com.adityachandel.booklore.service.watcher;

import com.adityachandel.booklore.exception.ApiError;
import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.entity.LibraryPathEntity;
import com.adityachandel.booklore.model.enums.BookFileExtension;
import com.adityachandel.booklore.model.websocket.LogNotification;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.LibraryRepository;
import com.adityachandel.booklore.service.NotificationService;
import com.adityachandel.booklore.service.library.LibraryProcessingService;
import com.adityachandel.booklore.util.FileUtils;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.adityachandel.booklore.model.enums.PermissionType.ADMIN;
import static com.adityachandel.booklore.model.enums.PermissionType.MANAGE_LIBRARY;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookFileTransactionalHandler {

    private final BookFilePersistenceService bookFilePersistenceService;
    private final LibraryProcessingService libraryProcessingService;
    private final NotificationService notificationService;
    private final LibraryRepository libraryRepository;

    @Transactional()
    public void handleNewBookFile(long libraryId, Path path) {
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        String filePath = path.toString();
        String fileName = path.getFileName().toString();
        String libraryPath = bookFilePersistenceService.findMatchingLibraryPath(libraryEntity, path);

        notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Started processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));

        LibraryPathEntity libraryPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(libraryEntity, libraryPath);

        LibraryFile libraryFile = LibraryFile.builder()
                .libraryEntity(libraryEntity)
                .libraryPathEntity(libraryPathEntity)
                .fileSubPath(FileUtils.getRelativeSubPath(libraryPathEntity.getPath(), path))
                .fileName(fileName)
                .bookFileType(BookFileExtension.fromFileName(fileName)
                        .map(BookFileExtension::getType)
                        .orElseThrow(() -> new IllegalArgumentException("Unsupported book file type: " + fileName)))
                .build();

        libraryProcessingService.processLibraryFiles(List.of(libraryFile), libraryEntity);

        notificationService.sendMessageToPermissions(Topic.LOG, LogNotification.info("Finished processing file: " + filePath), Set.of(ADMIN, MANAGE_LIBRARY));
        log.info("[CREATE] Completed processing for file '{}'", filePath);
    }

    @Transactional()
    public void handleFileMove(long libraryId, Path oldPath, Path newPath, String hash) {
        try {
            LibraryEntity library = libraryRepository.findById(libraryId)
                    .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
            
            String libPath = bookFilePersistenceService.findMatchingLibraryPath(library, oldPath);
            LibraryPathEntity libPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libPath);
            
            Path relPath = Paths.get(libPathEntity.getPath()).relativize(oldPath);
            String fileName = relPath.getFileName().toString();
            String fileSubPath = Optional.ofNullable(relPath.getParent()).map(Path::toString).orElse("");
            
            Optional<BookEntity> bookOpt = bookFilePersistenceService.findByLibraryPathSubPathAndFileName(
                    libPathEntity.getId(), fileSubPath, fileName);
            
            if (bookOpt.isPresent()) {
                BookEntity book = bookOpt.get();
                bookFilePersistenceService.updatePathIfChanged(book, library, newPath, hash);
                log.info("[FILE_MOVE] Book {} path updated from '{}' to '{}'", book.getId(), oldPath, newPath);
            } else {
                log.warn("[FILE_MOVE] Book not found for old path '{}', processing as new file", oldPath);
                handleNewBookFile(libraryId, newPath);
            }
        } catch (Exception e) {
            log.error("[FILE_MOVE] Error handling file move from '{}' to '{}': {}", oldPath, newPath, e.getMessage(), e);
            // Fallback: process as new file
            try {
                handleNewBookFile(libraryId, newPath);
            } catch (Exception ex) {
                log.error("[FILE_MOVE] Fallback failed for '{}': {}", newPath, ex.getMessage());
            }
        }
    }
}
