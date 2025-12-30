package com.adityachandel.booklore.task.tasks;

import com.adityachandel.booklore.model.dto.request.TaskCreateRequest;
import com.adityachandel.booklore.model.dto.response.TaskCreateResponse;
import com.adityachandel.booklore.model.enums.TaskType;
import com.adityachandel.booklore.service.hash.HashMigrationService;
import com.adityachandel.booklore.service.hash.HashMigrationService.HashMigrationResult;
import com.adityachandel.booklore.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RegenerateMissingHashesTask implements Task {

    private final HashMigrationService hashMigrationService;

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .taskType(getTaskType());

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());

        try {
            HashMigrationResult result = hashMigrationService.regenerateMissingHashes(null);
            log.info("{}: Processed {} books, updated {} hashes, {} failed",
                    getTaskType(), result.processed(), result.updated(), result.failed());
            builder.status(TaskStatus.COMPLETED);
        } catch (IllegalStateException e) {
            log.warn("{}: {}", getTaskType(), e.getMessage());
            builder.status(TaskStatus.FAILED);
        } catch (Exception e) {
            log.error("{}: Error regenerating hashes", getTaskType(), e);
            builder.status(TaskStatus.FAILED);
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        return builder.build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.REGENERATE_MISSING_HASHES;
    }

    @Override
    public String getMetadata() {
        long missing = hashMigrationService.getMigrationStats().booksMissingHashes();
        return "Book files without hashes: " + missing;
    }
}
