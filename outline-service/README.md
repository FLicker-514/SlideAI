# 大纲生成

从**一段文字**生成 **PPT 大纲**的独立模块，逻辑抽离自原项目 banana-slides 的「一句话生成大纲」流程，沿用同一套 prompt 与 JSON 结构。

## 功能

- **输入**：用户的一段文字（PPT 主题/构想），或**上传的文本/ Markdown 文件**（如 `.md`、`.txt`）。
- **输出**：结构化大纲（每页包含 `title`、`points`，可选 `part`），格式与原项目一致，可直接用于后续生成描述与配图。

## 使用方式

### 1. 命令行（推荐）

在**项目根目录**下执行（会读取 `大纲生成/config.py` 中的通义千问 API 配置，也可在项目根目录 `.env` 中设置 `QWEN_API_KEY` 等）：

```bash
# 直接传入文字
python -m 大纲生成.run "我想做一个关于软件工程管理的汇报，包含绪论、需求分析、设计、测试和总结"

# 从文件读取（支持 .md、.txt）
python -m 大纲生成.run -f doc.md
python -m 大纲生成.run -f idea.txt

# 指定输出语言与格式
python -m 大纲生成.run "主题：产品发布会" -l zh -o readable

# 管道输入
echo "做一个 5 页的读书分享大纲" | python -m 大纲生成.run
```

**参数说明**：

| 参数 | 说明 |
|------|------|
| `input` | 直接传入的一段文字（与 `-f` 二选一） |
| `-f, --file` | 从文件读取输入（支持 .md、.txt 等） |
| `-l, --language` | 输出语言：`zh` / `en` / `ja` / `auto`，默认 `zh` |
| `-r, --requirements` | 可选额外要求（如页数、风格等） |
| `-o, --output` | 输出格式：`json`（默认）或 `readable` |

### 2. outline-service：本目录 = Java 接口 + Python AI

- **REST 接口**：本目录即 **outline-service**（Java Spring Boot），端口 8083。前端请求 `POST /outline` 到本服务。
- **AI 部分**：Java 通过命令行调用本目录下 `python3 -m outline_service.generate_cli`（stdin 入参 JSON，stdout 输出大纲 JSON）。

**运行**（在项目根目录）：
```bash
mvn spring-boot:run -pl outline-service
```

**Python 配置**（环境变量或 `outline_service/config.py`）：
- `LLM_SERVICE_URL`：llm-service 的 base URL（如 `http://127.0.0.1:8082`）。配置后通过 llm-service 调 LLM，不配置则直连通义千问。

### 3. 在代码中调用（原 大纲生成 包）

```python
from 大纲生成 import generate_outline

pages = generate_outline(
    user_input="做一个关于敏捷开发的 10 页 PPT，包含概念、实践与案例。",
    language="zh",
    extra_requirements="每页要点不超过 5 条",
)
# pages: [{"title": "...", "points": [...], "part": "..."(可选)}, ...]
```

若希望改用其他模型，可自行传入大模型调用函数：

```python
def my_llm(prompt: str, thinking_budget: int = 0) -> str:
    # 调用你使用的大模型 API，返回纯文本
    ...

pages = generate_outline("你的主题描述", generate_text=my_llm)
```

## 配置（必填）

所有需要填写的内容在 **`大纲生成/config.py`** 中，默认使用**通义千问（Qwen）** API。

| 配置项 | 说明 | 环境变量（可选覆盖） |
|--------|------|----------------------|
| `API_KEY` | 必填。从 [阿里云 DashScope 控制台](https://dashscope.console.aliyun.com/) 获取 | `QWEN_API_KEY` |
| `API_BASE` | 一般无需改，OpenAI 兼容地址 | `QWEN_API_BASE` |
| `MODEL` | 模型：`qwen-turbo` / `qwen-plus` / `qwen-max` | `QWEN_MODEL` |
| `TIMEOUT` | 请求超时（秒） | `QWEN_TIMEOUT` |

可直接在 `config.py` 里写死 `API_KEY = "sk-xxx"`，或在项目根目录 `.env` 中写 `QWEN_API_KEY=sk-xxx`（环境变量优先）。

## 文件说明

| 文件 | 说明 |
|------|------|
| `config.py` | **需要填写**：Qwen API Key、模型等 |
| `prompts.py` | 抽离的大纲生成 prompt（格式、语言、可选要求、参考文件） |
| `generator.py` | `generate_outline`、`flatten_outline`，默认使用 config 中的 Qwen |
| `run.py` | 命令行入口 |
| `README.md` | 本说明 |

## 与原项目对应关系

- **Prompt**：与 `backend/services/prompts.py` 中的 `get_outline_generation_prompt` 一致（简化掉 `ProjectContext`，仅保留输入文字、语言、可选要求）。
- **输出结构**：与 `AIService.flatten_outline()` 后的页面列表一致，可直接对接原项目后续「描述生成」「配图」等步骤。
