package com.adityachandel.booklore.service.watcher;

import com.adityachandel.booklore.exception.ApiError;
import com.adityachandel.booklore.model.entity.BookEntity;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.entity.LibraryPathEntity;
import com.adityachandel.booklore.model.enums.BookFileExtension;
import com.adityachandel.booklore.model.enums.PermissionType;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.LibraryRepository;
import com.adityachandel.booklore.service.file.FileFingerprint;
import com.adityachandel.booklore.service.NotificationService;
import com.adityachandel.booklore.util.FileUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

@Slf4j
@Service
@AllArgsConstructor
public class LibraryFileEventProcessor {

    private static final long DEBOUNCE_MS = 500L;
    private static final long RENAME_DETECTION_WINDOW_MS = 200L;

    private final BlockingQueue<FileEvent> eventQueue = new LinkedBlockingQueue<>();
    private final LibraryRepository libraryRepository;
    private final BookFileTransactionalHandler bookFileTransactionalHandler;
    private final BookFilePersistenceService bookFilePersistenceService;
    private final NotificationService notificationService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConcurrentMap<Path, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RecentCreate> recentCreates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        Thread.ofVirtual().start(() -> {
            log.info("LibraryFileEventProcessor virtual thread started.");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    handleEvent(eventQueue.take());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("LibraryFileEventProcessor virtual thread interrupted.");
                } catch (Exception e) {
                    log.error("Error while processing file event", e);
                }
            }
        });
    }

    public void processFile(WatchEvent.Kind<?> eventKind, long libraryId, String libraryPath, String filePath) {
        Path path = Paths.get(filePath).toAbsolutePath().normalize();

        if (eventKind == StandardWatchEventKinds.ENTRY_DELETE) {
            handleDeleteEvent(path, eventKind, libraryId, libraryPath, filePath);
        } else if (eventKind == StandardWatchEventKinds.ENTRY_CREATE) {
            handleCreateEvent(path, eventKind, libraryId, libraryPath, filePath);
        } else if (eventKind == StandardWatchEventKinds.ENTRY_MODIFY) {
            handleModifyEvent(path, libraryId, libraryPath, filePath);
        } else {
            eventQueue.offer(new FileEvent(eventKind, libraryId, libraryPath, filePath));
        }
    }

    private void handleDeleteEvent(Path path, WatchEvent.Kind<?> eventKind, long libraryId, String libraryPath, String filePath) {
        String fileName = path.getFileName().toString();
        
        if (!isBookFile(fileName)) {
            scheduleDelete(path, eventKind, libraryId, libraryPath, filePath, null);
            return;
        }
        
        String hash = getBookHashForPath(libraryId, path);
        
        if (hash != null && !hash.isEmpty()) {
            Optional<RecentCreate> matchingCreate = findRecentCreateByHash(hash);
            if (matchingCreate.isPresent()) {
                RecentCreate createEvent = matchingCreate.get();
                recentCreates.remove(createEvent.hash());
                log.info("[RENAME_DETECTED] File renamed from '{}' to '{}' via hash '{}'", 
                        path, createEvent.path(), hash);
                handleFileMove(libraryId, path, createEvent.path(), hash);
                return;
            }
        }
        
        scheduleDelete(path, eventKind, libraryId, libraryPath, filePath, hash);
    }

    private void handleCreateEvent(Path path, WatchEvent.Kind<?> eventKind, long libraryId, String libraryPath, String filePath) {
        PendingDelete pendingDelete = pendingDeletes.remove(path);
        if (pendingDelete != null) {
            pendingDelete.future().cancel(false);
            log.debug("[DEBOUNCE] CREATE ignored because pending DELETE exists for same path '{}'", path);
            return;
        }
        
        String hash = calculateHashSafely(path);
        if (hash != null && !hash.isEmpty() && isBookFile(path.getFileName().toString())) {
            Optional<Map.Entry<Path, PendingDelete>> matchingDelete = findPendingDeleteByHash(hash);
            if (matchingDelete.isPresent()) {
                Path oldPath = matchingDelete.get().getKey();
                PendingDelete pending = matchingDelete.get().getValue();
                pendingDeletes.remove(oldPath);
                pending.future().cancel(false);
                log.info("[RENAME_DETECTED] File renamed from '{}' to '{}' via hash '{}'", oldPath, path, hash);
                handleFileMove(libraryId, oldPath, path, hash);
                return;
            }
            
            storeRecentCreate(path, hash);
        }
        
        eventQueue.offer(new FileEvent(eventKind, libraryId, libraryPath, filePath));
    }

    private void handleModifyEvent(Path path, long libraryId, String libraryPath, String filePath) {
        String fileName = path.getFileName().toString();
        if (!isBookFile(fileName)) {
            return;
        }
        
        schedulePendingModify(path, libraryId, libraryPath, filePath);
    }

    private final ConcurrentMap<Path, ScheduledFuture<?>> pendingModifies = new ConcurrentHashMap<>();

    private void schedulePendingModify(Path path, long libraryId, String libraryPath, String filePath) {
        ScheduledFuture<?> existing = pendingModifies.remove(path);
        if (existing != null) {
            existing.cancel(false);
        }
        
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingModifies.remove(path);
            eventQueue.offer(new FileEvent(StandardWatchEventKinds.ENTRY_MODIFY, libraryId, libraryPath, filePath));
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        
        pendingModifies.put(path, future);
    }

    private void scheduleDelete(Path path, WatchEvent.Kind<?> eventKind, long libraryId, String libraryPath, String filePath, String hash) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            eventQueue.offer(new FileEvent(eventKind, libraryId, libraryPath, filePath));
            pendingDeletes.remove(path);
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        
        PendingDelete existing = pendingDeletes.put(path, new PendingDelete(future, hash));
        if (existing != null) {
            existing.future().cancel(false);
        }
    }

    private void storeRecentCreate(Path path, String hash) {
        RecentCreate recentCreate = new RecentCreate(path, hash, System.currentTimeMillis());
        recentCreates.put(hash, recentCreate);
        
        scheduler.schedule(() -> {
            RecentCreate stored = recentCreates.get(hash);
            if (stored != null && stored.timestamp() == recentCreate.timestamp()) {
                recentCreates.remove(hash);
            }
        }, RENAME_DETECTION_WINDOW_MS, TimeUnit.MILLISECONDS);
    }

    private Optional<RecentCreate> findRecentCreateByHash(String hash) {
        RecentCreate recent = recentCreates.get(hash);
        if (recent != null && (System.currentTimeMillis() - recent.timestamp()) <= RENAME_DETECTION_WINDOW_MS) {
            return Optional.of(recent);
        }
        return Optional.empty();
    }

    private Optional<Map.Entry<Path, PendingDelete>> findPendingDeleteByHash(String hash) {
        return pendingDeletes.entrySet().stream()
                .filter(entry -> hash.equals(entry.getValue().hash()))
                .findFirst();
    }

    private String getBookHashForPath(long libraryId, Path path) {
        try {
            LibraryEntity library = libraryRepository.findById(libraryId).orElse(null);
            if (library == null) return null;
            
            String libPath = bookFilePersistenceService.findMatchingLibraryPath(library, path);
            LibraryPathEntity libPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libPath);
            
            Path relPath = Paths.get(libPathEntity.getPath()).relativize(path);
            String fileName = relPath.getFileName().toString();
            String fileSubPath = Optional.ofNullable(relPath.getParent()).map(Path::toString).orElse("");
            
            Optional<BookEntity> bookOpt = bookFilePersistenceService.findByLibraryPathSubPathAndFileName(
                    libPathEntity.getId(), fileSubPath, fileName);
            
            return bookOpt.map(BookEntity::getCurrentHash).orElse(null);
        } catch (Exception e) {
            log.debug("Could not retrieve hash for path '{}': {}", path, e.getMessage());
            return null;
        }
    }

    private String calculateHashSafely(Path path) {
        try {
            if (Files.exists(path)) {
                return FileFingerprint.generateHash(path);
            }
        } catch (Exception e) {
            log.debug("Could not calculate hash for path '{}': {}", path, e.getMessage());
        }
        return null;
    }

    private void handleFileMove(long libraryId, Path oldPath, Path newPath, String hash) {
        bookFileTransactionalHandler.handleFileMove(libraryId, oldPath, newPath, hash);
    }

    private void handleEvent(FileEvent event) {
        Path path = Paths.get(event.filePath()).toAbsolutePath().normalize();
        String fileName = path.getFileName().toString();
        log.info("[PROCESS] '{}' event for '{}'", event.eventKind().name(), fileName);

        if (SystemOperationContext.isSystemOperation(path)) {
            log.debug("[SKIP] System operation in progress for: '{}'", path);
            return;
        }

        LibraryEntity library = libraryRepository.findById(event.libraryId())
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(event.libraryId()));

        if (library.getLibraryPaths().stream().noneMatch(lp -> path.startsWith(lp.getPath()))) {
            log.warn("[SKIP] Path outside of library: '{}'", path);
            return;
        }

        if (isFolder(path)) {
            switch (event.eventKind().name()) {
                case "ENTRY_CREATE" -> handleFolderCreate(library, path);
                case "ENTRY_DELETE" -> handleFolderDelete(library, path);
                default -> log.warn("[SKIP] Folder event '{}' ignored for '{}'", event.eventKind().name(), fileName);
            }
            return;
        }

        if (!isBookFile(fileName)) {
            log.debug("[SKIP] Ignored non-book file '{}'", fileName);
            return;
        }

        switch (event.eventKind().name()) {
            case "ENTRY_CREATE" -> handleFileCreate(library, path);
            case "ENTRY_DELETE" -> handleFileDelete(library, path);
            case "ENTRY_MODIFY" -> handleFileModify(library, path);
            default -> log.debug("[SKIP] File event '{}' ignored for '{}'", event.eventKind().name(), fileName);
        }
    }

    private void handleFileCreate(LibraryEntity library, Path path) {
        log.info("[FILE_CREATE] '{}'", path);
        bookFileTransactionalHandler.handleNewBookFile(library.getId(), path);
    }

    private void handleFileDelete(LibraryEntity library, Path path) {
        log.info("[FILE_DELETE] '{}'", path);
        try {
            String libPath = bookFilePersistenceService.findMatchingLibraryPath(library, path);
            LibraryPathEntity libPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libPath);

            Path relPath = Paths.get(libPathEntity.getPath()).relativize(path);
            String fileName = relPath.getFileName().toString();
            String fileSubPath = Optional.ofNullable(relPath.getParent()).map(Path::toString).orElse("");

            bookFilePersistenceService.findByLibraryPathSubPathAndFileName(libPathEntity.getId(), fileSubPath, fileName)
                    .ifPresentOrElse(book -> {
                        book.setDeleted(true);
                        bookFilePersistenceService.save(book);
                        notificationService.sendMessageToPermissions(Topic.BOOKS_REMOVE, Set.of(book.getId()),
                                Set.of(PermissionType.ADMIN, PermissionType.MANAGE_LIBRARY));
                        log.info("[MARKED_DELETED] Book '{}' marked as deleted", fileName);
                    }, () -> log.warn("[NOT_FOUND] Book for deleted path '{}' not found", path));

        } catch (Exception e) {
            log.warn("[ERROR] While handling file delete '{}': {}", path, e.getMessage());
        }
    }

    private void handleFileModify(LibraryEntity library, Path path) {
        log.info("[FILE_MODIFY] '{}'", path);
        try {
            String libPath = bookFilePersistenceService.findMatchingLibraryPath(library, path);
            LibraryPathEntity libPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libPath);

            Path relPath = Paths.get(libPathEntity.getPath()).relativize(path);
            String fileName = relPath.getFileName().toString();
            String fileSubPath = Optional.ofNullable(relPath.getParent()).map(Path::toString).orElse("");

            bookFilePersistenceService.findByLibraryPathSubPathAndFileName(libPathEntity.getId(), fileSubPath, fileName)
                    .ifPresentOrElse(book -> {
                        String newHash = FileFingerprint.generateHash(path);
                        Instant newMtime = getLastModifiedTime(path);
                        long newSize = getFileSizeKb(path);
                        
                        boolean hashChanged = newHash != null && !newHash.equals(book.getCurrentHash());
                        boolean mtimeChanged = newMtime != null && !newMtime.equals(book.getLastModifiedTime());
                        boolean sizeChanged = newSize != book.getFileSizeKb();
                        
                        if (hashChanged || mtimeChanged || sizeChanged) {
                            book.setCurrentHash(newHash);
                            book.setLastModifiedTime(newMtime);
                            book.setFileSizeKb(newSize);
                            bookFilePersistenceService.save(book);
                            log.info("[FILE_MODIFIED] Book '{}' updated: hash={}, mtime={}, size={}", 
                                    fileName, hashChanged, mtimeChanged, sizeChanged);
                        } else {
                            log.debug("[FILE_UNCHANGED] Book '{}' no changes detected", fileName);
                        }
                    }, () -> log.debug("[NOT_FOUND] Book for modified path '{}' not found", path));

        } catch (Exception e) {
            log.warn("[ERROR] While handling file modify '{}': {}", path, e.getMessage());
        }
    }

    private Instant getLastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (IOException e) {
            return null;
        }
    }

    private long getFileSizeKb(Path path) {
        try {
            return Files.size(path) / 1024;
        } catch (IOException e) {
            return 0;
        }
    }

    private void handleFolderCreate(LibraryEntity library, Path folderPath) {
        log.info("[FOLDER_CREATE] '{}'", folderPath);
        try (var stream = Files.walk(folderPath)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isBookFile(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            bookFileTransactionalHandler.handleNewBookFile(library.getId(), p);
                        } catch (Exception e) {
                            log.warn("[ERROR] Processing file '{}': {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[ERROR] Walking folder '{}': {}", folderPath, e.getMessage());
        }
    }

    private void handleFolderDelete(LibraryEntity library, Path folderPath) {
        log.info("[FOLDER_DELETE] '{}'", folderPath);
        try {
            String libPath = bookFilePersistenceService.findMatchingLibraryPath(library, folderPath);
            LibraryPathEntity libPathEntity = bookFilePersistenceService.getLibraryPathEntityForFile(library, libPath);

            String relativePrefix = FileUtils.getRelativeSubPath(libPathEntity.getPath(), folderPath);
            int count = bookFilePersistenceService.markAllBooksUnderPathAsDeleted(libPathEntity.getId(), relativePrefix);
            log.info("[MARKED_DELETED] {} books under '{}'", count, folderPath);
        } catch (Exception e) {
            log.warn("[ERROR] Folder delete '{}': {}", folderPath, e.getMessage());
        }
    }

    private boolean isFolder(Path path) {
        return !path.getFileName().toString().contains(".");
    }

    private boolean isBookFile(String fileName) {
        return BookFileExtension.fromFileName(fileName).isPresent();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        log.info("Shutting down LibraryFileEventProcessor...");
    }

    public record FileEvent(WatchEvent.Kind<?> eventKind, long libraryId, String libraryPath, String filePath) {
    }

    private record PendingDelete(ScheduledFuture<?> future, String hash) {
    }

    private record RecentCreate(Path path, String hash, long timestamp) {
    }
}