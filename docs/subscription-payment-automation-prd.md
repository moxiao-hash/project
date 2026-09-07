# StudyPilot 订阅制与自动化收款履约系统需求规格说明书 (PRD)

> **版本**：v1.0 (Draft Planning)  
> **日期**：2026-09-05  
> **阶段**：第一版架构规划与接口设计（不改动系统源码，仅作为后续研发蓝图）  
> **目标项目**：StudyPilot 学习平台（Spring Boot + MySQL + Vue 3 + FastAPI AI）

---

## 1. 项目背景与业务诉求

### 1.1 业务背景
StudyPilot 目前核心聚焦在高质量的 Java + AI 体系化学习闭环（包含 12 阶段 64 节点学习路线、交互式讲义、错题本复习、课内 AI 导师与代码评测）。
随着平台服务能力的扩展，调用外部高阶大语言模型（如 DeepSeek、深度代码诊断、长上下文检索等）带来了一定的算力成本。同时为了满足不同用户的深度学习需求，系统需要引入**商业化订阅制（Subscription）与增值服务体系**。

### 1.2 核心业务诉求
1. **订阅套餐体系化**：支持不同周期（如月卡、季卡、年卡）或按需算力包（Token 加油包）的多级定价与权益映射。
2. **自动化收款与状态感知**：解决个人开发者或中小型平台在微信/支付宝等渠道收款时，从“用户支付”到“系统感知”的自动化检测痛点。
3. **自动履约开通**：后台检测到用户完成付款后，基于安全验签与幂等性保证，毫秒级为用户自动开通/续期相应套餐，无需人工干预。
4. **掉单补偿与容灾兜底**：网络抖动、微信风控或监听端偶发离线时，具备自动补单与人工工单快速核验机制。
5. **无侵入设计**：现阶段保持源码不动，优先输出完整的业务状态机、技术选型对比、数据模型与对外/对内接口契约。

---

## 2. 订阅套餐体系与权益矩阵设计

### 2.1 套餐层级规划
系统将用户划分为以下等级：

| 套餐级别 | 计费周期 | 参考定价 | 核心权益矩阵 |
| :--- | :--- | :--- | :--- |
| **Free (免费版)** | 永久 | ¥0 | 默认基础学习路线、前 2 阶段课程节点、基础多选测验、每日 10 次基础 AI 导师提问 |
| **Pro Monthly (专业月卡)** | 30 天 | ¥29 / 月 | 解锁全部 12 阶段高阶路线、无限次课内 AI 导师答疑、错题本重做、代码评测诊断 |
| **Pro Quarterly (专业季卡)** | 90 天 | ¥69 / 季 | 享有月卡全部特权 + 额外赠送 10 万高阶 Agent 思考 Token + 优先客服响应 |
| **Pro Yearly (专业年卡)** | 365 天 | ¥199 / 年 | 享有最高学习特权 + 年度路线更新优先内测 + 离线资料包打包下载 + 专属社群交流 |
| **Token Pack (算力加油包)** | 永久有效 | ¥19 / 50万 Token | 不限时长的额外模型算力补充，适用于高频使用复杂自治 Agent 规划的用户 |

### 2.2 权益校验与拦截机制
在架构层面，用户发起受限操作时，系统通过切面或网关层进行订阅鉴权：
1. **路线节点解锁校验**：Spring Boot 拦截请求，检查用户当前有效订阅状态；若为免费用户访问阶段 3 及以上节点，则返回 `403 Entitlement Required` 并附带升级套餐提示。
2. **AI 导师与评测限流**：每天凌晨重置免费用户的每日配额；对于 Pro 用户放开频次限制；高消耗的复杂规划功能扣减用户账户的 Token 额度。

### 2.3 订阅周期与顺延规则
- **连续续费**：用户在当前有效 Pro 期间再次购买同等级套餐，新到期时间自动在原到期时间基础上顺延（`new_expire_time = current_expire_time + plan_days`）。
- **过期降级**：当用户订阅到期后，系统自动将其标记为 `EXPIRED`，用户自动回退到 Free 权限，已学历史与错题记录永久保留，不删除用户数据。

---

## 3. 收款检测与自动化方案对比剖析

针对不同业务发展阶段与资质门槛，本系统规划设计 **4 种收款与状态检测方案**，支持在配置中心无缝切换：

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                            StudyPilot 支付通道方案选型                     │
├─────────────────┬───────────────────┬──────────────────┬──────────────────┤
│ 方案 A: 微信官方商户 │ 方案 B: 个人免签监听 │ 方案 C: 聚合服务商 │ 方案 D: 卡密/手动核销│
├─────────────────┼───────────────────┼──────────────────┼──────────────────┤
│ • 官方 Native API │ • 安卓备用机挂机监听 │ • PayJS / 虎皮椒 │ • 管理员批量生成卡密 │
│ • 资质要求：高(执照)│ • 资质要求：零(个人码)│ • 资质要求：低(个人)│ • 资质要求：零       │
│ • 稳定性：极高(秒级)│ • 稳定性：受手机保活 │ • 稳定性：高(云端回调)│ • 稳定性：100% 兜底  │
│ • 费率：0.38%~0.6%│ • 费率：0% (直接入账)│ • 费率：2%~5% 抽成 │ • 费率：0%           │
└─────────────────┴───────────────────┴──────────────────┴──────────────────┘
```

### 3.1 方案 A：微信支付官方商户 API（首选标准方案）
- **运行机制**：
  1. StudyPilot 后端调用微信支付 V3 Native 下单接口，获取支付链接 `code_url`。
  2. 前端根据 `code_url` 生成动态二维码展示给用户。
  3. 用户使用手机微信扫码支付完成。
  4. 微信服务器向 StudyPilot 预留的公网回调地址发送加密的异步通知（Webhook）。
  5. 后端验签解密后更新订单状态并自动为用户开通套餐。
- **优点**：官方正规、合规无风险、到账实时回调、支持原路自动退款。
- **前置条件**：需个体工商户或企业营业执照、申请微信支付商户号，需配置公网域名与 SSL 证书。

### 3.2 方案 B：个人微信收款码/赞赏码 + 客户端通知监听（极具可行性的个人开发者方案）
- **运行机制**：
  1. **金额随机微调（防并发碰撞）**：例如月卡标价 29.00 元。用户 A 下单时实付金额设为 29.01 元，用户 B 下单时设为 29.02 元（有效支付窗口 5 分钟，过期释放金额占用）。
  2. **挂机端监听 Push 通知**：在一台闲置 Android 备用机上安装通知监听 App（利用 Android `NotificationListenerService` 系统服务）。
  3. **实时拦截收款通知**：当个人微信收到转账或赞赏码到账时，系统产生通知栏消息（如：“微信支付收款 29.01 元”）。
  4. **加密上报服务器**：监听 App 提取金额与时间戳，携带 HMAC 预共享密钥，通过 HTTPS 上报至 StudyPilot 后端专用回调网关。
  5. **自动匹配履约**：后端通过 `金额(29.01) + 5分钟有效时间窗口` 准确定位唯一待支付订单，立即触发套餐开通。
- **优点**：无需营业执照，个人微信直接收款，资金秒进个人微信零钱，无第三方扣点。
- **技术难点与保障**：
  - 安卓机需开启常亮/前台保活与无障碍权限，避免系统杀后台。
  - 必须有金额并发锁，同一时间内不能出现两个金额完全相同的未支付订单。
  - 需提供掉单申诉补单页面作为兜底。

### 3.3 方案 C：第三方聚合免签服务商（如 PayJS、虎皮椒、爱发电等）
- **运行机制**：
  - 接入专门支持个人开发者的聚合支付云服务平台。
  - 平台提供标准统一的 API 生成收款二维码，并在用户扫码支付后由平台服务器回调 StudyPilot Webhook。
- **优点**：无需自己购买挂机安卓机，接入简单，云端稳定托管。
- **注意点**：需要交纳服务商开户费或支付 2%~5% 手续费。

### 3.4 方案 D：卡密兑换码 (CDKEY) 与线下核验（强力兜底与推广机制）
- **运行机制**：
  - 管理员在后台生成带有一串加密防伪字符的卡密（如 `SP-PRO-30D-8F92A1`）。
  - 用户线下转账（如加开发者微信转账）或淘宝发卡平台拍下后，在个人中心输入卡密即可瞬间秒级开通。
- **优点**：100% 独立自主，无任何外部接口依赖，也是系统出现网络故障时最稳妥的应急手段。

---

## 4. 支付自动化时序图与状态机设计

### 4.1 核心支付与履约时序图

```text
┌──────┐             ┌────────────┐            ┌──────────────────┐           ┌──────────────────┐
│ 用户 │             │ Web 前端   │            │ Spring Boot 后端 │           │ 收款渠道 / 监听端 │
└──┬───┘             └─────┬──────┘            └────────┬─────────┘           └────────┬─────────┘
   │                       │                            │                              │
   │ 1. 点击购买 Pro 套餐   │                            │                              │
   ├──────────────────────>│                            │                              │
   │                       │ 2. POST /orders 下单       │                              │
   │                       ├───────────────────────────>│                              │
   │                       │                            │ 3. 创建订单并锁定金额微调       │
   │                       │                            │    (例如 29.01 元, 倒计时5分)  │
   │                       │ 4. 返回支付二维码 & 订单号 │                              │
   │                       │<───────────────────────────┤                              │
   │ 5. 用户手机扫码付款   │                            │                              │
   ├──────────────────────────────────────────────────────────────────────────────────>│
   │                       │ 6. 前端开始轮询状态         │                              │
   │                       ├───────────────────────────>│                              │
   │                       │    (GET /orders/{id}/status)                              │
   │                       │                            │                              │
   │                       │                            │ 7. 收到到账通知(Webhook/Push)│
   │                       │                            │<─────────────────────────────┤
   │                       │                            │ 8. 验签 + 幂等校验           │
   │                       │                            │ 9. 更新订单为 PAID           │
   │                       │                            │ 10. 事务内顺延用户订阅到期日 │
   │                       │                            │ 11. 发送站内成功通知         │
   │                       │ 12. 轮询检测到 SUCCESS     │                              │
   │                       │<───────────────────────────┤                              │
   │ 13. 页面弹窗升级成功  │                            │                              │
   │<──────────────────────┤                            │                              │
```

### 4.2 订单状态机流转设计

```text
               ┌──────────────┐
               │   CREATED    │
               └──────┬───────┘
                      │ 用户点击支付/进入支付态
                      ▼
               ┌──────────────┐ 超时未支付(>5分钟)   ┌──────────────┐
               │   PENDING    ├─────────────────────>│   EXPIRED    │
               └──────┬───────┘                      └──────────────┘
                      │ 收到有效付款通知
                      ▼
               ┌──────────────┐ 权益开通失败(异常)   ┌──────────────┐
               │     PAID     ├─────────────────────>│ FULFILL_FAIL │ (告警并人工接入)
               └──────┬───────┘                      └──────────────┘
                      │ 权益发放完毕
                      ▼
               ┌──────────────┐ 退款/撤销
               │  FULFILLED   ├─────────────────────>┌──────────────┐
               └──────────────┘                      │   REFUNDED   │
                                                     └──────────────┘
```

---

## 5. 开放与调用接口规约 (API Specifications)

以下为建议开放调用的完整 RESTful API 清单，分为 **用户侧接口**、**支付通道回调接口**、**管理端运营接口** 三大分类。

### 5.1 用户侧前端接口 (Client APIs)

#### (1) 获取所有可用订阅套餐
- **Method / Path**: `GET /api/v1/subscription/plans`
- **鉴权**: 允许匿名或携带用户 JWT
- **响应体示例**:
```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": [
    {
      "planId": "plan_pro_monthly",
      "name": "Pro 专业月卡",
      "description": "解锁全部路线与无限 AI 答疑",
      "priceCents": 2900,
      "durationDays": 30,
      "badge": "热门精选",
      "features": [
        "解锁全部 12 阶段 64 节点路线",
        "无限次课内 AI 导师追问",
        "错题本反复诊断与练习"
      ]
    },
    {
      "planId": "plan_pro_yearly",
      "name": "Pro 专业年卡",
      "description": "最划算的长效进阶方案",
      "priceCents": 19900,
      "durationDays": 365,
      "badge": "立省 50%",
      "features": [
        "享有月卡全部权益",
        "赠送 20 万自治 Agent 算力 Token",
        "离线资料包一键打包导出"
      ]
    }
  ]
}
```

#### (2) 查询当前用户的订阅状态
- **Method / Path**: `GET /api/v1/subscription/my-status`
- **鉴权**: 需要用户登录 JWT
- **响应体示例**:
```json
{
  "code": "SUCCESS",
  "data": {
    "userId": "usr_9527",
    "tier": "PRO",
    "isActive": true,
    "expireAt": "2026-10-05T23:59:59Z",
    "remainingDays": 30,
    "aiTokensBalance": 100000,
    "planName": "Pro 专业月卡"
  }
}
```

#### (3) 创建订阅支付订单 (统一收银台下单)
- **Method / Path**: `POST /api/v1/subscription/orders`
- **鉴权**: 需要用户登录 JWT
- **请求体**:
```json
{
  "planId": "plan_pro_monthly",
  "channel": "WECHAT_NATIVE"  // WECHAT_NATIVE (官方扫码), AGGREGATOR (聚合), PERSONAL_LISTEN (个人码免签)
}
```
- **响应体**:
```json
{
  "code": "SUCCESS",
  "data": {
    "orderId": "ORD_20260905_10001",
    "planId": "plan_pro_monthly",
    "originalAmountCents": 2900,
    "actualAmountCents": 2901,  // 若为免签模式，返回微调后的金额 29.01
    "qrCodeUrl": "weixin://wxpay/bizpayurl?pr=xxxxxx", // 或直接返回收款码图片地址
    "expireSeconds": 300,        // 5分钟倒计时
    "channel": "WECHAT_NATIVE"
  }
}
```

#### (4) 前端主动轮询订单支付结果
- **Method / Path**: `GET /api/v1/subscription/orders/{orderId}/status`
- **鉴权**: 需要用户登录 JWT (限订单所属人)
- **响应体**:
```json
{
  "code": "SUCCESS",
  "data": {
    "orderId": "ORD_20260905_10001",
    "status": "PAID", // CREATED, PENDING, PAID, FULFILLED, EXPIRED
    "isFulfilled": true,
    "paidAt": "2026-09-05T14:22:10Z"
  }
}
```

#### (5) 卡密兑换激活
- **Method / Path**: `POST /api/v1/subscription/redeem`
- **鉴权**: 需要用户登录 JWT
- **请求体**:
```json
{
  "cdkey": "SP-PRO-30D-ABCD-EFGH"
}
```
- **响应体**:
```json
{
  "code": "SUCCESS",
  "message": "兑换成功，已成功开通 30 天 Pro 专业月卡",
  "data": {
    "newExpireAt": "2026-10-05T23:59:59Z"
  }
}
```

---

### 5.2 支付通道通知与回调接口 (Payment Webhook & Callback APIs)

#### (1) 微信官方支付异步通知 (V3 Webhook)
- **Method / Path**: `POST /api/v1/webhooks/payments/wechat`
- **鉴权方式**: 微信公钥证书签名验证（HTTP Header 包含 `Wechatpay-Signature` 等）
- **处理逻辑**: 
  1. 读取 Request Body 密文，使用商户 APIv3 密钥解密成 JSON。
  2. 提取 `out_trade_no`、`transaction_id`、`trade_state`、`amount.total`。
  3. 执行幂等性履约操作。
  4. 向微信返回 `{"code": "SUCCESS", "message": "成功"}`，若验签失败返回 4xx/5xx 触发微信阶梯重试。

#### (2) 个人安卓机监听上报专用通道 (免签监控网关)
- **Method / Path**: `POST /api/v1/webhooks/payments/notify-listener`
- **鉴权方式**: 预共享密钥 (PSK) HMAC-SHA256 签名，附带设备唯一指纹与时间戳防重放。
- **请求 Header**:
  - `X-Listener-Device`: `Pixel4_Agent_01`
  - `X-Listener-Timestamp`: `1757053330`
  - `X-Listener-Signature`: `d41d8cd98f00b204e9800998ecf8427e...`
- **请求体**:
```json
{
  "channel": "WECHAT",       // WECHAT 或 ALIPAY
  "moneyCents": 2901,        // 识别到的收款金额：29.01元 = 2901分
  "rawNoticeTitle": "微信支付",
  "rawNoticeContent": "微信支付收款29.01元(朋友到账)",
  "deviceTime": 1757053328000
}
```
- **处理逻辑**:
  1. 校验时间戳在当前服务器时间 ±60 秒内，防止重放。
  2. 校验 HMAC 签名无误。
  3. 检索数据库在最近 5 分钟内创建且处于 `PENDING` 状态、`actual_amount_cents == 2901` 的订单。
  4. 若匹配成功，原子性加锁更新订单为 `PAID`，开通套餐。
  5. 若未匹配到待付订单，将此流水记录至 `unmatched_transactions` 待人工或延迟认领。

#### (3) 聚合支付服务商回调 (Aggregator Webhook)
- **Method / Path**: `POST /api/v1/webhooks/payments/aggregator`
- **鉴权方式**: 按照对应聚合服务商的 MD5/SHA256 验签算法验证。

---

### 5.3 管理端运营与治理接口 (Admin APIs)

#### (1) 订单全景检索与监控
- **Method / Path**: `GET /api/v1/admin/subscription/orders`
- **鉴权**: 仅限拥有 `ROLE_ADMIN` 权限的管理员
- **参数**: 支持根据 `userId`、`status`、`dateRange`、`channel` 分页检索。

#### (2) 异常掉单人工手动履约开通
- **Method / Path**: `POST /api/v1/admin/subscription/orders/{orderId}/manual-fulfill`
- **鉴权**: 仅限管理员
- **请求体**:
```json
{
  "reason": "用户微信转账未收到通知，提供微信单号 100000000000 经人工确认已到账"
}
```

#### (3) 批量生成卡密兑换码
- **Method / Path**: `POST /api/v1/admin/subscription/cdkeys/generate`
- **鉴权**: 仅限管理员
- **请求体**:
```json
{
  "planId": "plan_pro_monthly",
  "quantity": 50,
  "notes": "v1.0 种子用户推广活动赠送"
}
```

---

## 6. 数据库表结构演化规划 (MySQL 8 Schema)

以下表结构设计严格契合 StudyPilot 现有的 Flyway 与 JPA 规范（`id` 采用 `VARCHAR(36)`，时间戳采用 `TIMESTAMP(6)`，遵循防软删与审计日志风格）：

```sql
-- 1. 订阅套餐定义表
CREATE TABLE subscription_plans (
    id VARCHAR(36) PRIMARY KEY,
    plan_code VARCHAR(50) NOT NULL UNIQUE,       -- 如 plan_pro_monthly
    name VARCHAR(100) NOT NULL,                  -- 套餐显示名称
    description TEXT,                            -- 套餐简介
    price_cents INT NOT NULL,                    -- 标价(分)
    duration_days INT NOT NULL,                  -- 订阅天数(例如 30, 365)
    entitlements_json JSON NOT NULL,             -- 权益清单配置(JSON)
    sort_order INT NOT NULL DEFAULT 0,           -- 排序号
    is_active BOOLEAN NOT NULL DEFAULT TRUE,     -- 是否在售
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

-- 2. 用户当前有效订阅状态表 (核心权益快照)
CREATE TABLE user_subscriptions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL UNIQUE,         -- 关联 app_users.id，一用户一条有效记录
    current_plan_id VARCHAR(36) NOT NULL,        -- 关联 subscription_plans.id
    tier VARCHAR(32) NOT NULL,                   -- FREE, PRO, ENTERPRISE
    status VARCHAR(32) NOT NULL,                 -- ACTIVE, EXPIRED, CANCELLED
    started_at TIMESTAMP(6) NOT NULL,            -- 本期开通时间
    expires_at TIMESTAMP(6) NOT NULL,            -- 到期失效时间
    ai_token_balance BIGINT NOT NULL DEFAULT 0,  -- 算力额度余额
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_user_subs_user FOREIGN KEY (user_id) REFERENCES app_users (id),
    CONSTRAINT fk_user_subs_plan FOREIGN KEY (current_plan_id) REFERENCES subscription_plans (id)
);

-- 3. 支付订单流水表
CREATE TABLE payment_orders (
    id VARCHAR(36) PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,        -- 业务订单号, 例如 ORD20260905XXXX
    user_id VARCHAR(36) NOT NULL,                -- 下单用户
    plan_id VARCHAR(36) NOT NULL,                -- 选购套餐
    channel VARCHAR(32) NOT NULL,                -- WECHAT_NATIVE, AGGREGATOR, PERSONAL_LISTEN, CDKEY
    original_amount_cents INT NOT NULL,          -- 标价
    actual_amount_cents INT NOT NULL,            -- 实收金额(支持微调尾数识别)
    status VARCHAR(32) NOT NULL,                 -- CREATED, PENDING, PAID, FULFILLED, EXPIRED, FAILED
    channel_trade_no VARCHAR(128),               -- 渠道方交易号(微信支付订单号等)
    expired_at TIMESTAMP(6) NOT NULL,            -- 支付截止时间(通常下单后5-15分钟)
    paid_at TIMESTAMP(6),                        -- 实际支付成功时间
    fulfilled_at TIMESTAMP(6),                   -- 权益开通完成时间
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_pay_order_user FOREIGN KEY (user_id) REFERENCES app_users (id),
    CONSTRAINT fk_pay_order_plan FOREIGN KEY (plan_id) REFERENCES subscription_plans (id)
);
CREATE INDEX idx_pay_order_lookup ON payment_orders (actual_amount_cents, status, expired_at);

-- 4. 原始收款通知与对账流水表 (防篡改审计)
CREATE TABLE payment_transactions (
    id VARCHAR(36) PRIMARY KEY,
    channel VARCHAR(32) NOT NULL,                -- WECHAT, ALIPAY, AGGREGATOR
    channel_trade_no VARCHAR(128),               -- 渠道交易凭证号
    amount_cents INT NOT NULL,                   -- 解析出到账金额
    raw_payload TEXT NOT NULL,                   -- 原始报文(防重放与对账证据)
    matched_order_id VARCHAR(36),                -- 关联匹配到的订单号
    status VARCHAR(32) NOT NULL,                 -- MATCHED, UNMATCHED, DUPLICATE, MANUAL_CHECKED
    created_at TIMESTAMP(6) NOT NULL
);

-- 5. 卡密兑换码表
CREATE TABLE subscription_cdkeys (
    id VARCHAR(36) PRIMARY KEY,
    cdkey_hash VARCHAR(64) NOT NULL UNIQUE,      -- 加盐哈希值(避免明文泄漏)
    cdkey_mask VARCHAR(32) NOT NULL,             -- 脱敏掩码显示 (如 SP-PRO-****-EFGH)
    plan_id VARCHAR(36) NOT NULL,
    duration_days INT NOT NULL,
    status VARCHAR(32) NOT NULL,                 -- UNUSED, REDEEMED, REVOKED
    redeemed_by_user_id VARCHAR(36),
    redeemed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_cdkey_plan FOREIGN KEY (plan_id) REFERENCES subscription_plans (id)
);
```

---

## 6. 系统可靠性、幂等性与风控安全机制

### 6.1 幂等性履约控制 (Idempotency)
- **问题**：无论是微信官方通知还是挂机监听客户端，都可能因网络重发导致同一笔付款被通知多次。
- **解决方案**：
  1. 在 `payment_transactions` 表中将 `channel + channel_trade_no` 建立联合唯一索引或通过 Redis 分布式锁 `lock:payment:trade:{trade_no}` 锁定。
  2. 履约更新逻辑采用数据库乐观锁状态机更新：
     `UPDATE payment_orders SET status = 'PAID' WHERE id = ? AND status = 'PENDING'`
  3. 只有成功从 `PENDING` 流转至 `PAID` 的线程才允许触发套餐顺延服务，彻底杜绝多加月数。

### 6.2 个人收款码免签模式并发“防撞车”设计
- **问题**：如果两个用户在同一分钟内都要购买 29.00 元的月卡，如果都扫同一个码付 29.00 元，挂机手机收到“微信支付收款 29.00 元”时无法判断是谁付的。
- **防撞车算法**：
  1. **金额随机偏移池**：定义允许的浮动范围为 `[0, +0.05]` 元。
  2. 针对 29.00 元套餐，建立当前有效倒计时池。第一个人分配 29.01 元，第二个人分配 29.02 元。
  3. 该金额在 Redis 中占用 TTL 为 300 秒的锁（如 `lock:pending_amount:2901`）。
  4. 用户在界面上看到的付款提示为：“为了自动为您开通，请务必支付精确金额 **29.01** 元（多付或少付一分钱将无法自动到账）”。
  5. 挂机手机捕捉到 29.01 元，便能百分之百精准锁定订单。支付完成后立即释放金额锁。

### 6.3 掉单处理与人工认领机制
- **用户端主动认领流程**：若挂机手机因断网没能上报，导致用户支付后倒计时结束页面显示过期，前端界面提供“我已付款，但未开通？”按钮。
- 用户输入支付微信转账单号末 4 位或截图上传，系统将请求写入 `pending_manual_audits`，并在后台管理系统发出高亮红点提醒开发者，可在管理后台一键核实并“手动履约补发”。

---

## 7. 分阶段实施演进路线 (Roadmap)

为了保证研发节奏稳健、降低前置风险，建议按照以下三个阶段逐步推进：

### 阶段一：数据模型与卡密兑换闭环 (快速验证商业可行性)
- **目标**：不依赖任何第三方支付与挂机硬件，先具备完整的套餐与用户权限控制能力。
- **内容**：
  1. 执行数据库迁移脚本，建立套餐表、用户订阅表、卡密表。
  2. 开发 Spring Boot 订阅拦截切面与到期判断逻辑。
  3. 提供管理员后台生成 CDKEY，用户在前端通过卡密激活 Pro 会员。
  4. 前端个人中心上线“我的订阅”卡片。

### 阶段二：接入免签监听端或个人聚合服务 (全自动流程跑通)
- **目标**：实现“用户扫码付钱 -> 后台检测 -> 自动秒开套餐”的完整闭环。
- **内容**：
  1. 实现订单中心与随机尾数防撞车算法。
  2. 部署/对接安卓通知监听服务或聚合支付平台 Webhook。
  3. 联调端到端自动履约与前端状态轮询。
  4. 上线掉单人工申诉与后台审核补单功能。

### 阶段三：微信官方商户原生支付升级 (正规化规模化)
- **目标**：当具备个体工商户或企业资质后，无缝平滑接入微信官方 Native 扫码支付。
- **内容**：
  1. 集成 `wechatpay-java` 官方 SDK。
  2. 实现 APIv3 签名、证书自动更新与解密回调。
  3. 完善财务每日对账与退款全自动化管理。

---

> **归档提示**：本文档位于 `docs/subscription-payment-automation-prd.md`，作为 StudyPilot 订阅体系的技术需求基线，后续在启动源码开发前可基于此架构召开评审并按切片编写集成测试。
