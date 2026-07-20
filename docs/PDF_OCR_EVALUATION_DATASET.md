# PDF OCR 评测基线

本仓库内的可重复评测使用小型 fixture；真实扫描书籍保留在仓库外，避免把大文档和版权材料写入源码仓库。仓库只保存
来源、SHA-256、物理页和预期章节树的 manifest。

| 类型 | 当前 fixture/来源 | 覆盖内容 |
| --- | --- | --- |
| 原生 Outline | `PdfOutlineFeatureTest.outlinePdf` | 多层 Outline、替换、追加、页数保持、独立 PDF 对象解析 |
| 原生文字层 | `ChapterStructureDetectorTest` | 编号标题、重复页眉、低置信度候选、目录页与印刷页码对齐 |
| 版面 OCR adapter | `DocumentAnalysisIntegrationTest` | loopback HTTP、标题 label、阅读顺序、像素坐标归一化、置信度 |
| 缓存 | `PdfAnalysisCacheTest` | 页级读写、页面隔离、DPI 身份失效 |
| 导出安全边界 | `PdfSecurityFixtureTest` | 真实 R2 加密、权限限制、CMS 签名和拒绝后的源文件不变 |

## 外部扫描样本

当前已登记一份可公开下载的公共领域扫描样本，以及一份本机用户提供的中文教材样本：

| manifest | 来源 | 页面范围 | 状态 |
| --- | --- | ---: | --- |
| `docs/pdf-evaluation-manifests/adventures-of-sherlock-holmes-1892.json` | Internet Archive, 1892 | 12 个章节起始页 / 353 页 | PP-StructureV3 真实运行 |
| `docs/pdf-evaluation-manifests/mst-math-2027-mixed-text-layer.json` | 本机用户提供的中文教材 | 12 个章节起始页 / 555 页 | 已登记，GPU/CPU 矩阵待实跑 |

manifest 中的 `pages` 使用从 0 开始的 PDF 物理页序号，`expected` 使用标题、目标物理页和层级。评测测试会校验 PDF
SHA-256，逐页经过 MuPDF 渲染、PaddleOCR adapter、页级缓存和同一个 `ChapterEvaluator`，并输出标题精确率/召回率、
层级边、目标页、首次运行耗时、P95、sidecar RSS 和缓存命中数。12 页是可审计的章节起始页子集，不应被描述为整本
353 页的 OCR 准确率；中文教材样本已登记，论文、双栏、竖排、旋转和其他混合文字层样本仍需继续登记。

下载样本后运行真实评测：

```bash
XUEXUE_PDF_EVAL_PDF=/path/to/Adventures_of_Sherlock_Holmes.pdf \
XUEXUE_PDF_EVAL_MANIFEST="$PWD/docs/pdf-evaluation-manifests/adventures-of-sherlock-holmes-1892.json" \
XUEXUE_PDF_EVAL_REPORT="$PWD/tmp/pdfs/adventures-of-sherlock-holmes-1892-report.json" \
XUEXUE_PADDLEOCR_PYTHON=/path/to/paddleocr-python \
tools/run_pdf_ocr_evaluation.sh
```

长文档页级恢复基准：

```bash
XUEXUE_PDF_BENCHMARK_REPORT="$PWD/tmp/pdfs/long-document-benchmark.json" \
tools/run_pdf_long_document_benchmark.sh
```

章节质量指标由 `ChapterEvaluator` 计算：标题精确率、标题召回率、父子边准确率和目标页准确率。固定评测集的
预期树应使用 `PdfOutlineDocument` 保存，文档类型至少分为中文教材、小说、论文、无目录、双栏、竖排、旋转/倾斜
和混合文字层；大样本不进入 Git，但应在 CI 任务中通过相同 evaluator 输出按类型报告。

运行当前小型基线：

```bash
JAVA_HOME=/usr/lib/jvm/java-26-openjdk \
PATH=/usr/lib/jvm/java-26-openjdk/bin:$PATH \
./gradlew desktopTest
```

导出 fixture 还会调用 `tools/verify_pdf_outline.py`，用独立于 MuPDF JNI 的 PDF 对象解析器检查书签标题和目标页。
