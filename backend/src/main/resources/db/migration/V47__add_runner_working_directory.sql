-- Task 32：Runner 必须在"登记工作区 + 已验证相对子目录"里执行，而不是永远在工作区根目录。
-- 工作目录与工作区路径同属安全边界，因此随执行记录一起持久化并在确认前重新校验。
-- 历史行按工作区根目录回填，语义与改动前一致。
ALTER TABLE runner_executions
    ADD COLUMN working_directory VARCHAR(512) NOT NULL DEFAULT '.';
