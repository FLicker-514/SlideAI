#!/usr/bin/env python3
"""
命令行：仅解析 PDF。每个 PDF 生成一个 id 文件夹，内含 content.md 与图片；无描述图片自动生成描述。

使用方式：
  python -m 文件解析.run path/to/file.pdf
  python -m 文件解析.run path/to/file.pdf -o output.md   # 额外复制 content.md 到指定路径
"""
import argparse
import sys
from pathlib import Path

_project_root = Path(__file__).resolve().parent.parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

try:
    from dotenv import load_dotenv
    _env = _project_root / ".env"
    if _env.is_file():
        load_dotenv(dotenv_path=_env, override=True)
except ImportError:
    pass


def main():
    parser = argparse.ArgumentParser(
        description="解析 PDF：生成 id 文件夹，内含 content.md 与图片；无描述图片自动生成描述",
        epilog=__doc__,
    )
    parser.add_argument("file", help="PDF 文件路径")
    parser.add_argument("-o", "--output", metavar="PATH", help="将 content.md 复制到该路径（可选）")
    args = parser.parse_args()

    path = Path(args.file)
    if not path.is_file():
        print(f"错误：文件不存在 {path}", file=sys.stderr)
        sys.exit(1)
    if path.suffix.lower() != ".pdf":
        print("错误：仅支持 PDF 文件", file=sys.stderr)
        sys.exit(1)

    from 文件解析 import create_parser

    service = create_parser()
    extract_id, content_md_path, error, failed_captions = service.parse_file(str(path), path.name)

    if error:
        print(f"解析失败: {error}", file=sys.stderr)
        sys.exit(1)

    storage_root = service.storage_root
    id_dir = storage_root / extract_id
    print(f"解析完成。id: {extract_id}", file=sys.stderr)
    print(f"目录: {id_dir}", file=sys.stderr)
    print(f"文字: {id_dir}/content.md", file=sys.stderr)
    print(f"图片: {id_dir}/ 下图片文件", file=sys.stderr)
    if failed_captions:
        print(f"（{failed_captions} 张图片描述生成失败）", file=sys.stderr)

    if args.output:
        import shutil
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(content_md_path, out)
        print(f"已复制 content.md -> {out}", file=sys.stderr)

    print(extract_id)


if __name__ == "__main__":
    main()
