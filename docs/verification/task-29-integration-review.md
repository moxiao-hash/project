# Task 29 合并代码验收（2026-09-10）

当前结论：自动化验收通过，真实运行验收未完成，暂不放行 Task 30。

## 代码范围

- 后端 `37ac851` 与前端 `1922ea4` 已在独立 `codex/task-29-integration` 分支合并。
- 工作树：`/Users/moxiao/IdeaProjects/project-task29-review`。main 尚未合并；原两个交付分支未修改。
- Codex 新增回归：正常 TURN_COMPLETED 先到、POST 完整快照后到时，引用及降级警告必须显示。
- RED：新增测试失败，缺少“Java 官方来源”；其余15个组件测试通过。
- GREEN：改为按最新请求代际接收本轮完整快照，仍拒绝失败/取消及旧轮结果。保留已有旧请求竞态测试。

## 本轮实际验证

- Java Maven：379 tests，0 failures/errors/skipped。
- Python pytest：401 passed，1条已有Starlette弃用警告；Ruff通过。
- Vue Vitest：143 passed；生产构建（含vue-tsc）通过。
- 能力矩阵：31页面、64工具；git diff --check通过。

## 真实运行检查及限制

- 当前8080及8000健康接口UP，Java跨域Last-Event-ID预检200。
- 创建独立Task29测试账户与会话，注册/创建/导航消息均成功；未输出账户密码或Token。
- **运行中会话响应缺少lastEventSequence、activeTurnId、activeTurn三个字段**，当前服务未加载本轮交付。此次仅证明旧运行实例可用，不证明新恢复协议通过。
- 事件探针收到一条事件与心跳；未验证完整新轮次链路，不宣称真实SSE联调通过。
- 本次独立验收账户保留用于追溯，未改用户正式学习数据。

## 下一步

运行独立验收分支的新Java/Python/Vue版本，执行30秒心跳、生成中刷新、断线续传、重复连接、取消和Python重启；核对消息、引用、游标、执行次数。通过后才能合并main并调度Task30。
