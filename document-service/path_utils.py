"""
路径工具：MinerU 解析结果路径与前缀匹配。
从原项目 backend/utils/path_utils.py 抽离。
"""

import os
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


def convert_mineru_path_to_local(mineru_path: str, storage_root: Path) -> Optional[Path]:
    """
    将 /files/mineru/{extract_id}/{rel_path} 格式的路径转换为本地文件系统路径。

    Args:
        mineru_path: 如 /files/mineru/abc123/images/fig1.png
        storage_root: 存储根目录（mineru_files 的父目录）

    Returns:
        本地路径 Path，失败返回 None
    """
    try:
        if not mineru_path.startswith("/files/mineru/"):
            return None
        rel = mineru_path.replace("/files/mineru/", "").lstrip("/")
        local = storage_root / "mineru_files" / rel
        return local
    except Exception as e:
        logger.warning(f"Failed to convert MinerU path: {mineru_path}, error: {e}")
        return None


def find_file_with_prefix(file_path: Path) -> Optional[Path]:
    """
    查找文件，支持前缀匹配（MinerU 可能生成短前缀名）。
    """
    if file_path.exists() and file_path.is_file():
        return file_path
    filename = file_path.name
    dirpath = file_path.parent
    if "." in filename and dirpath.exists() and dirpath.is_dir():
        prefix, ext = os.path.splitext(filename)
        if len(prefix) >= 5:
            try:
                for fname in os.listdir(dirpath):
                    fp, fe = os.path.splitext(fname)
                    if fp.lower().startswith(prefix.lower()) and fe.lower() == ext.lower():
                        p = dirpath / fname
                        if p.is_file():
                            return p
            except OSError as e:
                logger.warning(f"Failed to list directory {dirpath}: {e}")
    return None


def find_mineru_file_with_prefix(mineru_path: str, storage_root: Path) -> Optional[Path]:
    """
    查找 MinerU 文件，支持前缀匹配。
    """
    local = convert_mineru_path_to_local(mineru_path, storage_root)
    if local is None:
        return None
    if local.exists() and local.is_file():
        return local
    return find_file_with_prefix(local)
