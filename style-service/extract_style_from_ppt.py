#!/usr/bin/env python3
"""
从 PPT 提取风格：模仿 ppt_keep_background_only 导出纯背景 HTML，再对该 HTML 渲染截图得到 16:9 的 PNG。
供 style-service 调用，输出到 Userdata/{userId}/ 下：{styleId}-background.png（统一 1920x1080）。

步骤：1) 就地移除第一页所有形状 → 导出 HTML 并去 Spire 水印  2) 用 Playwright 打开 HTML，视口 16:9 截图保存 PNG。

依赖：pip install spire-presentation  [playwright 可选，未安装时仅输出 HTML]
"""

import argparse
import re
import sys
import tempfile
from pathlib import Path

# 与 ppt_keep_background_only.py 相同：移除 Spire 试用版在 HTML 中插入的水印
SPIRE_WATERMARK_PATTERN = re.compile(
    r'<text[^>]*>[\s\S]*?Evaluation Warning\s*:\s*The document was created with Spire\.Presentation for Python[\s\S]*?</text>',
    re.IGNORECASE,
)

# 截图统一 16:9
VIEWPORT_WIDTH = 1920
VIEWPORT_HEIGHT = 1080

try:
    from spire.presentation import FileFormat, Presentation
except ImportError:
    Presentation = None
    FileFormat = None

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sync_playwright = None


def remove_all_shapes_from_slide(slide):
    """移除幻灯片上所有形状（文本框、图片、图形等），仅保留幻灯片背景。与 ppt_keep_background_only 一致。"""
    shapes = slide.Shapes
    for i in range(shapes.Count - 1, -1, -1):
        try:
            shapes.RemoveAt(i)
        except Exception:
            try:
                shapes.Remove(shapes[i])
            except Exception:
                raise


def _inject_fill_viewport_script():
    """注入脚本：使页面主内容铺满 16:9 视口（cover），减少留白。"""
    return """
    (function() {
      var vw = %d, vh = %d;
      var doc = document;
      doc.documentElement.style.margin = doc.documentElement.style.padding = '0';
      doc.documentElement.style.width = doc.body.style.width = vw + 'px';
      doc.documentElement.style.height = doc.body.style.height = vh + 'px';
      doc.body.style.margin = doc.body.style.padding = '0';
      doc.body.style.overflow = 'hidden';
      var root = doc.body.firstElementChild;
      if (!root) return;
      var rect = root.getBoundingClientRect();
      var w = rect.width || root.offsetWidth || 1, h = rect.height || root.offsetHeight || 1;
      var scale = Math.max(vw / w, vh / h);
      root.style.position = 'absolute';
      root.style.left = '50%%';
      root.style.top = '50%%';
      root.style.transform = 'translate(-50%%, -50%%) scale(' + scale + ')';
      root.style.transformOrigin = 'center center';
    })();
    """ % (VIEWPORT_WIDTH, VIEWPORT_HEIGHT)


def html_to_png_16_9(html_path: Path, png_path: Path) -> bool:
    """将 HTML 文件用 Playwright 渲染并截图为 16:9 PNG，内容铺满视口以减少留白。"""
    if sync_playwright is None:
        return False
    html_path = html_path.resolve()
    png_path = png_path.resolve()
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch()
            context = browser.new_context(
                viewport={"width": VIEWPORT_WIDTH, "height": VIEWPORT_HEIGHT},
                device_scale_factor=1,
            )
            page = context.new_page()
            page.goto(html_path.as_uri())
            page.evaluate(_inject_fill_viewport_script())
            page.screenshot(path=str(png_path), type="png")
            browser.close()
        return True
    except Exception as e:
        print(f"警告：HTML 渲染截图失败: {e}", file=sys.stderr)
        return False


def main():
    parser = argparse.ArgumentParser(description="从 PPT 提取第一页纯背景，导出 HTML 并截图 16:9 PNG")
    parser.add_argument("--ppt", required=True, type=Path, help="PPT/PPTX 文件路径")
    parser.add_argument("--style-id", required=True, help="风格 ID，用于输出文件名前缀")
    parser.add_argument("--output-dir", required=True, type=Path, help="输出目录（Userdata/用户ID）")
    args = parser.parse_args()

    if not args.ppt.exists():
        print(f"错误：文件不存在 {args.ppt}", file=sys.stderr)
        sys.exit(1)
    if Presentation is None:
        print("错误：请安装 spire-presentation (pip install spire-presentation)", file=sys.stderr)
        sys.exit(1)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    prefix = args.style_id

    ppt = Presentation()
    ppt.LoadFromFile(str(args.ppt.resolve()))
    if ppt.Slides.Count == 0:
        print("错误：PPT 无幻灯片", file=sys.stderr)
        ppt.Dispose()
        sys.exit(1)

    # 与 ppt_keep_background_only 一致：就地移除第一页所有形状，只保留背景
    remove_all_shapes_from_slide(ppt.Slides[0])

    # 仅保留第一页：删除其余页，再导出 HTML
    while ppt.Slides.Count > 1:
        ppt.Slides.RemoveAt(ppt.Slides.Count - 1)

    # 导出纯背景 HTML 到临时文件，并去水印
    with tempfile.NamedTemporaryFile(mode="w", suffix=".html", delete=False, encoding="utf-8") as f:
        temp_html = Path(f.name)
    try:
        ppt.SaveToFile(str(temp_html.resolve()), FileFormat.Html)
        html_content = temp_html.read_text(encoding="utf-8")
        new_content = SPIRE_WATERMARK_PATTERN.sub("", html_content)
        temp_html.write_text(new_content, encoding="utf-8")
    finally:
        ppt.Dispose()

    # 对 HTML 渲染截图，得到 16:9 PNG
    png_path = args.output_dir / f"{prefix}-background.png"
    if html_to_png_16_9(temp_html, png_path):
        temp_html.unlink(missing_ok=True)
    else:
        # 未安装 playwright 或截图失败：将 HTML 复制到输出目录，便于排查
        fallback_html = args.output_dir / f"{prefix}-background.html"
        if temp_html.exists():
            fallback_html.write_text(temp_html.read_text(encoding="utf-8"), encoding="utf-8")
        temp_html.unlink(missing_ok=True)
        print("提示：未生成 PNG，已保留 HTML。安装 playwright 并执行 playwright install chromium 后可生成 16:9 PNG。", file=sys.stderr)

    print("OK")


if __name__ == "__main__":
    main()
