-- Task 31 修复：预占许可必须包含当前请求的输入 token 上界。
-- 只按输出上限预占时，输入成本完全不计入，并发调用可以一起跨过日费用上限；
-- 费用上限生效时若拿不到输入上界，服务层会失败关闭而不是按 0 预占。
ALTER TABLE assistant_usage_reservation
    ADD COLUMN input_tokens_upper_bound INT NULL;
