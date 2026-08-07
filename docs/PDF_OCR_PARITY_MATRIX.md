# PDF OCR 与章节书签实施对照矩阵

## 1. 基线与判定规则

- 实施基线：`docs/PDF_OCR_BOOKMARK_IMPLEMENTATION_PLAN.md`
- 评测清单：`docs/PDF_OCR_EVALUATION_DATASET.md`
- 评测报告：`docs/PDF_OCR_EVALUATION_REPORT.md`
- 当前判定日期：2026-07-20
- PDF 引擎基线：MuPDF 1.27.2
- 默认 OCR 路径：PP-StructureV3
- 可选低置信度回退：PaddleOCR-VL

状态只使用以下值：

| 状态 | 含义 |
| --- | --- |
| `pending` | 计划要求尚未实现，或没有可定位的实现证据 |
| `in-progress` | 已有部分实现或测试，但阶段交付物、真实样本或验收条件尚未齐全 |
| `verified` | 实现、自动化测试和该项要求的必要运行证据均已齐全 |
| `excluded` | 被计划明确排除，且实现不得引入该路径 |

`verified` 必须同时指向实现和自动化测试。仅有 Kotlin/JNI/Python 代码、文档、可编译接口、
设置页可见或单一真实样本均不能判为完成。涉及模型质量、GPU 加速、跨平台产物或独立阅读器
互操作性的项目，还必须具备对应环境的实测证据；协议 fixture 不能替代真实模型运行。

阶段状态按最弱的必需交付物判定。一个阶段内部可以有多个 `verified` 子项，但只要阶段验收仍有
缺口，阶段总状态就是 `in-progress`。

## 2. 明确排除项

| 排除路径 | 保留边界 | 状态 |
| --- | --- | --- |
| 引入 PDFBox 或第二套 PDF 页面/坐标语义 | PDF 打开、渲染、结构化文字、Outline 和导出统一由 MuPDF 承担 | `excluded` |
| 覆盖或原地修改源 PDF | 只导出到临时文件，校验后原子移动到用户选择的新路径 | `excluded` |
| 将整个 PDF 作为 Base64 JSON 发送给 sidecar | 按页渲染、请求、缓存和释放中间图像 | `excluded` |
| 将模型、运行时或可重建 OCR 缓存写入工作区 | 用户审校树写入 `.xuexue/pdf-outlines.json`；模型和页缓存写入系统缓存目录 | `excluded` |
| 向用户暴露原始模型权重作为主要方案选择 | UI 展示识别方案、能力、隐私、资源和下载信息 | `excluded` |
| 后台静默替换用户选择的识别方案或后端 | 推荐与选择分离；不可用时提示，带警告的方案仍允许用户运行 | `excluded` |
| 未经用户启用自动调用 PaddleOCR-VL | VL 仅用于显式选择或已允许的低置信度回退 | `excluded` |
| 用印刷页码作为 PDF 导航主键 | 导航始终使用从 0 开始的 PDF 物理页，印刷页码只作为证据 | `excluded` |

## 3. 阶段 0：固定评测基线

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| CI 可运行的小型原生/扫描 fixture | `PdfOcrEvaluationBaselineTest`、`ChapterStructureDetectorTest`、`DocumentAnalysisIntegrationTest` 覆盖指标、章节树和 OCR adapter | `verified` |
| 标题精确率、召回率、父子边和目标页指标 | `ChapterEvaluator` 由 `ChapterStructureDetectorTest` 和 `PdfOcrEvaluationBaselineTest` 验证 | `verified` |
| 仓库外真实样本 manifest 和 SHA-256 校验 | 已登记英文扫描小说与中文混合文字层教材；`PdfOcrExternalEvaluationTest` 校验 manifest、文件 hash 并输出报告 | `verified` |
| 覆盖中文教材、小说、论文、无目录、双栏、竖排、旋转/倾斜和混合文字层 | 目前只有英文扫描小说实跑；中文教材仅登记，其他类型未登记或未实跑 | `in-progress` |
| 按文档类型达到计划第 12 节质量目标 | 小型 fixture 和英文 12 页子集达标，但不能外推到完整固定评测集 | `in-progress` |

阶段判定：`in-progress`。

## 4. 阶段 1：MuPDF Outline 最小闭环

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| JNI 读取原生 Outline 和 Kotlin 解码 | `tools/native/mupdf_bridge.c`、`MuPdfNative.kt`、`MuPdfReaderBackend.kt`；`PdfOutlineFeatureTest.nativeOutlineAndStructuredTextRoundTripThroughMuPdf` | `verified` |
| Outline iterator 导出多层树到新 PDF | `MuPdfReaderBackend.exportOutline` 与原生桥接；`PdfOutlineFeatureTest` 覆盖替换、追加、层级和页数保持 | `verified` |
| 导出后重新打开并比较树和目标页 | `PdfOutlineFeatureTest` 通过 MuPDF 重新读取，`tools/verify_pdf_outline.py` 使用独立 PDF 对象解析器复核 | `verified` |
| 源文件不变与损坏负载拒绝 | `PdfOutlineFeatureTest` 覆盖截断 PDF/负载；`PdfSecurityFixtureTest` 覆盖拒绝后目标副本不变 | `verified` |
| CropBox、旋转、已有 Outline、加密、签名和损坏 PDF 的完整行为矩阵 | 已有 Outline、加密、权限、签名和损坏有测试；CropBox/旋转导出目标仍缺明确自动化用例 | `in-progress` |

阶段判定：`in-progress`。

## 5. 阶段 2：应用内书签模型与导航

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| `PdfOutlineDocument`、schema、指纹和 repository | `DocumentAnalysisEngine.kt`、`PdfOutlineRepository.kt`；`PdfOutlineRepositoryTest` 覆盖 stale fingerprint、手动节点保护和 schema 迁移 | `verified` |
| 书签编辑、层级调整、确认/驳回和导出过滤 | `PdfOutlineTreeEditor.kt`；`PdfOutlineTreeEditorTest` 覆盖确认、移动、层级、重复节点、拒绝节点和保护诊断 | `verified` |
| PDF 侧栏和全局目录工具共用 PDF 目录树 | `PdfOutlinePanel.kt`、`WorkbenchPdfOutlineController.kt` 已接线 | `in-progress` |
| 节点导航、编辑、持久化、恢复的 UI 闭环 | 控制器和 repository 已实现，尚缺覆盖完整用户操作序列的 UI/控制器自动化测试 | `in-progress` |
| 保留、追加、替换和仅确认节点的导出策略 | 导出模型与 native round trip 已覆盖主要策略；完整 UI 选择流程仍缺端到端测试 | `in-progress` |

阶段判定：`in-progress`。

## 6. 阶段 3：原生文字层章节识别

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| 从 `fz_stext_page` 导出块、行、样式和坐标 | MuPDF native structured-text 负载与 Kotlin 解码已实现；`PdfOutlineFeatureTest`、`DocumentAnalysisIntegrationTest` 覆盖 round trip 和页面原点归一化 | `verified` |
| `NativePdfLayoutEngine` 与按页混合文字/扫描分类 | `DocumentAnalysisEngine.kt`；`DocumentAnalysisIntegrationTest` 覆盖混合页、隐藏文字证据和分类 | `verified` |
| 标题编号、重复页眉页脚抑制和基础层级 | `ChapterStructureDetector`；`ChapterStructureDetectorTest` 覆盖中英文编号、重复页眉、合法层级和低置信度候选 | `verified` |
| 无 OCR 生成可审校章节树 | 原生版面 fixture 经章节检测器生成带证据和 review state 的树 | `verified` |

阶段判定：`verified`。

## 7. 阶段 4：PP-StructureV3 集成

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| `DocumentAnalysisEngine`、descriptor 和配置持久化 | `DocumentAnalysisEngine.kt`、`DocumentAnalysisSettings.kt`；协议和设置测试已覆盖引擎选择边界 | `verified` |
| 硬件探测、运行时检查和可用性矩阵 | `HostComputeDetector.kt`；`DocumentAnalysisIntegrationTest` 覆盖运行时、模型、内存、推荐和 CPU/GPU 识别边界 | `verified` |
| 受管理 sidecar、版本、健康、安装、升级和停止 | `PaddleOcrServiceManager.kt`；`PaddleOcrServiceManagerTest` 覆盖校验、断点下载、复用、进程生命周期、版本和引擎就绪 | `verified` |
| PP-StructureV3 请求、坐标/label/文本/阅读顺序映射 | `PpStructureV3Engine`；`DocumentAnalysisIntegrationTest.ppStructureAdapterNormalizesCoordinatesAndPreservesEvidence` | `verified` |
| 页级渲染、缓存、进度和取消 | `PdfDocumentAnalysisRunner.kt`、`PdfAnalysisCache`、`PdfPageLayoutStore`；对应 runner/cache/store 测试覆盖逐页落盘、身份隔离和取消 | `verified` |
| 中文、英文、混合文本、旋转和取消的真实集成矩阵 | 英文扫描小说 12 页已真实运行；中文教材 GPU/CPU、旋转和完整混合样本矩阵未完成 | `in-progress` |
| GPU-first 真实运行 | 选择协议已验证，Intel Arc 可被 custom device 识别；完整 PP-StructureV3 尚未在该 GPU 上完成初始化和推理 | `in-progress` |

阶段判定：`in-progress`。

## 8. 阶段 5：全文档章节推断

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| 目录页检测、条目解析和印刷页码对齐 | `ChapterStructureDetector`；测试覆盖目录证据、阿拉伯/罗马页码、页码重置和冲突降级 | `verified` |
| 标题候选评分、全文样式证据和层级构建 | 测试覆盖中英文编号、无编号标题、目录缩进、全文样式支持和无证据层级保护 | `verified` |
| 可序列化证据、置信度和待审核原因 | `PdfOutlineEvidence`、review state 和 repository round trip 均有测试 | `verified` |
| 固定评测集达到第 12 节质量目标 | 只有确定性 fixture 和单一英文扫描子集达标，文档类型矩阵不完整 | `in-progress` |

阶段判定：`in-progress`。

## 9. 阶段 6：审校与完整导出体验

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| 低置信度证据、候选/驳回可视化和确认过滤 | `PdfOutlinePanel.kt`、`PdfOutlineEvidenceOverlayTest`、`PdfOutlineTreeEditorTest` | `verified` |
| 手动修正保护和重新识别合并 | `PdfOutlineRepository.mergePreservingReview`；repository/editor 测试覆盖 `MANUAL`、`CONFIRMED` 保护 | `verified` |
| 临时文件校验、原子移动和受保护 PDF 诊断 | `PdfOutlineExportService.kt`、MuPDF export；feature/security/export service 测试覆盖校验、拒绝和并发关闭 | `verified` |
| 不编辑 JSON 的识别、审核、导航、导出 UI 全流程 | 各组件已存在，但缺少完整 Compose/控制器端到端测试和人工验收记录 | `in-progress` |
| 独立 PDF 阅读器互操作验收 | 独立对象解析器已自动复核，尚缺至少一个独立桌面阅读器的实际验收记录 | `in-progress` |

阶段判定：`in-progress`。

## 10. 阶段 7：PaddleOCR-VL 可选回退

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| `PaddleOcrVlEngine` 与按需引擎就绪 | `PaddleOcrEngines.kt` 和 sidecar 按选中引擎加载；服务测试验证只等待所选引擎 | `verified` |
| 低置信度路由、文字块回对和无法回对降级 | `ChapterStructureDetectorTest.unmatchedVlCandidatesRemainCandidatesEvenWithHighConfidence` 验证无法回对时不自动确认 | `in-progress` |
| 仅在用户启用时自动回退 | `DocumentAnalysisSettings` 保存回退开关，sidecar 不会仅因健康探测加载 VL | `in-progress` |
| PP-StructureV3 与 VL 的固定评测集 A/B 报告 | 当前没有 PaddleOCR-VL 真实 A/B 结果 | `pending` |

阶段判定：`in-progress`。

## 11. 阶段 8：性能、打包与恢复

| 计划要求 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| sidecar 安装、校验、升级和下载恢复 | `package.py`、`package.sh`、`PaddleOcrServiceManager`；服务测试覆盖 SHA-256、断点下载、安装复用和损坏清理 | `verified` |
| CPU/CUDA/Intel GPU 打包定义 | `requirements*.txt` 与 `package.py --backend` 提供 CPU、CUDA 11.8/12.6/12.9 和 Intel custom-device 变体 | `in-progress` |
| Linux、Windows、macOS 实际产物启动验收 | 构建脚本和 launcher 已存在；Windows/macOS 尚无目标机产物和启动证据 | `in-progress` |
| GPU-first 选择且不静默回退 CPU | Python sidecar 测试和 `PaddleOcrServiceManagerTest` 覆盖 `auto` 优先加速设备、显式 GPU 失败和拒绝复用 CPU sidecar | `verified` |
| 1,000 页级缓存、恢复和 schema 迁移 | `PdfLongDocumentBenchmarkTest` 验证 1,001 页写入、恢复和缓存命中；repository/cache 测试覆盖迁移和损坏恢复 | `verified` |
| 1,000 页真实 OCR 长时任务 | 当前基准不执行真实 OCR，不能证明端到端耗时或模型内存稳定性 | `pending` |
| OCR 后台运行时首屏/瓦片 P95 回归不超过 10% | 已有渲染优先级和资源边界测试，尚无 OCR 并发下的对照性能报告 | `in-progress` |
| 取消后 2 秒内停止调度并回收资源 | runner 测试证明停止后续页调度和关闭等待；尚缺真实 sidecar 进程与模型内存的时限实测 | `in-progress` |

阶段判定：`in-progress`。

## 12. GPU-first 专项矩阵

| 要求 | 自动化证据 | 真实运行证据 | 状态 |
| --- | --- | --- | --- |
| `auto` 优先 Paddle 实际报告的 CUDA/XPU/custom device | `test_paddleocr_sidecar.py` 覆盖 CUDA、custom device 和 Intel `intel_gpu:0` | Intel Arc B570 已被 Paddle custom device 枚举 | `verified` |
| 有可用加速设备时不得复用 CPU sidecar | Python `runtime_matches_requested_device` 测试与 `PaddleOcrServiceManagerTest.autoRejectsCpuSidecarWhenAnAcceleratorIsReported` | 健康协议可区分 requested/device/backend | `verified` |
| 显式 GPU 请求不可静默回退 CPU | Python sidecar 和 Kotlin service manager 均有失败路径测试 | 尚未覆盖所有 CUDA/XPU/custom 驱动组合 | `verified` |
| OCR 缓存按实际 device/backend 隔离 | `PdfAnalysisCacheTest` 覆盖 CPU 与 CUDA fingerprint 隔离 | 真实 GPU OCR 复跑尚未完成 | `verified` |
| Intel Arc 完成 PP-StructureV3 初始化和页面推理 | custom-device 插件已能执行基础 Tensor/slice 路径 | PP-StructureV3 仍需重新验证初始化、首个页面和完整 12 页教材子集 | `in-progress` |
| 中文教材 CPU/GPU 对比报告 | manifest 和本机 555 页文件已校验 | 尚无 CPU/GPU 首次运行、P95、RSS/显存和质量指标报告 | `pending` |

GPU-first 的当前判定是：策略和协议 `verified`，真实 Intel GPU 模型链路 `in-progress`。
在 PP-StructureV3 完成初始化和至少一个真实页面推理之前，不得宣称“GPU 加速已完成”。

## 13. 当前实现判定

| 范围 | 判定 |
| --- | --- |
| MuPDF 结构化文字与原生 Outline 基础能力 | 已形成可测试闭环；阶段 1 仍缺 CropBox/旋转矩阵 |
| 应用内书签模型、缓存、持久化和章节算法 | 核心逻辑已验证；完整 UI 工作流仍需端到端验收 |
| PP-StructureV3 sidecar 协议和 CPU 真实样本 | 英文扫描子集已真实运行并可缓存复跑 |
| GPU-first 设备选择 | 自动化测试已验证优先加速设备且不静默回退 CPU |
| Intel Arc PP-StructureV3 | custom-device 基础算子已推进，完整模型初始化和推理尚未完成 |
| PaddleOCR-VL | adapter 和按需加载边界存在，真实 A/B 质量证据缺失 |
| 完整固定评测集 | 未完成，不能宣布第一版达到生产质量门禁 |
| 跨平台发布 | 构建路径已实现，Windows/macOS 实际产物尚未验收 |

## 14. 阶段门与完成定义

第一版只有在以下条件全部满足后才能从整体 `in-progress` 改为 `verified`：

1. 补齐中文教材、论文、无目录、双栏、竖排、旋转/倾斜和混合文字层样本，并按类型报告指标。
2. 在真实 Intel GPU 或受支持 CUDA GPU 上完成 PP-StructureV3 初始化、页面推理、缓存复跑和取消。
3. 完成中文教材 CPU/GPU 对比，记录标题质量、首页耗时、单页 P95、进程 RSS 和 GPU 显存。
4. 完成 PaddleOCR-VL 启用/禁用 A/B，证明只在明确类型上改善，且无法回对的结果保持待审核。
5. 完成 CropBox/旋转 Outline 导出测试、完整 Compose/控制器用户流程测试和独立阅读器验收。
6. 完成 1,000 页真实 OCR 长时任务及中断恢复，证明不会在内存中常驻全量页面结果。
7. 在 Linux、Windows 和 macOS 上构建、校验并启动对应 sidecar 产物。
8. 证明 OCR 并发期间 PDF 首屏和可见瓦片 P95 回归不超过 10%，取消后 2 秒内停止新页调度。
9. 使用 Java 26 通过仓库质量链：

```bash
./gradlew ktlintFormat
./gradlew ktlintCheck detektMainDesktop detektTestDesktop desktopTest koverXmlReport
```

当前整体状态：`in-progress`。
