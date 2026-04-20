# 文件解析（仅 PDF）

只解析 **PDF**。每个 PDF 生成一个 **id 文件夹**，目录下存 **文字内容**（`content.md`）和 **图片**；图片若没有文字描述则**自动生成描述**（通义千问视觉）。

## 输出结构

每个 PDF 对应一个 id 文件夹，**只保留**：

```
{STORAGE_ROOT}/
  {id}/
    content.md       # 正文（Markdown，图片用相对路径引用）
    images/         # 从 PDF 提取的图片
```

其他 MinerU 解压出的文件（如 full.md、*.json）会在解析完成后自动删除。

## 使用方式

### 命令行

```bash
# 解析 PDF，在 文件解析/parsed_output/{id}/ 下生成 content.md 与图片
python -m 文件解析.run path/to/file.pdf

# 同时把 content.md 复制到指定路径
python -m 文件解析.run path/to/file.pdf -o output.md
```

### 代码

```python
from 文件解析 import create_parser

parser = create_parser()
extract_id, content_md_path, error, failed_captions = parser.parse_file("/path/to/file.pdf")

if error:
    print("失败:", error)
else:
    # extract_id: 文件夹名
    # content_md_path: content.md 的绝对路径
    # 图片在同一目录下（如 images/xxx.png）
    print("id:", extract_id, "content:", content_md_path)
```

## 配置（config.py）

| 配置项 | 说明 |
|--------|------|
| **MINERU_TOKEN** | MinerU API Token（必填）。https://mineru.net/apiManage/token |
| ENABLE_IMAGE_CAPTION | 是否对无描述图片自动生成描述（默认 True） |
| CAPTION_API_KEY / CAPTION_API_BASE / CAPTION_MODEL | 描述用通义千问；可不填则使用 大纲生成 的 API |
| STORAGE_ROOT | 解析结果根目录；默认 文件解析/parsed_output |

## 依赖

- `requests`
- `Pillow`（图片描述时读图）
- 可选：项目根目录有 `大纲生成` 时，描述接口复用其 API 配置

## 图片描述

- 解析出的 Markdown 里若有 `![](相对路径)`（无 alt），会调用**通义千问视觉模型**生成一句中文描述，并写回 `content.md` 的 alt。
- 可在 config 中关闭：`ENABLE_IMAGE_CAPTION = False`。
