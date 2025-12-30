package com.adityachandel.booklore.service.library;

import com.adityachandel.booklore.exception.ApiError;
import com.adityachandel.booklore.model.dto.settings.LibraryFile;
import com.adityachandel.booklore.model.entity.LibraryEntity;
import com.adityachandel.booklore.model.websocket.LogNotification;
import com.adityachandel.booklore.model.websocket.Topic;
import com.adityachandel.booklore.repository.LibraryRepository;
import com.adityachandel.booklore.service.NotificationService;
import com.adityachandel.booklore.task.options.RescanLibraryContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@AllArgsConstructor
@Service
@Slf4j
public class LibraryProcessingService {

    private final LibraryRepository libraryRepository;
    private final NotificationService notificationService;
    private final LibraryFileProcessorRegistry fileProcessorRegistry;
    private final LibraryFileHelper libraryFileHelper;
    private final LibraryRescanOperations rescanOperations;

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

    public void rescanLibrary(RescanLibraryContext context) throws IOException {
        long libraryId = context.getLibraryId();
        String libraryName = libraryRepository.findById(libraryId)
                .map(LibraryEntity::getName)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        notificationService.sendMessage(Topic.LOG, LogNotification.info("Started refreshing library: " + libraryName));
        
        LibraryEntity libraryEntity = libraryRepository.findById(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        LibraryFileProcessor processor = fileProcessorRegistry.getProcessor(libraryEntity);
        List<LibraryFile> diskFiles = libraryFileHelper.getLibraryFiles(libraryEntity, processor);
        
        rescanOperations.handleDeletions(diskFiles, libraryId);
        rescanOperations.handleRestorations(diskFiles, libraryId);
        List<LibraryFile> filesToProcess = rescanOperations.categorizeAndUpdateExistingFiles(diskFiles, libraryId);
        
        if (!filesToProcess.isEmpty()) {
            LibraryEntity freshLibrary = libraryRepository.findById(libraryId)
                    .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
            processor.processLibraryFiles(filesToProcess, freshLibrary);
        }

        notificationService.sendMessage(Topic.LOG, LogNotification.info("Finished refreshing library: " + libraryName));
    }

    public void processLibraryFiles(List<LibraryFile> libraryFiles, LibraryEntity libraryEntity) {
        LibraryFileProcessor processor = fileProcessorRegistry.getProcessor(libraryEntity);
        processor.processLibraryFiles(libraryFiles, libraryEntity);
    }
}
