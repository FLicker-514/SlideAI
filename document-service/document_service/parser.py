"""
文件解析服务：仅解析 PDF。每个 PDF 生成一个 id 文件夹，内含 content.md 与图片。
"""
import io
import os
import re
import time
import uuid
import zipfile
import logging
from pathlib import Path
from typing import Optional, Tuple

import requests

from .config import (
    get_mineru_token,
    get_mineru_api_base,
    get_mineru_model_version,
    get_storage_root,
    get_enable_image_caption,
)
from . import caption as caption_module

logger = logging.getLogger(__name__)

ParseResult = Tuple[Optional[str], Optional[str], Optional[str], int]


class FileParserService:
    def __init__(
        self,
        mineru_token: str = None,
        mineru_api_base: str = None,
        storage_root: Path = None,
        mineru_model_version: str = None,
        enable_image_caption: bool = None,
    ):
        self.mineru_token = mineru_token or get_mineru_token()
        self.mineru_api_base = (mineru_api_base or get_mineru_api_base()).rstrip("/")
        self.storage_root = Path(storage_root or get_storage_root())
        self.mineru_model_version = mineru_model_version or get_mineru_model_version()
        self.enable_image_caption = enable_image_caption if enable_image_caption is not None else get_enable_image_caption()
        self.get_upload_url_api = f"{self.mineru_api_base}/api/v4/file-urls/batch"
        self.get_result_api_template = f"{self.mineru_api_base}/api/v4/extract-results/batch/{{}}"

    def parse_file(self, file_path: str, filename: str = None) -> ParseResult:
        path = Path(file_path)
        if not path.is_file():
            return None, None, f"文件不存在: {file_path}", 0
        name = filename or path.name
        ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
        if ext != "pdf":
            return None, None, "仅支持 PDF 文件", 0
        if not self.mineru_token or not self.mineru_token.strip():
            return None, None, "请配置 MINERU_TOKEN（document_service/config.py）", 0

        try:
            logger.info("Step 1/4: 请求上传 URL...")
            batch_id, upload_url, err = self._get_upload_url(name)
            if err:
                return None, None, err, 0
            logger.info("Step 2/4: 上传文件...")
            err = self._upload_file(str(path), upload_url)
            if err:
                return None, None, err, 0
            logger.info("Step 3/4: 等待解析完成...")
            extract_id, err = self._download_and_save(batch_id)
            if err:
                return None, None, err, 0
            id_dir = self.storage_root / extract_id
            content_path = id_dir / "content.md"
            if not content_path.is_file():
                return None, None, "解析结果中未生成 content.md", 0
            markdown_content = content_path.read_text(encoding="utf-8")
            failed = 0
            if self.enable_image_caption and ("![" in markdown_content and "](" in markdown_content):
                logger.info("Step 4/4: 为无描述图片生成描述...")
                markdown_content, failed = self._enhance_markdown_with_captions(markdown_content, extract_id)
                content_path.write_text(markdown_content, encoding="utf-8")
            return extract_id, str(content_path), None, failed
        except Exception as e:
            logger.exception("解析异常")
            return None, None, str(e), 0

    def _get_upload_url(self, filename: str) -> Tuple[Optional[str], Optional[str], Optional[str]]:
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {self.mineru_token}"}
        payload = {"files": [{"name": filename}], "model_version": self.mineru_model_version}
        try:
            r = requests.post(self.get_upload_url_api, headers=headers, json=payload, timeout=30)
            r.raise_for_status()
            data = r.json()
            if data.get("code") != 0:
                return None, None, data.get("msg", "获取上传地址失败")
            batch_id = data["data"]["batch_id"]
            upload_url = data["data"]["file_urls"][0]
            return batch_id, upload_url, None
        except requests.RequestException as e:
            return None, None, f"网络错误: {e}"

    def _upload_file(self, file_path: str, upload_url: str) -> Optional[str]:
        try:
            with open(file_path, "rb") as f:
                r = requests.put(upload_url, data=f, headers={"Authorization": None}, timeout=300)
                r.raise_for_status()
            return None
        except Exception as e:
            return f"上传失败: {e}"

    def _poll_result(self, batch_id: str, max_wait: int = 600) -> Tuple[Optional[str], Optional[str], Optional[str]]:
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {self.mineru_token}"}
        url = self.get_result_api_template.format(batch_id)
        start = time.time()
        while time.time() - start < max_wait:
            try:
                r = requests.get(url, headers=headers, timeout=30)
                r.raise_for_status()
                info = r.json()
                if info.get("code") != 0:
                    return None, None, info.get("msg", "查询状态失败")
                item = info["data"]["extract_result"][0]
                state = item["state"]
                if state == "done":
                    return item["full_zip_url"], None, None
                if state == "failed":
                    return None, None, item.get("err_msg", "解析失败")
            except requests.RequestException as e:
                logger.warning("轮询失败: %s", e)
            time.sleep(2)
        return None, None, f"解析超时（{max_wait}s）"

    def _download_and_save(self, batch_id: str) -> Tuple[Optional[str], Optional[str]]:
        zip_url, _, err = self._poll_result(batch_id)
        if err:
            return None, err
        try:
            r = requests.get(zip_url, timeout=60)
            r.raise_for_status()
        except requests.RequestException as e:
            return None, f"下载结果失败: {e}"
        extract_id = str(uuid.uuid4())[:8]
        id_dir = self.storage_root / extract_id
        id_dir.mkdir(parents=True, exist_ok=True)
        markdown_content = None
        md_rel_path = None
        with zipfile.ZipFile(io.BytesIO(r.content)) as z:
            z.extractall(id_dir)
            for name in z.namelist():
                if name.lower().endswith(".md"):
                    md_full = id_dir / name
                    if md_full.is_file():
                        markdown_content = md_full.read_text(encoding="utf-8")
                        md_rel_path = name.replace("\\", "/")
                        break
        if not markdown_content:
            return None, "ZIP 中未找到 Markdown 文件"
        markdown_content = self._replace_image_paths_to_relative(markdown_content, md_rel_path)
        content_md = id_dir / "content.md"
        content_md.write_text(markdown_content, encoding="utf-8")
        self._keep_only_content_and_images(id_dir)
        return extract_id, None

    def _keep_only_content_and_images(self, id_dir: Path) -> None:
        import shutil
        keep = {"content.md", "images"}
        for item in list(id_dir.iterdir()):
            if item.name in keep:
                continue
            if item.is_file():
                try:
                    item.unlink()
                except OSError as e:
                    logger.warning("删除多余文件 %s 失败: %s", item, e)
            else:
                try:
                    shutil.rmtree(item)
                except OSError as e:
                    logger.warning("删除多余目录 %s 失败: %s", item, e)

    def _replace_image_paths_to_relative(self, content: str, md_rel_path: str) -> str:
        md_dir = os.path.dirname(md_rel_path)
        def repl(match):
            alt, img_path = match.group(1), match.group(2)
            if img_path.startswith(("http://", "https://")):
                return match.group(0)
            if md_dir:
                rel = os.path.normpath(os.path.join(md_dir, img_path)).replace("\\", "/")
            else:
                rel = img_path.replace("\\", "/")
            return f"![{alt}]({rel})"
        return re.sub(r"!\[(.*?)\]\((.*?)\)", repl, content)

    def _enhance_markdown_with_captions(self, content: str, extract_id: str) -> Tuple[str, int]:
        pattern = r"!\[(.*?)\]\(([^\)]+)\)"
        matches = list(re.finditer(pattern, content))
        to_caption = [m for m in matches if not m.group(1).strip()]
        if not to_caption:
            return content, 0
        id_dir = self.storage_root / extract_id
        failed = 0
        captions = []
        for m in to_caption:
            rel = m.group(2).strip()
            if rel.startswith(("http://", "https://")):
                captions.append("")
                failed += 1
                continue
            local_path = id_dir / rel
            if not local_path.is_file():
                captions.append("")
                failed += 1
                continue
            cap = caption_module.generate_caption(local_path)
            captions.append(cap if cap else "")
            if not cap:
                failed += 1
        result = content
        for m, cap in zip(reversed(to_caption), reversed(captions)):
            result = result[: m.start()] + f"![{cap}]({m.group(2)})" + result[m.end() :]
        return result, failed


def create_parser(
    mineru_token: str = None,
    storage_root: Path = None,
    enable_image_caption: bool = None,
) -> FileParserService:
    return FileParserService(
        mineru_token=mineru_token,
        storage_root=storage_root,
        enable_image_caption=enable_image_caption,
    )
