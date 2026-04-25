#!/usr/bin/env python3
"""
文件上传向量化（向量存储）脚本

用途：
1) 读取输入目录下的文本文件（默认：.txt/.md）
2) 做简单分块（按字符长度滑动窗口）
3) 生成每个块的 embedding
4) 将向量与原文一起落盘到 JSON 向量库文件

注意：
- 默认 provider='fallback'：不依赖外部 API，可在本地/无 Key 的情况下运行测试。
- provider='dashscope'：会调用阿里云 DashScope 的 TextEmbedding（需要环境变量：
  `DASHSCOPE_API_KEY`）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Literal, Sequence, Tuple


EmbeddingProvider = Literal["dashscope", "openai", "fallback"]

DEFAULT_DIMENSION = 1024


def cosine_similarity(a: Sequence[float], b: Sequence[float]) -> float:
    """计算余弦相似度（向量不要求预先归一化）"""
    if len(a) != len(b):
        raise ValueError(f"vector length mismatch: {len(a)} vs {len(b)}")
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / ((na**0.5) * (nb**0.5))


_TOKEN_RE = re.compile(r"[A-Za-z0-9]+|[\u4e00-\u9fff]")


def tokenize(text: str) -> List[str]:
    """极简 tokenizer：把连续中文汉字视作若干 token，把英文/数字按词分组。"""
    return [m.group(0) for m in _TOKEN_RE.finditer(text)]


def _hash_to_index(token: str, dimension: int) -> int:
    h = hashlib.sha256(token.encode("utf-8")).hexdigest()
    return int(h, 16) % dimension


def fallback_embed_texts(texts: Sequence[str], dimension: int = DEFAULT_DIMENSION) -> List[List[float]]:
    """
    本地确定性 embedding：
    - 对每个 token 做 hash 映射到 [0, dimension)
    - 累加权重并归一化

    说明：该 embedding 的“语义”能力有限，但对于单元测试（可控输入）是稳定可复现的。
    """
    out: List[List[float]] = []
    for text in texts:
        vec = [0.0] * dimension
        for tok in tokenize(text):
            vec[_hash_to_index(tok, dimension)] += 1.0
        # 归一化
        norm = sum(v * v for v in vec) ** 0.5
        if norm > 0:
            vec = [v / norm for v in vec]
        out.append(vec)
    return out


def dashscope_embed_texts(texts: Sequence[str], dimension: int = DEFAULT_DIMENSION) -> List[List[float]]:
    """
    DashScope embedding（参考你给的官方代码思路）
    需要：
    - 环境变量 `DASHSCOPE_API_KEY`
    """
    try:
        import dashscope
        from dashscope import TextEmbedding
    except ImportError as e:
        raise RuntimeError("未安装 dashscope，请先执行: pip install dashscope") from e

    api_key = os.environ.get("DASHSCOPE_API_KEY")
    if not api_key:
        raise RuntimeError("缺少环境变量 DASHSCOPE_API_KEY")

    # dashscope 支持直接设全局 api_key
    dashscope.api_key = api_key

    resp = TextEmbedding.call(
        model="text-embedding-v4",
        input=list(texts),
        dimension=dimension,
    )

    embeddings = []
    # 参考代码结构：resp.output['embeddings'][i]['embedding']
    for item in resp.output["embeddings"]:
        embeddings.append(item["embedding"])
    return embeddings


def openai_embed_texts(
    texts: Sequence[str],
    dimension: int = DEFAULT_DIMENSION,
    *,
    api_key_env: str = "DASHSCOPE_API_KEY",
    base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1",
) -> List[List[float]]:
    """
    OpenAI-Compatible embeddings 调用（参考你给的官方代码思路）

    说明：DashScope 兼容 OpenAI API，所以这里的 provider='openai' 仍可以走 dashscope。
    需要：
    - 环境变量 `DASHSCOPE_API_KEY`
    """
    try:
        from openai import OpenAI
    except ImportError as e:
        raise RuntimeError("未安装 openai，请先执行: pip install openai") from e

    api_key = os.environ.get(api_key_env)
    if not api_key:
        raise RuntimeError(f"缺少环境变量 {api_key_env}")

    client = OpenAI(
        api_key=api_key,
        base_url=base_url,
    )

    completion = client.embeddings.create(
        model="text-embedding-v4",
        input=list(texts),
    )

    # openai embeddings: completion.data[i].embedding
    return [item.embedding for item in completion.data]


def embed_texts(texts: Sequence[str], provider: EmbeddingProvider, dimension: int) -> List[List[float]]:
    if provider == "fallback":
        return fallback_embed_texts(texts, dimension=dimension)
    if provider == "dashscope":
        return dashscope_embed_texts(texts, dimension=dimension)
    if provider == "openai":
        return openai_embed_texts(texts, dimension=dimension)
    raise ValueError(f"unknown provider: {provider}")


def chunk_text(text: str, chunk_size: int = 500, overlap: int = 50) -> List[str]:
    """按字符长度做滑动窗口分块（尽量保留原始文本顺序）"""
    if chunk_size <= 0:
        raise ValueError("chunk_size must be > 0")
    if overlap < 0:
        raise ValueError("overlap must be >= 0")
    if overlap >= chunk_size:
        raise ValueError("overlap must be smaller than chunk_size")

    if not text:
        return []

    text = text.strip()
    if not text:
        return []

    chunks: List[str] = []
    start = 0
    while start < len(text):
        end = min(len(text), start + chunk_size)
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end >= len(text):
            break
        start = end - overlap
    return chunks


def load_text_file(path: Path) -> str:
    """读取文本文件；遇到无法解码会给出明确错误。"""
    suffix = path.suffix.lower()
    if suffix not in {".txt", ".md"}:
        raise ValueError(f"unsupported file type: {path}")
    return path.read_text(encoding="utf-8")


def vectorize_files(
    input_dir: Path,
    output_json: Path,
    *,
    provider: EmbeddingProvider = "fallback",
    dimension: int = DEFAULT_DIMENSION,
    chunk_size: int = 500,
    overlap: int = 50,
    allowed_suffixes: Tuple[str, ...] = (".txt", ".md"),
    max_files: int | None = None,
) -> Dict[str, Any]:
    if not input_dir.exists() or not input_dir.is_dir():
        raise FileNotFoundError(f"input_dir not found or not a directory: {input_dir}")

    output_json.parent.mkdir(parents=True, exist_ok=True)

    # 为了测试稳定，固定遍历顺序
    files = sorted([p for p in input_dir.iterdir() if p.is_file() and p.suffix.lower() in allowed_suffixes])
    if max_files is not None:
        files = files[:max_files]
    if not files:
        raise RuntimeError(f"no input text files found in {input_dir}")

    all_chunks: List[Dict[str, Any]] = []
    all_chunk_texts: List[str] = []

    for file_idx, path in enumerate(files):
        text = load_text_file(path)
        chunks = chunk_text(text, chunk_size=chunk_size, overlap=overlap)
        for chunk_idx, chunk in enumerate(chunks):
            item_id = f"f{file_idx}_c{chunk_idx}"
            all_chunks.append(
                {
                    "id": item_id,
                    "source_path": str(path),
                    "file_name": path.name,
                    "chunk_index": chunk_idx,
                    "text": chunk,
                    "embedding": None,  # 先占位，后面补齐
                }
            )
            all_chunk_texts.append(chunk)

    embeddings = embed_texts(all_chunk_texts, provider=provider, dimension=dimension)
    if len(embeddings) != len(all_chunks):
        raise RuntimeError("embedding count mismatch")

    for i, emb in enumerate(embeddings):
        all_chunks[i]["embedding"] = emb

    store = {
        "version": 1,
        "dimension": dimension,
        "provider": provider,
        "items": all_chunks,
    }
    output_json.write_text(json.dumps(store, ensure_ascii=False), encoding="utf-8")

    return {
        "saved_to": str(output_json),
        "file_count": len(files),
        "chunk_count": len(all_chunks),
        "provider": provider,
        "dimension": dimension,
    }


def vectorize_single_file(
    input_file: Path,
    output_json: Path,
    *,
    provider: EmbeddingProvider = "fallback",
    dimension: int = DEFAULT_DIMENSION,
    chunk_size: int = 500,
    overlap: int = 50,
    allowed_suffixes: Tuple[str, ...] = (".txt", ".md"),
) -> Dict[str, Any]:
    """
    单文件向量化（输入是单个 .txt/.md），输出同样是 vector_store JSON。

    该函数用于命令行快捷用法：直接传一个文件路径，而不必先准备目录。
    """
    if not input_file.exists() or not input_file.is_file():
        raise FileNotFoundError(f"input_file not found or not a file: {input_file}")
    if input_file.suffix.lower() not in allowed_suffixes:
        raise ValueError(f"unsupported file type: {input_file}")

    output_json.parent.mkdir(parents=True, exist_ok=True)

    text = load_text_file(input_file)
    chunks = chunk_text(text, chunk_size=chunk_size, overlap=overlap)
    if not chunks:
        raise RuntimeError("no chunks generated from the input file")

    all_chunks: List[Dict[str, Any]] = []
    all_chunk_texts: List[str] = []
    for chunk_idx, chunk in enumerate(chunks):
        item_id = f"single_c{chunk_idx}"
        all_chunks.append(
            {
                "id": item_id,
                "source_path": str(input_file),
                "file_name": input_file.name,
                "chunk_index": chunk_idx,
                "text": chunk,
                "embedding": None,  # 先占位，后面补齐
            }
        )
        all_chunk_texts.append(chunk)

    embeddings = embed_texts(all_chunk_texts, provider=provider, dimension=dimension)
    if len(embeddings) != len(all_chunks):
        raise RuntimeError("embedding count mismatch")

    for i, emb in enumerate(embeddings):
        all_chunks[i]["embedding"] = emb

    store = {
        "version": 1,
        "dimension": dimension,
        "provider": provider,
        "items": all_chunks,
    }
    output_json.write_text(json.dumps(store, ensure_ascii=False), encoding="utf-8")

    return {
        "saved_to": str(output_json),
        "file_count": 1,
        "chunk_count": len(all_chunks),
        "provider": provider,
        "dimension": dimension,
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="文件上传向量化（生成向量库 JSON）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "input",
        type=Path,
        help="输入：目录（内含 .txt/.md）或单个 .txt/.md 文件",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("vector_store.json"),
        help="输出向量库 JSON 路径（默认：当前目录下 vector_store.json）",
    )
    parser.add_argument("--provider", choices=["dashscope", "openai", "fallback"], default="fallback")
    parser.add_argument("--dimension", type=int, default=DEFAULT_DIMENSION)
    parser.add_argument("--chunk-size", type=int, default=500)
    parser.add_argument("--overlap", type=int, default=50)
    parser.add_argument("--max-files", type=int, default=None, help="仅目录模式下生效：最多处理多少个文件")

    args = parser.parse_args()

    try:
        if args.input.is_dir():
            result = vectorize_files(
                args.input,
                args.output,
                provider=args.provider,  # type: ignore[arg-type]
                dimension=args.dimension,
                chunk_size=args.chunk_size,
                overlap=args.overlap,
                max_files=args.max_files,
            )
        else:
            result = vectorize_single_file(
                args.input,
                args.output,
                provider=args.provider,
                dimension=args.dimension,
                chunk_size=args.chunk_size,
                overlap=args.overlap,
            )
    except Exception as e:
        print(f"向量化失败: {e}", file=sys.stderr)
        sys.exit(1)

    # 便于被测试捕获
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

