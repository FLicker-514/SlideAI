"""
AI 大纲生成 CLI：从 stdin 读入一行 JSON，调用 generate_outline，向 stdout 输出一行 JSON。
供 Java outline-service 调用，仅负责与 AI 相关的逻辑（prompt + LLM + 解析）。
输入：一行 JSON {"topic": "...", "documentContents": [...], "language": "zh", "extraRequirements": ""}
输出：一行 JSON 数组 [{"part":"...","title":"...","points":[...]}, ...]，错误时输出 {"error": "..."}
"""
import json
import sys

def main():
    try:
        line = sys.stdin.readline()
        if not line or not line.strip():
            out = {"error": "缺少输入"}
            print(json.dumps(out, ensure_ascii=False))
            sys.exit(1)
        data = json.loads(line)
        topic = (data.get("topic") or "").strip()
        if not topic:
            print(json.dumps({"error": "topic 不能为空"}, ensure_ascii=False))
            sys.exit(1)
        document_contents = data.get("documentContents") or []
        language = (data.get("language") or "zh").strip() or "zh"
        extra = (data.get("extraRequirements") or data.get("extra_requirements") or "").strip() or None
        reference_files = [
            {"filename": f"文档{i+1}.md", "content": (c or "").strip()}
            for i, c in enumerate(document_contents)
            if (c or "").strip()
        ]
        from .generator import generate_outline
        pages = generate_outline(
            user_input=topic,
            language=language,
            extra_requirements=extra,
            reference_files_content=reference_files if reference_files else None,
        )
        print(json.dumps(pages, ensure_ascii=False))
    except json.JSONDecodeError as e:
        print(json.dumps({"error": f"输入 JSON 无效: {e}"}, ensure_ascii=False))
        sys.exit(1)
    except Exception as e:
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()
