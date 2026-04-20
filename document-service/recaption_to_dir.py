#!/usr/bin/env python3
"""
对已解析目录中的 content.md 补充图片描述（调用通义千问视觉）。
适用于之前未配置 CAPTION_API_KEY 时解析出的、图片为 ![](images/xxx) 无描述的情况。
用法: 在 document-service 目录下执行
  export CAPTION_API_KEY=your_dashscope_key   # 若未在 config 中配置
  python recaption_to_dir.py <输出目录路径>
例如: python recaption_to_dir.py Userdata/40eff0b6.../PDF/9cb8aa66ce7d4fd88257608385681259
"""
import re
import sys
from pathlib import Path

_project_root = Path(__file__).resolve().parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

from document_service import caption as caption_module
from document_service.config import get_caption_api_key


def main() -> None:
    if len(sys.argv) < 2:
        print("用法: python recaption_to_dir.py <包含 content.md 和 images/ 的目录>", file=sys.stderr)
        sys.exit(2)
    output_dir = Path(sys.argv[1]).resolve()
    content_md = output_dir / "content.md"
    if not content_md.is_file():
        print(f"错误：{content_md} 不存在", file=sys.stderr)
        sys.exit(1)
    if not get_caption_api_key().strip():
        print("错误：未配置 CAPTION_API_KEY，无法生成图片描述。请设置环境变量或在 document_service/config.py 中配置。", file=sys.stderr)
        sys.exit(1)

    content = content_md.read_text(encoding="utf-8")
    pattern = r"!\[(.*?)\]\(([^\)]+)\)"
    matches = list(re.finditer(pattern, content))
    to_caption = [
        m for m in matches
        if not m.group(1).strip() and m.group(2).strip().replace("\\", "/").startswith("images/")
    ]
    if not to_caption:
        print("没有需要补充描述的图片（无 alt 的 ![](images/...)）")
        return

    print(f"共 {len(to_caption)} 张图片需要生成描述...")
    captions = []
    failed = 0
    for i, m in enumerate(to_caption):
        rel = m.group(2).strip().replace("\\", "/")
        local_path = output_dir / rel
        if not local_path.is_file():
            captions.append("")
            failed += 1
            continue
        cap = caption_module.generate_caption(local_path)
        captions.append(cap if cap else "")
        if not cap:
            failed += 1
        if (i + 1) % 5 == 0:
            print(f"  已处理 {i + 1}/{len(to_caption)}")

    result = content
    for m, cap in zip(reversed(to_caption), reversed(captions)):
        result = result[: m.start()] + f"![{cap}]({m.group(2)})" + result[m.end() :]
    content_md.write_text(result, encoding="utf-8")
    print(f"已写回 {content_md}")
    if failed:
        print(f"（{failed} 张描述未生成）", file=sys.stderr)


if __name__ == "__main__":
    main()
