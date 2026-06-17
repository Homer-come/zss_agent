# 思思专属双模块 AI 助手

Spring Boot 3 + Vue 3 的双人格 Agent 示例：工作模块负责内容创作与提效，生活模块负责陪伴、长期记忆、纪念日和主动关怀。

## 关键能力

- Intent Router：自动分流到 Work Helper 或 Girlfriend Helper。
- Life Agent：后端强制以“亲爱的思思主人”开头，语气温柔稳定。
- Work Agent：进入写作/总结/PPT 场景时先走 Firecrawl 搜索；未配置时优雅降级。
- Memory Agent：MySQL 保存记忆，内存向量检索兜底，保留 Milvus/Spring AI 配置。
- Anniversary Agent：支持生日、恋爱纪念日、初次见面、节日，并在 30/7/3/1/当天提醒。
- 主动陪伴：SSE 长连接 + Scheduler，早安、晚安、纪念日主动推送。
- 降级策略：DeepSeek、Firecrawl、Milvus 或 MySQL 不可用时，不向前端抛系统错误。

## 本地启动

后端默认使用 H2 内存库，因此不启动 MySQL/Milvus 也能跑通：

```bash
./scripts/run-backend.sh
```

前端：

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。后端默认示例端口使用 `18081`，避免和本机已有服务冲突。

## 接入真实服务

复制 `.env.example` 为本地 `.env`，填入自己的环境变量。不要把真实密钥提交到仓库。

```bash
docker compose up -d mysql etcd minio milvus
export DEEPSEEK_API_KEY="你的 DeepSeek API Key"
export FIRECRAWL_API_KEY="你的 Firecrawl API Key"
export FIRECRAWL_ENABLED=true
export MYSQL_URL="jdbc:mysql://localhost:3306/zss_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
export MYSQL_USERNAME=zss_agent
export MYSQL_PASSWORD=zss_agent
mvn -pl backend spring-boot:run
```

## API

- `POST /api/chat/stream`：SSE 流式对话。
- `GET /api/companion/events`：主动陪伴 SSE。
- `POST /api/memory`：保存长期记忆。
- `GET /api/memory`：查看记忆。
- `POST /api/anniversaries`：保存纪念日。
- `GET /api/health/ready`：降级和状态检查。

## Context7 依据

开发前已按项目要求使用 Context7 查询 Spring AI 官方参考文档。采用了官方文档中的 DeepSeek 配置、Milvus vector store starter 与 `application.yml` 配置形态。
