package com.adityachandel.booklore.service.file;

import com.adityachandel.booklore.mapper.BookMapper;
import com.adityachandel.booklore.mapper.LibraryMapper;
import com.adityachandel.booklore.model.dto.FileMoveResult;
import com.adityachandel.booklore.model.dto.request.FileMoveRequest;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.entity.LibraryPathEntity;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.BookRepository;
import com.adityachandel.booklore.repository.LibraryRepository;
import com.adityachandel.booklore.service.NotificationService;
import com.adityachandel.booklore.service.monitoring.MonitoringRegistrationService;
import com.adityachandel.booklore.service.watcher.SystemOperationContext;
import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
public class FileMoveService {

    private static final long EVENT_DRAIN_TIMEOUT_MS = 300;

    private final BookRepository bookRepository;
    private final LibraryRepository libraryRepository;
    private final FileMoveHelper fileMoveHelper;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final LibraryMapper libraryMapper;
    private final BookMapper bookMapper;
    private final NotificationService notificationService;
    private final EntityManager entityManager;


    @Transactional
    public void bulkMoveFiles(FileMoveRequest request) {
        List<FileMoveRequest.Move> moves = request.getMoves();
        
        Set<Long> allAffectedLibraryIds = collectAllAffectedLibraryIds(moves);
        Set<Path> libraryPaths = monitoringRegistrationService.getPathsForLibraries(allAffectedLibraryIds);
        
        log.info("Unregistering {} libraries before bulk file move", allAffectedLibraryIds.size());
        monitoringRegistrationService.unregisterLibraries(allAffectedLibraryIds);
        monitoringRegistrationService.waitForEventsDrainedByPaths(libraryPaths, EVENT_DRAIN_TIMEOUT_MS);

        try {
            for (FileMoveRequest.Move move : moves) {
                processSingleMove(move);
            }
        } finally {
            for (Long libraryId : allAffectedLibraryIds) {
                libraryRepository.findById(libraryId)
                        .ifPresent(library -> monitoringRegistrationService.registerLibrary(libraryMapper.toLibrary(library)));
            }
        }
    }

    private Set<Long> collectAllAffectedLibraryIds(List<FileMoveRequest.Move> moves) {
        Set<Long> libraryIds = new HashSet<>();
        
        for (FileMoveRequest.Move move : moves) {
            libraryIds.add(move.getTargetLibraryId());
            bookRepository.findById(move.getBookId())
                    .ifPresent(book -> libraryIds.add(book.getLibrary().getId()));
        }
        
        return libraryIds;
    }

    private void processSingleMove(FileMoveRequest.Move move) {
        Long bookId = move.getBookId();
        Path tempPath = null;
        Path currentFilePath = null;

        try {
            MoveContext context = prepareMoveContext(move);
            if (context == null) {
                return;
            }

            currentFilePath = context.bookEntity.getFullFilePath();
            Path newFilePath = context.newFilePath;

            if (currentFilePath.equals(newFilePath)) {
                return;
            }

            SystemOperationContext.markSystemOperation(currentFilePath);
            SystemOperationContext.markSystemOperation(newFilePath);

            tempPath = fileMoveHelper.moveFileWithBackup(currentFilePath);
            updateBookEntityPath(context, newFilePath);
            fileMoveHelper.commitMove(tempPath, newFilePath);
            tempPath = null;
            
            cleanupOldDirectory(context.bookEntity, currentFilePath);
            notifyBookUpdate(bookId);

        } catch (Exception e) {
            log.error("Error moving file for book ID {}: {}", bookId, e.getMessage(), e);
        } finally {
            if (tempPath != null && currentFilePath != null) {
                fileMoveHelper.rollbackMove(tempPath, currentFilePath);
            }
            SystemOperationContext.clearThreadContext();
        }
    }

    private MoveContext prepareMoveContext(FileMoveRequest.Move move) {
        Optional<BookEntity> optionalBook = bookRepository.findById(move.getBookId());
        Optional<LibraryEntity> optionalLibrary = libraryRepository.findById(move.getTargetLibraryId());

        if (optionalBook.isEmpty()) {
            log.warn("Book not found for move operation: bookId={}", move.getBookId());
            return null;
        }
        if (optionalLibrary.isEmpty()) {
            log.warn("Target library not found for move operation: libraryId={}", move.getTargetLibraryId());
            return null;
        }

        BookEntity bookEntity = optionalBook.get();
        LibraryEntity targetLibrary = optionalLibrary.get();

        Optional<LibraryPathEntity> optionalLibraryPath = targetLibrary.getLibraryPaths().stream()
                .filter(lp -> Objects.equals(lp.getId(), move.getTargetLibraryPathId()))
                .findFirst();

        if (optionalLibraryPath.isEmpty()) {
            log.warn("Target library path not found for move operation: libraryId={}, pathId={}", 
                    move.getTargetLibraryId(), move.getTargetLibraryPathId());
            return null;
        }

        LibraryPathEntity libraryPathEntity = optionalLibraryPath.get();
        String pattern = fileMoveHelper.getFileNamingPattern(targetLibrary);
        Path newFilePath = fileMoveHelper.generateNewFilePath(bookEntity, libraryPathEntity, pattern);

        return new MoveContext(bookEntity, targetLibrary, libraryPathEntity, newFilePath);
    }

    private void updateBookEntityPath(MoveContext context, Path newFilePath) {
        String newFileName = newFilePath.getFileName().toString();
        String newFileSubPath = fileMoveHelper.extractSubPath(newFilePath, context.libraryPathEntity);
        bookRepository.updateFileAndLibrary(
                context.bookEntity.getId(), 
                newFileSubPath, 
                newFileName, 
                context.targetLibrary.getId(), 
                context.libraryPathEntity
        );
    }

    private void cleanupOldDirectory(BookEntity bookEntity, Path currentFilePath) {
        Path libraryRoot = Paths.get(bookEntity.getLibraryPath().getPath()).toAbsolutePath().normalize();
        fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(currentFilePath.getParent(), Set.of(libraryRoot));
    }

    private void notifyBookUpdate(Long bookId) {
        entityManager.clear();
        BookEntity freshBook = bookRepository.findById(bookId).orElseThrow();
        notificationService.sendMessage(Topic.BOOK_UPDATE, bookMapper.toBookWithDescription(freshBook, false));
    }

    private record MoveContext(
            BookEntity bookEntity,
            LibraryEntity targetLibrary,
            LibraryPathEntity libraryPathEntity,
            Path newFilePath
    ) {}

    @Transactional
    public FileMoveResult moveSingleFile(BookEntity bookEntity) {
        Long libraryId = bookEntity.getLibraryPath().getLibrary().getId();
        Path libraryRoot = Paths.get(bookEntity.getLibraryPath().getPath()).toAbsolutePath().normalize();
        boolean isLibraryMonitored = monitoringRegistrationService.isLibraryMonitored(libraryId);

        try {
            String pattern = fileMoveHelper.getFileNamingPattern(bookEntity.getLibraryPath().getLibrary());
            Path currentFilePath = bookEntity.getFullFilePath();
            Path expectedFilePath = fileMoveHelper.generateNewFilePath(bookEntity, bookEntity.getLibraryPath(), pattern);

            if (currentFilePath.equals(expectedFilePath)) {
                return FileMoveResult.builder().moved(false).build();
            }

            log.info("File for book ID {} needs to be moved from {} to {} to match library pattern", 
                    bookEntity.getId(), currentFilePath, expectedFilePath);

            SystemOperationContext.markSystemOperation(currentFilePath);
            SystemOperationContext.markSystemOperation(expectedFilePath);

            if (isLibraryMonitored) {
                unregisterLibraryAndWaitForDrain(libraryId);
            }

            fileMoveHelper.moveFile(currentFilePath, expectedFilePath);
            fileMoveHelper.deleteEmptyParentDirsUpToLibraryFolders(currentFilePath.getParent(), Set.of(libraryRoot));

            return buildSuccessfulMoveResult(expectedFilePath, bookEntity.getLibraryPath());
            
        } catch (Exception e) {
            log.error("Failed to move file for book ID {}: {}", bookEntity.getId(), e.getMessage(), e);
            return FileMoveResult.builder().moved(false).build();
        } finally {
            if (isLibraryMonitored) {
                reregisterLibrary(libraryId, libraryRoot);
            }
            SystemOperationContext.clearThreadContext();
        }
    }

    private void unregisterLibraryAndWaitForDrain(Long libraryId) {
        log.debug("Unregistering library {} before moving a single file", libraryId);
        Set<Path> libraryPaths = monitoringRegistrationService.getPathsForLibraries(Set.of(libraryId));
        fileMoveHelper.unregisterLibrary(libraryId);
        monitoringRegistrationService.waitForEventsDrainedByPaths(libraryPaths, EVENT_DRAIN_TIMEOUT_MS);
    }

    private void reregisterLibrary(Long libraryId, Path libraryRoot) {
        log.debug("Re-registering library paths for library {} with root {}", libraryId, libraryRoot);
        fileMoveHelper.registerLibraryPaths(libraryId, libraryRoot);
    }

    private FileMoveResult buildSuccessfulMoveResult(Path newFilePath, LibraryPathEntity libraryPath) {
        String newFileName = newFilePath.getFileName().toString();
        String newFileSubPath = fileMoveHelper.extractSubPath(newFilePath, libraryPath);
        
        return FileMoveResult.builder()
                .moved(true)
                .newFileName(newFileName)
                .newFileSubPath(newFileSubPath)
                .build();
    }
}
