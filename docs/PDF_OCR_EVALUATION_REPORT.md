# PDF OCR 评测基线报告

日期：2026-07-20

本报告记录仓库内可在 CI 中重复运行的小型结构化 fixture。它验证章节候选、层级组装、证据页和指标计算的闭环，不代表真实扫描书籍的生产准确率。真实教材、小说、论文、双栏、竖排和旋转样本仍需在仓库外维护，并使用同一个 `ChapterEvaluator` 输出分类报告。

运行命令：

```bash
JAVA_HOME=/usr/lib/jvm/java-26-openjdk \
PATH=/usr/lib/jvm/java-26-openjdk/bin:$PATH \
./gradlew :desktopTest --tests 'dev.xuexue.platform.PdfOcrEvaluationBaselineTest'
```

| fixture | 标题精确率 | 标题召回率 | 父子边准确率 | 目标页准确率 |
| --- | ---: | ---: | ---: | ---: |
| native-numbered | 100% | 100% | 100% | 100% |
| cjk-hierarchy | 100% | 100% | 100% | 100% |
| english-numbered | 100% | 100% | 100% | 100% |

CI 测试入口：`PdfOcrEvaluationBaselineTest`。该基线故意保持小而确定，不能用来宣称第 12 节中针对真实文档类型的 95%/90%/98% 目标已经达成。

## 真实扫描书籍评测

使用 `docs/pdf-evaluation-manifests/adventures-of-sherlock-holmes-1892.json`，从 Internet
Archive 下载的 1892 年公共领域扫描小说。PDF 共 353 页，本次抽取 12 个章节起始物理页
(`13, 41, 72, 96, 128, 152, 181, 206, 237, 263, 289, 318`)；PDF SHA-256 为
`ebe7ee4d8e53cbef879d19af961ff02c8508bd5af77716ae9f374804e34641bd`。样本和模型权重保留在仓库外，
仓库只保存 manifest 和评测入口。

运行命令：

```bash
XUEXUE_PDF_EVAL_PDF=/path/to/Adventures_of_Sherlock_Holmes.pdf \
XUEXUE_PDF_EVAL_MANIFEST="$PWD/docs/pdf-evaluation-manifests/adventures-of-sherlock-holmes-1892.json" \
XUEXUE_PDF_EVAL_REPORT="$PWD/tmp/pdfs/adventures-of-sherlock-holmes-1892-report.json" \
tools/run_pdf_ocr_evaluation.sh
```

本次使用 PP-StructureV3 sidecar `3.x-sidecar`，CPU 单线程，200 DPI；health 检查报告
`pp-structure-v3=true`、`paddleocr-vl=false`。结果来自 MuPDF 渲染、PP-StructureV3 adapter、
页级缓存和同一个 `ChapterEvaluator`：

| 指标 | 首次运行 | 缓存复跑 |
| --- | ---: | ---: |
| 页数 | 12 | 12 |
| 模型调用 | 12 | 0 |
| 缓存命中 | 0 | 12 |
| 首页耗时 | 26,093 ms | 1 ms |
| 单页耗时 P95 | 27,298 ms | 1 ms |
| sidecar 峰值 RSS | 3,274,158,080 bytes | 3,261,173,760 bytes |

| 标题精确率 | 标题召回率 | 父子边准确率 | 目标页准确率 | 实际/预期标题 |
| ---: | ---: | ---: | ---: | ---: |
| 100% | 100% | 100% | 100% | 12 / 12 |

12 个标题全部处于正确物理页和根级层级；其中 2 个达到自动确认阈值，其余保留为待审校的
`CANDIDATE`。这组结果只代表 353 页英文扫描小说中的 12 页章节起始页，不能外推为整本书或其他文档类型的生产准确率。
后续通过 `tools/run_pdf_ocr_evaluation.sh` 生成的 JSON 报告会同时记录 sidecar 的
`requested/device/backend`，并按实际运行时隔离页缓存；本段旧基线仍是 CPU 运行结果。

## 长文档页级恢复

`PdfLongDocumentBenchmarkTest` 使用 1,001 页确定性版面 fixture，验证页级落盘、模拟中断后恢复和
缓存命中，不执行 OCR：写入 522 ms，恢复读取 56 ms，1,001/1,001 页恢复，1,001/1,001 页缓存命中，
测试 worker 峰值 RSS 为 363,790,336 bytes。该结果证明的是存储边界和恢复路径，不替代真实 OCR 的内存
或端到端耗时基线。

## PDF 安全场景证据

`PdfSecurityFixtureTest` 使用标准 PDF R2/RC4 算法生成真实加密文档，并包含一个带 detached
SHA-256 CMS 签名和有效 `ByteRange` 的静态 PDF fixture。测试覆盖：

- 需要用户密码的 PDF：MuPDF 在认证前拒绝 Outline 导出。
- 用户密码可以打开但没有编辑/组装权限的 PDF：MuPDF 拒绝 Outline 导出。
- 带数字签名字段的 PDF：MuPDF 拒绝会使签名失效的 Outline 导出。

测试还确认导出后端预复制的目标文件在拒绝后仍与源文件逐字节相同。CMS fixture 在生成时由
OpenSSL 验证 detached 签名；当前 CI 测试验证的是 MuPDF 的保护行为，不评价证书信任链。

运行命令：

```bash
JAVA_HOME=/usr/lib/jvm/java-26-openjdk \
PATH=/usr/lib/jvm/java-26-openjdk/bin:$PATH \
./gradlew :desktopTest --tests 'dev.xuexue.platform.PdfSecurityFixtureTest'
```

## 环境和边界

真实评测使用的是仓库外受管理的 PP-StructureV3 sidecar；常规 CI 不需要安装 Paddle 运行时，仍只运行
确定性 fixture 和协议测试。中文混合文字层样本已登记在
`docs/pdf-evaluation-manifests/mst-math-2027-mixed-text-layer.json`，但当前机器只有 Paddle CPU wheel，
尚未完成该样本的 GPU/CPU 矩阵报告。当前仍没有 PaddleOCR-VL 的 A/B 结果，也没有论文、无目录书籍、双栏、
竖排或旋转/倾斜样本，因此第 12 节按文档类型设定的完整质量门禁仍未全部满足。

识别 runner 已按页写入 `PdfPageLayoutStore`，章节检测器消费可重复的页源，不在内存中保留全量
`PdfPageLayout`。长文档基准证明页级缓存和恢复可用，但不证明 1,000 页真实 OCR 的端到端性能；真实
sidecar 的 3.27 GB 峰值 RSS 也说明发布前仍需完成模型打包、资源限额、GPU/CPU 方案和首屏回归验证。
