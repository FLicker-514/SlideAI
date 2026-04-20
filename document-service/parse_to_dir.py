#!/usr/bin/env python3
"""
将 PDF 解析结果写入指定目录：--pdf 指定 PDF 路径，--output-dir 指定输出目录。
输出目录内将生成 content.md、images/ 以及 images/info.json（图片 id 与描述）。
用法: python parse_to_dir.py --pdf /path/to/file.pdf --output-dir /path/to/Userdata/userId/PDF/fileId
"""
import argparse
import json
import re
import shutil
import sys
from pathlib import Path

_project_root = Path(__file__).resolve().parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

try:
    from document_service.parser import FileParserService
    _create_parser = lambda storage_root: FileParserService(storage_root=Path(storage_root))
except Exception as e:
    print(f"无法加载 document_service.parser: {e}", file=sys.stderr)
    sys.exit(2)


def main():
    ap = argparse.ArgumentParser(description="解析 PDF 并写入指定目录：content.md + images/")
    ap.add_argument("--pdf", required=True, help="PDF 文件路径")
    ap.add_argument("--output-dir", required=True, help="输出目录（将写入 content.md 与 images/）")
    args = ap.parse_args()

    pdf_path = Path(args.pdf)
    output_dir = Path(args.output_dir)

    if not pdf_path.is_file():
        print(f"错误：PDF 不存在 {pdf_path}", file=sys.stderr)
        sys.exit(1)
    if pdf_path.suffix.lower() != ".pdf":
        print("错误：仅支持 PDF 文件", file=sys.stderr)
        sys.exit(1)

    output_dir.mkdir(parents=True, exist_ok=True)
    import tempfile
    with tempfile.TemporaryDirectory(prefix="doc_parse_") as tmp:
        storage_root = Path(tmp)
        service = _create_parser(storage_root)
        extract_id, content_md_path, error, failed_captions = service.parse_file(str(pdf_path), pdf_path.name)

        if error:
            print(f"解析失败: {error}", file=sys.stderr)
            sys.exit(1)

        id_dir = storage_root / extract_id
        if not id_dir.is_dir():
            print("解析结果目录不存在", file=sys.stderr)
            sys.exit(1)

        # 复制 content.md
        src_md = id_dir / "content.md"
        if src_md.is_file():
            shutil.copy2(src_md, output_dir / "content.md")
        else:
            print("未生成 content.md", file=sys.stderr)
            sys.exit(1)

        # 复制 images 目录
        src_images = id_dir / "images"
        dst_images = output_dir / "images"
        if src_images.is_dir():
            if dst_images.exists():
                shutil.rmtree(dst_images)
            shutil.copytree(src_images, dst_images)

        # 在 pdf/images 下写入 info.json：记录图片 id（文件名）与描述（来自 content.md 的 alt）
        md_path = output_dir / "content.md"
        images_info = _extract_images_info_from_md(md_path)
        if images_info and dst_images.is_dir():
            info_json = dst_images / "info.json"
            try:
                info_json.write_text(json.dumps(images_info, ensure_ascii=False, indent=2), encoding="utf-8")
            except Exception as e:
                print(f"写入 images/info.json 失败: {e}", file=sys.stderr)

        if failed_captions:
            print(f"（{failed_captions} 张图片描述生成失败）", file=sys.stderr)
        print("解析完成", file=sys.stderr)


def _extract_images_info_from_md(md_path: Path) -> list:
    """从 content.md 中解析所有 ![描述](images/xxx) 引用，返回 [{"id": "文件名", "description": "描述"}, ...]，按 id 去重保留首次出现。"""
    if not md_path.is_file():
        return []
    try:
        text = md_path.read_text(encoding="utf-8")
    except Exception:
        return []
    pattern = re.compile(r"!\[(.*?)\]\((.*?)\)")
    seen = set()
    result = []
    for m in pattern.finditer(text):
        alt = (m.group(1) or "").strip()
        path = (m.group(2) or "").strip().replace("\\", "/")
        if not path.startswith("images/"):
            continue
        img_id = path.split("/")[-1]
        if img_id in seen:
            continue
        seen.add(img_id)
        result.append({"id": img_id, "description": alt})
    return result


if __name__ == "__main__":
    main()
