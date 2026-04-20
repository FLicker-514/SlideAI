# document_service: PDF 解析（MinerU + 图片描述），供 parse_to_dir 与 Java document-service 调用
# 若未在 document_service/config.py 配置 MINERU_TOKEN，可保留根目录 config.py 中的 MINERU_TOKEN，parse_to_dir 从 document-service 目录运行时将使用根 config
from .parser import FileParserService, create_parser, ParseResult

__all__ = ["FileParserService", "create_parser", "ParseResult"]
