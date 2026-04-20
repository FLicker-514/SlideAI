#!/usr/bin/env python3
"""
单张图片描述：供 Java 调用，从命令行接收图片路径，将一句描述输出到 stdout。
用法: 在 document-service 目录下执行
  python caption_single.py --image /path/to/image.jpg
"""
import argparse
import sys
from pathlib import Path

_path = Path(__file__).resolve().parent
if str(_path) not in sys.path:
    sys.path.insert(0, str(_path))

from document_service.caption import generate_caption


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", required=True, help="图片路径")
    args = ap.parse_args()
    desc = generate_caption(args.image)
    print(desc, end="")
    if desc and not desc.endswith("\n"):
        print()


if __name__ == "__main__":
    main()
