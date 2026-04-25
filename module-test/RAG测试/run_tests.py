#!/usr/bin/env python3
"""
scripts/RAG测试 自检脚本

用于验证：
1) rag_vectorize_upload.py 能对输入生成向量库 JSON
2) rag_problem_retrieval.py 能对 query 返回 top_k 最匹配文本块

默认使用 provider='fallback'，保证无外部 API 也能稳定跑通。
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path


def assert_true(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def main() -> int:
    root_dir = Path(__file__).parent
    sys.path.insert(0, str(root_dir))
    import rag_vectorize_upload as vecmod  # type: ignore

    with tempfile.TemporaryDirectory() as td:
        td_path = Path(td)
        input_dir = td_path / "input"
        input_dir.mkdir(parents=True, exist_ok=True)

        # 可控样本：确保兜底关键词检索能区分
        (input_dir / "ai.md").write_text(
            "人工智能是计算机科学的一个分支。它研究如何让机器表现出智能。",
            encoding="utf-8",
        )
        (input_dir / "ml.md").write_text(
            "机器学习是实现人工智能的重要方法。深度学习是机器学习的一个子领域。",
            encoding="utf-8",
        )

        vector_store = td_path / "vector_store.json"
        summary = vecmod.vectorize_files(
            input_dir,
            vector_store,
            provider="fallback",
            dimension=128,  # 速度优先
            chunk_size=2000,
            overlap=0,
        )

        assert_true(vector_store.exists(), "vector store should exist")

        # retrieval 1：人工智能
        payload1 = _run_retrieval(
            root_dir,
            vector_store,
            query="人工智能的分支是什么？",
            top_k=1,
            use_keyword_fallback=True,
        )
        assert_true(len(payload1["results"]) == 1, "top_k=1 should return 1 result")
        assert_true("人工智能" in payload1["results"][0]["text"], "expected AI chunk to match")

        # retrieval 2：机器学习
        payload2 = _run_retrieval(
            root_dir,
            vector_store,
            query="机器学习的子领域是什么？",
            top_k=1,
            use_keyword_fallback=True,
        )
        assert_true("机器学习" in payload2["results"][0]["text"], "expected ML chunk to match")

    print("RAG_TESTS_OK")
    return 0


def _run_retrieval(
    scripts_dir: Path,
    vector_store: Path,
    *,
    query: str,
    top_k: int,
    use_keyword_fallback: bool,
) -> dict:
    cmd = [
        sys.executable,
        str(scripts_dir / "rag_problem_retrieval.py"),
        "-v",
        str(vector_store),
        "-q",
        query,
        "--top-k",
        str(top_k),
        "--provider",
        "fallback",
        "--dimension",
        "128",
    ]
    if use_keyword_fallback:
        cmd.append("--use-keyword-fallback")

    completed = subprocess.run(cmd, capture_output=True, text=True)
    if completed.returncode != 0:
        raise RuntimeError(f"retrieval failed: {completed.stderr}\nstdout={completed.stdout}")

    return json.loads(completed.stdout.strip())


if __name__ == "__main__":
    raise SystemExit(main())

