INSERT INTO task_cron_configuration (task_type, cron_expression, enabled, created_by)
VALUES ('REGENERATE_MISSING_HASHES', '0 0 * * * *', TRUE, -1); -- Run every hour at minute 0
