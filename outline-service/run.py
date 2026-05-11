#!/usr/bin/env python3
"""
命令行入口：输入一段文字或上传文件（含 .md），输出 PPT 大纲（JSON 或可读格式）。
使用方式：
  python -m 大纲生成.run "我想做一个关于软件工程管理的汇报，包含绪论、需求分析、设计、测试和总结"
  python -m 大纲生成.run -f doc.md
  echo "你的长文本..." | python -m 大纲生成.run
  python -m 大纲生成.run -f idea.txt
"""
import argparse
import json
import sys
from pathlib import Path

# 加载项目根目录 .env，便于复用 backend 的 AI 配置
try:
    from dotenv import load_dotenv
    _env = Path(__file__).resolve().parent.parent / ".env"
    if _env.is_file():
        load_dotenv(dotenv_path=_env, override=True)
except ImportError:
    pass

# 确保项目根目录与 backend 在 path 中，以便使用 backend 的 AI 配置
_project_root = Path(__file__).resolve().parent.parent
_backend = _project_root / "backend"
if _backend.is_dir() and str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))
if str(_backend) not in sys.path:
    sys.path.insert(0, str(_backend))


def main():
    parser = argparse.ArgumentParser(
        description="根据输入文字生成 PPT 大纲（沿用原项目优秀方法）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "input",
        nargs="?",
        default=None,
        help="直接传入的一段文字（PPT 主题/构想）",
    )
    parser.add_argument(
        "-f", "--file",
        metavar="PATH",
        help="从文件读取输入文字（支持 .md、.txt 等，与 input 二选一）",
    )
    parser.add_argument(
        "-l", "--language",
        choices=["zh", "en", "ja", "auto"],
        default="zh",
        help="输出语言（默认 zh）",
    )
    parser.add_argument(
        "-r", "--requirements",
        metavar="TEXT",
        default=None,
        help="可选：额外要求（如页数、风格等）",
    )
    parser.add_argument(
        "-o", "--output",
        choices=["json", "readable"],
        default="json",
        help="输出格式：json 或 readable（默认 json）",
    )
    args = parser.parse_args()

    if args.file:
        path = Path(args.file)
        if not path.is_file():
            print(f"错误：文件不存在 {path}", file=sys.stderr)
            sys.exit(1)
        user_input = path.read_text(encoding="utf-8").strip()
    elif args.input:
        user_input = args.input.strip()
    else:
        # 从 stdin 读取
        if sys.stdin.isatty():
            print("请在命令行传入内容，或使用 -f 指定文件，或从标准输入管道传入。", file=sys.stderr)
            parser.print_help(sys.stderr)
            sys.exit(1)
        user_input = sys.stdin.read().strip()

    if not user_input:
        print("错误：输入内容为空。", file=sys.stderr)
        sys.exit(1)

    try:
        from 大纲生成.generator import generate_outline
    except ImportError:
        # 以脚本方式运行时的 fallback
        sys.path.insert(0, str(_project_root))
        from 大纲生成.generator import generate_outline

    try:
        pages = generate_outline(
            user_input=user_input,
            language=args.language,
            extra_requirements=args.requirements,
        )
    except Exception as e:
        print(f"生成失败: {e}", file=sys.stderr)
        sys.exit(1)

    if args.output == "json":
        print(json.dumps(pages, ensure_ascii=False, indent=2))
    else:
        for i, p in enumerate(pages, 1):
            part = p.get("part")
            if part:
                print(f"【{part}】")
            print(f"  {i}. {p.get('title', '')}")
            for pt in p.get("points", []):
                print(f"     - {pt}")
            print()


if __name__ == "__main__":
    main()
