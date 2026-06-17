# Agent Workflow

User Input -> Intent Router -> Agent Dispatcher -> Memory Retrieval -> Prompt Assembly -> LLM Output

生活模块固定经过 `BoyfriendToneService`，确保回复以“亲爱的思思主人”开头。
工作模块固定经过 `FirecrawlSearchService`，未配置 Firecrawl 时自动降级为本地背景说明。
