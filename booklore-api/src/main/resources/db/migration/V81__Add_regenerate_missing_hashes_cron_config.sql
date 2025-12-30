-- Add cron configuration for hash regeneration task
INSERT INTO task_cron_configuration (task_type, cron_expression, enabled, created_by)
VALUES ('REGENERATE_MISSING_HASHES', '0 0 * * * *', TRUE, -1); -- Run every hour at minute 0

-- Add last_modified_time column to track file modification times for efficient change detection
ALTER TABLE book ADD COLUMN last_modified_time TIMESTAMP NULL;
