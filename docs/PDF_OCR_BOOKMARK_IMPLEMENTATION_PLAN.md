# PDF 扫描件章节识别与书签实施计划

## 1. 目标

本计划用于在 XueXue 中实现一条可审校、可缓存、可导出的 PDF 章节识别链路：

1. 优先读取 PDF 已有的原生 Outline，不对已有可用结构重复识别。
2. 对有可用文字层的 PDF，从 MuPDF 结构化文本中提取标题候选。
3. 对扫描页使用 PP-StructureV3 做 OCR、版面分析和阅读顺序恢复。
4. 将 PaddleOCR-VL 作为低置信度页面的可选二次判定，不作为默认全文档路径。
5. 通过目录页、章节编号、版式特征和跨页一致性组装章节树。
6. 在应用内先审校书签，再使用 MuPDF 导出一份带 Outline 的新 PDF。

成功标准不是“OCR 能返回文字”，而是用户能够快速得到可信的章节树，并在
导出前修正个别错误。

## 当前验证状态（2026-07-20）

本计划中的 MuPDF Outline 读取、应用内树模型、原生文字层分析、页级缓存与恢复、审校接线和导出
安全防护已经有当前工作区代码及 CI fixture 证据。`PdfSecurityFixtureTest` 还覆盖了真实 R2 加密、
权限限制和 CMS 签名 PDF，并确认被拒绝的目标副本没有被改写。

当前新增的外部证据如下：

- Internet Archive 的 353 页英文扫描小说抽取 12 个章节起始页，使用真实 PP-StructureV3 CPU
  sidecar 运行；标题精确率、召回率、父子边准确率和目标页准确率均为 100%。首次运行 12/12
  调用模型，单页 P95 为 27,298 ms、sidecar 峰值 RSS 为 3,274,158,080 bytes；缓存复跑 12/12
  命中，单页 P95 为 1 ms。
- 1,001 页确定性页级基准完成写入、模拟中断恢复和缓存复跑：1,001/1,001 页恢复与命中，写入
  522 ms、读取 56 ms，worker 峰值 RSS 为 363,790,336 bytes。该基准验证缓存边界，不代表
  1,001 页真实 OCR 的端到端性能。
- 章节检测器对扫描书籍 OCR 常见的 `Adventure` 前缀和丢失的首字母 `T` 做了归一化；无编号且
  没有目录证据的标题保持根级，目录缩进和显式编号仍可构建子层级。
- sidecar 现在按用户选择的引擎等待对应模型就绪，PaddleOCR-VL 只在显式选择或允许回退时加载；
  设置页首次检测只探测现有 endpoint，不会因为查看硬件信息自动下载或启动模型。健康响应记录
  sidecar 版本、引擎状态和进程 RSS，CPU/GPU 可用性矩阵会保留“运行时、模型、内存和推荐”之间的区别。
- 识别、导出和目录加载任务都追踪全部存活 Job；替换任务或关闭应用时会取消并等待旧任务完成，再释放
  MuPDF backend。导出完整审校树与“仅已确认节点”两种策略已分开，移动节点只会把实际移动的节点标记为手动。
- sidecar 发布增加了不依赖 Unix `zip`/`sha256sum` 的 `tools/paddleocr-sidecar/package.py`，并保留
  `package.sh` 兼容入口；当前仍只有 Linux/CPU 环境的实际运行证据，未在 Windows/macOS 目标机完成构建和启动验收。
- sidecar 设备选择已改为默认 `auto`：只有 Paddle runtime 实际报告可用的 CUDA/XPU/custom device
  才会启用 GPU，显式 `gpu`/`cuda` 请求在后端不可用时会失败，不再静默回退 CPU。健康响应会记录
  请求设备、实际设备、后端、编译后端、可用设备和 `device_available`；当前本机实测仍是
  `paddlepaddle==3.3.1` CPU 包，Intel Arc/SYCL 不会被误报为 PaddleOCR GPU。
- sidecar 打包已提供 `cpu`、`cuda118`、`cuda126` 和 `cuda129` 变体；CUDA 变体使用同版本
  `paddlepaddle-gpu==3.3.1`。Intel Arc 若要加速 PP-StructureV3，仍需另行提供 Paddle custom-device
  或 OpenVINO/ONNX 引擎，不能仅凭操作系统显卡枚举完成。

当前工作区质量链中，`ktlintCheck`、`detektTestDesktop`、`desktopTest`、`koverXmlReport` 和强制重跑的
`detektMainDesktop` 均已通过，detekt 报告为 0 findings。PI 子模块的 detekt 仍会提示分析器发现既有
compiler errors、因此分析准确性受限；这不是任务失败，也未通过扩展 baseline 隐藏任何问题。

因此 PP-StructureV3 的“真实运行链路”已有单一英文扫描样本证据，但第一版仍不能标记为完成：
中文混合文字层教材已登记在 `docs/pdf-evaluation-manifests/mst-math-2027-mixed-text-layer.json`，但当前机器只有
Paddle CPU wheel，尚未完成该样本的 GPU/CPU 模型启动矩阵和最终报告。当前仍没有论文、无目录、双栏、竖排、
旋转/倾斜样本，也没有 PaddleOCR-VL A/B 结果、整本 OCR 长时测试和 Windows/macOS sidecar 实际产物。
这些条件不能由 12 页子集、跨平台打包脚本或确定性页级 fixture 替代。

## 2. 已冻结的技术决策

| 领域 | 决策 | 原因 |
| --- | --- | --- |
| PDF 引擎 | MuPDF 1.27.2 | 项目已有文档 actor、display list、结构化文本和 JNI 桥接 |
| 主识别引擎 | PP-StructureV3 | 能返回标题类别、坐标、OCR 结果和阅读顺序，适合构建可解释书签 |
| 高精度回退 | PaddleOCR-VL | 仅用于复杂版面、古籍或低置信度页，输出必须回对到原始文字块 |
| 章节推断 | 确定性规则与全文档一致性结合 | 防止凭单页文本或模型生成结果猜测层级 |
| 应用内存储 | `.xuexue/pdf-outlines.json` | 保存经用户确认的章节树和手动修正 |
| 重建缓存 | 系统缓存目录 | OCR 与版面块可重建，不应混入需要备份的工作区数据 |
| 硬件与模型选择 | 检测可运行方案，但不强制接受推荐 | 把配置检测作为决策信息，保留用户的性能、隐私和质量选择权 |
| PDF 导出 | MuPDF Outline iterator + `pdf_save_document` | 直接复用现有 PDF 引擎、页面和坐标语义 |
| 额外 PDF 引擎 | 不引入 | 不使用 PDFBox，避免两套 PDF 语义、依赖和故障面 |

PP-StructureV3 和 PaddleOCR-VL 是“文档识别引擎”，不是将模型权重文件直接暴露给用户。
用户界面展示的应是“快速本地”、“高精度中文”、“复杂版面”等方案及其隐私、
硬件、下载体积和功能信息。

## 3. 当前基线与缺口

现有 PDF 管线已经具备以下基础：

- `MuPdfDocumentSession` 串行拥有 `fz_document`，页面 display list 可被渲染池复用。
- `MuPdfPageSnapshot` 缓存了 display list 和 `fz_stext_page`。
- `PdfReaderBackend` 已提供页数、尺寸、渲染、搜索、文本、选区和链接接口。
- `WorkbenchTaskController` 已提供可取消、可重试的后台任务呈现。
- PDF 注释已有 `.xuexue` 持久化模式，可作为书签仓库的原子写入参考。

实施前需要补齐的缺口：

- JNI 只把结构化文本展平为字符串，没有导出块、行、字号和坐标。
- JNI 没有暴露 `fz_load_outline`、Outline iterator 或 PDF 保存。
- `PdfSidePanel` 只有缩略图和注释，全局“目录”工具只支持 Markdown。
- 没有文档分析引擎抽象、页级缓存或章节树审校状态。
- HTTP Range 渐进文档在下载完成前不能交给需要完整文件的识别或导出流程。

## 4. 目标架构

```text
PDF document
  |
  +-- MuPDF native outline ------------------------------+
  |                                                      |
  +-- MuPDF structured text -- NativePdfLayoutEngine ----+--> normalized page layout
  |                                                      |
  +-- MuPDF OCR raster -- PpStructureV3Engine -----------+
                              |
                              +-- PaddleOcrVlEngine (low-confidence fallback)

normalized page layouts
  -> heading candidate extraction
  -> TOC and printed-page reconciliation
  -> hierarchy construction and confidence scoring
  -> reviewable PdfOutlineDocument
  -> application navigation and durable corrections
  -> MuPDF outline export to a new PDF
```

分层边界：

- MuPDF 负责 PDF 打开、页面坐标、原生文本、原生 Outline、渲染和导出。
- 文档分析引擎只接收稳定的页面输入，返回标准化版面块，不访问 Compose 状态。
- 章节检测器是可单元测试的纯 Kotlin 组件，不直接调用 OCR 或 MuPDF。
- Controller 编排任务、缓存和持久化；Compose 只展示状态并提交用户操作。

## 5. 核心数据协议

### 5.1 页面版面结果

```kotlin
data class PdfPageLayout(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val blocks: List<PdfLayoutBlock>,
    val engine: AnalysisProvenance,
)

data class PdfLayoutBlock(
    val id: String,
    val order: Int,
    val type: PdfLayoutBlockType,
    val text: String,
    val bounds: PdfNormalizedRect,
    val detectionConfidence: Float?,
    val recognitionConfidence: Float?,
    val style: PdfTextStyle?,
)
```

`PdfLayoutBlockType` 至少包含 `DOCUMENT_TITLE`、`SECTION_TITLE`、`TEXT`、`TOC`、
`PAGE_NUMBER`、`HEADER`、`FOOTER`、`LIST`、`FIGURE`、`TABLE` 和 `UNKNOWN`。
各引擎的原始 label 在 adapter 内映射，不渗透到章节算法。

### 5.2 应用内书签树

```kotlin
data class PdfOutlineDocument(
    val schemaVersion: Int,
    val relativePath: String,
    val documentFingerprint: String,
    val analysis: AnalysisProvenance?,
    val nodes: List<PdfOutlineNode>,
)

data class PdfOutlineNode(
    val id: String,
    val title: String,
    val destination: PdfOutlineDestination,
    val source: PdfOutlineSource,
    val confidence: Float?,
    val reviewState: PdfOutlineReviewState,
    val evidence: PdfOutlineEvidence?,
    val children: List<PdfOutlineNode>,
)

data class PdfOutlineDestination(
    val pageIndex: Int,
    val topNormalized: Float? = null,
)
```

关键不变量：

- `pageIndex` 始终为从 0 开始的 PDF 物理页序号。
- 印刷页码只作为识别证据，不作为导航主键。
- 坐标在 Kotlin 层使用页面左上为原点的归一化坐标；仅在 MuPDF 边界转换。
- `MANUAL` 和 `CONFIRMED` 节点不得被后台重新识别覆盖。
- 自动识别的新结果先作为候选集保存，经显式合并才替换当前书签树。

### 5.3 文档分析引擎

```kotlin
interface DocumentAnalysisEngine {
    val descriptor: DocumentAnalysisEngineDescriptor

    suspend fun analyzePage(request: DocumentAnalysisRequest): PdfPageLayout
}
```

`descriptor` 描述引擎 ID、版本、模型版本、语言、版面、竖排、表格、公式、手写、
CPU/GPU、隐私、许可证和下载体积。第一版提供：

- `NativePdfLayoutEngine`：处理原生文字层。
- `PpStructureV3Engine`：处理扫描页，默认引擎。
- `PaddleOcrVlEngine`：可选回退，仅处理需要二次判定的页面。

### 5.4 硬件探测与模型可用性

硬件探测和模型选择是两个独立的概念：探测负责告知用户“能否运行”、“以什么代价运行”，
选择仍然由用户决定。系统可以默认高亮推荐项，但不能自动替换用户已选的方案。

```kotlin
data class HostComputeProfile(
    val os: String,
    val architecture: String,
    val cpuThreads: Int,
    val availableMemoryBytes: Long,
    val gpu: GpuProfile?,
)

data class EngineAvailability(
    val engineId: String,
    val state: AvailabilityState,
    val reasons: List<String>,
    val estimatedMemoryBytes: Long?,
    val estimatedSpeed: String?,
    val requiresDownload: Boolean,
)
```

`GpuProfile` 至少包含厂商、设备名、驱动版本、后端类型、显存总量、可用显存、支持的精度和检测时间。
检测不应仅凭 GPU 型号猜测，必须同时检查：

- 本机是否已安装对应运行时和驱动。
- 模型文件是否存在、版本和 hash 是否匹配。
- 模型启动的实测分配是否在显存和系统内存限额内。
- 当前平台、驱动和 sidecar 是否支持该后端。
- 是否允许下载模型、使用 GPU 或运行较慢的 CPU 回退。

`AvailabilityState` 建议包含 `AVAILABLE`、`AVAILABLE_WITH_WARNING`、`DOWNLOAD_REQUIRED`、
`UNSUPPORTED`、`INSUFFICIENT_MEMORY`和 `BROKEN_INSTALLATION`。“可运行”与“被系统推荐”分开存储：

- `selectedEngineId` 是用户持久选择，只在用户选的方案不再可用时提示修改。
- `recommendedEngineId` 是当前检测结果的建议，硬件或模型变化后可重新计算。
- 用户选择不可被后台识别任务自动改写；任务启动前必须使用当前选择快照。

高风险配置可以显示警告和“强制启动”操作，但不应将推荐项设为唯一可选项。若用户选择了较慢的 CPU 方案，应显示预估耗时后仍然允许运行。

## 6. 存储与缓存

### 6.1 持久数据

`.xuexue/pdf-outlines.json` 保存工作区内文档与 `PdfOutlineDocument` 的映射。它是需要备份的应用数据，
因为其中可能含有用户手动修正。写入使用 `WorkspaceService.atomicWrite`，并使用 mutex 串行化。

持久记录以工作区相对路径定位，同时存储文档指纹。指纹不一致时保留旧书签供用户比较，
但不再默认用于导出。

### 6.2 可重建缓存

OCR 与页面版面结果放在系统缓存目录：

```text
$XDG_CACHE_HOME/xuexue/document-analysis/<document-hash>/
  manifest.json
  pages/000000.json
  pages/000001.json
  ...
```

缓存身份至少包含：

- PDF SHA-256、文件大小和页数。
- 页序号、识别 DPI 和预处理选项。
- 引擎 ID、引擎版本和所有模型版本。
- 语言、竖排、纠偏、展平和阅读顺序配置。

更换识别方案时，旧结果在新任务成功前保持可用。只有受影响的页缓存和下游候选树被失效。

## 7. 识别与章节树构建

### 7.1 页面分类

文档可能同时包含原生文字页和扫描页，因此按页判定，不给整本 PDF 设置单一类型。
判定信号包括：

- 结构化文本的字符数、文字块数和页面覆盖率。
- 文字是否大量为乱码或不可见字符。
- 页面是否主要由一张大图像构成。
- 原生文字与可见图像文字是否明显不一致。

文字层可用时优先 `NativePdfLayoutEngine`；只有缺少可信文字层的页才进入 OCR。

### 7.2 PP-StructureV3 主路径

第一版采用受管理的本地 sidecar，由 Kotlin 通过 loopback 调用。运行时与模型放在用户缓存目录，
不写入工作区，不向外网上传文档。

页面处理流程：

1. MuPDF 以低优先级生成 OCR 用页面图像，初始 DPI 为 300。
2. 页面图像编码后立即释放 RGBA 缓冲，同时最多保留一个全页中间结果。
3. sidecar 执行方向检测、版面检测、OCR 和阅读顺序恢复。
4. adapter 将 `parsing_res_list` 映射为 `PdfPageLayout`，原始像素坐标立即归一化。
5. 每完成一页就写入缓存并发布进度，取消时不丢弃已完成页。

不把整个 PDF 作为 Base64 JSON 请求发送，避免长文档的内存峰值。开发阶段可先支持用户配置的
本地 endpoint；发布前必须完成 sidecar 版本锁定、校验和进程生命周期管理。

### 7.3 PaddleOCR-VL 低置信度回退

满足以下任一条件时才进入 VL 回退：

- PP-StructureV3 未识别到标题，但目录页显示应存在章节。
- 页面是古籍、竖排、严重变形或不规则图文混排。
- 标题文字置信度低于阈值，且其页码可从目录页得到强约束。
- 用户对当前页或文档显式选择“复杂版面”方案。

VL 返回的标题必须与已有 OCR 文字块做模糊对齐。无法对齐到页面坐标和原始文字的结果只能进入
待审核列表，不能自动生成导出书签。

### 7.4 标题候选特征

标题候选评分使用可解释特征，不把单一正则或模型 label 当作最终结果：

- 版面类别为文档标题或段落标题。
- 符合“第 X 章/节/编/部/卷”、`1.2.3`、`Chapter 2` 等可解析编号。
- 与全文正文块相比具有显著字高、行高、粗体或居中特征。
- 文本长度、标点和行数符合标题分布。
- 同一样式在跨页上反复出现，并与编号层级一致。
- 与目录页条目在文字和预期页码窗口内匹配。

惩罚信号包括页眉页脚的重复出现、位于页边缘、只包含页码、图表标题、低 OCR 置信度以及
与前后章节序列冲突。初始权重必须从固定评测集校准，不在 UI 代码中散落魔法数字。

### 7.5 目录页与印刷页码对齐

1. 组合 `TOC` label、“目录/Contents”关键词和连续条目形态识别目录页区间。
2. 解析每个条目的标题、层级线索、引导点和印刷页码。
3. 从正文页底部的页码块建立 `pdfPageIndex = printedPage + offset` 映射。
4. 将罗马数字前言和阿拉伯数字正文分开建模，允许文档中间的页码重置。
5. 使用标题归一化、模糊匹配和页码窗口验证目录条目与正文候选。

当目录页文字与正文标题冲突时，应保留两份证据供用户选择，不静默改写。

### 7.6 层级构建

层级推导的优先级为：

1. 可确定解析的章节编号。
2. 目录页缩进、字号和编号的组合。
3. 全文档标题样式聚类，不使用单页局部大小排名。
4. 用户审校结果。

树构建时禁止无证据的层级跳跃，例如一级标题后直接出现三级标题。无法稳定判定的层级使用
最浅合法层级并标记为待审核。

## 8. MuPDF Outline 读取与导出

### 8.1 读取原生 Outline

在 `MuPdfDocumentSession` 的 actor 上调用 `fz_load_outline`，将 `next/down` 树、标题、URI、页面和坐标序列化为
紧凑二进制负载，再由 Kotlin 解码为 `PdfOutlineDocument`。原生 Outline 默认可直接导航，
除非用户显式选择重新识别。

### 8.2 导出新 PDF

导出是独立于阅读 session 的短生命周操作：

1. 要求源 PDF 已完整落盘，在临时输出路径上重新打开。
2. 用 `pdf_specifics` 确认文档确实是可编辑 PDF。
3. Kotlin 将最终书签树编码为带版本、深度、UTF-8 标题、页序号和定位坐标的二进制负载。
4. C 桥接使用 `fz_new_outline_iterator`、`insert/down/up/next` 构建完整层级。
5. 节点 URI 使用 MuPDF 内部目标语义，第一版至少定位到页，后续增加标题纵坐标。
6. `pdf_can_be_saved_incrementally` 允许时可使用增量写入，否则使用完整保存。
7. 重新打开临时 PDF，校验页数、Outline 树和所有目标页。
8. 校验通过后原子移动到用户选择的新路径。

导出 JNI 应提供一个高层原子操作，不将原生 Outline iterator handle 长时间暴露给 Kotlin，
以避免异常路径上泄漏引用或留下半完成树。

### 8.3 原生 Outline 合并策略

在 Kotlin 层生成完整最终树，原生层只负责写入。导出界面提供：

- 保留原生书签，只追加经确认的新节点。
- 用审校后的树替换原生书签。
- 仅导出已确认节点，忽略待审核候选。

不允许静默覆盖原 PDF。加密、权限限制、数字签名或结构损坏的 PDF 要给出明确诊断；
签名文档导出后的签名状态必须在确认界面提示。

## 9. 后台任务与资源控制

- 每份文档同时只允许一个结构识别任务，新任务显式取消或排队。
- OCR 页面渲染使用 `PdfRasterPriority.BACKGROUND`，不占用可见瓦片优先级。
- CPU 默认保留一个核心给 UI 和 MuPDF 可见区渲染；GPU 并发由识别引擎 descriptor 给出。
- 识别任务启动前重新读取 `HostComputeProfile` 和 `EngineAvailability`，防止检测与实际启动之间发生配置漂移。
- 用户已选的方案只在 `UNSUPPORTED` 或严重内存不足时阻止；`AVAILABLE_WITH_WARNING` 只显示风险并允许继续。
- 同一识别引擎不在没有用户授权时自动切换后端；失败时先保留旧结果，再让用户选择重试、回退或换方案。
- `WorkbenchTaskController` 展示当前阶段、已完成页数、总页数和取消/重试操作。
- 取消必须停止新页调度、中断 sidecar 请求并释放临时图像。
- 应用关闭时先停止识别任务，再回收 sidecar，不遗留后台进程。
- 渐进下载 PDF 只能显示“等待下载完成”，不对不稳定临时文件启动全文档分析。

## 10. 用户界面

### 10.1 PDF 目录面板

在 `PdfSidePanel` 增加 `OUTLINE`，并使全局“目录”工具窗口对 PDF 复用同一个
`PdfOutlineTree` 组件。面板提供：

- 原生书签、自动候选和已确认书签的统一树视图。
- 点击节点后跳转到 PDF 物理页，有标题坐标时对齐到标题位置。
- 编辑标题、增加/删除、上移/下移、升级/降级和确认/驳回。
- 低置信度节点可打开对应页面缩略图、识别框和目录匹配证据。
- 重新识别、取消、选择识别方案和导出新 PDF。

工具栏使用 Material icon 和 tooltip，层级修改使用缩进/反缩进图标，不堆叠大量文字按钮。

### 10.2 审校交互

- 高置信度节点可批量确认，低置信度节点默认不进入导出。
- 手动修正后记录 `MANUAL`，重新识别时只提示可合并的新证据。
- 显示标题文本、PDF 页码和可选印刷页码，不用印刷页码替代实际跳转目标。
- 导出前显示节点数、待审核数、原生 Outline 处理策略和输出路径。

### 10.3 识别方案与硬件设置

在应用设置中增加“文档识别”分区，并提供明确的“重新检测本机配置”操作。该页面分为三层：

1. **当前配置**：CPU 核心、内存、GPU 后端、显存、sidecar 版本和模型安装状态。
2. **方案列表**：每个方案显示支持语言、版面能力、预估显存/内存、预估速度、隐私类型和下载量。
3. **选择与推荐**：推荐方案只做标记和排序，用户可以点选任意可用或带警告的方案。

用户选择应持久化：

- 全局默认方案：用于新文档。
- 按文档覆盖：优先于全局默认，适合古籍或特殊文档。
- 每次任务临时选择：只影响当前任务，不改变默认值。

需要避免两种误导：

- 检测不到 GPU 不等于识别不可用，应保留 CPU 方案。
- 推荐的方案不等于唯一合法方案，不能在用户选择后自动改回推荐项。

方案选择更改时，界面应告知下游影响：只重建 OCR 和版面缓存、只重建章节候选，还是会清除现有自动树。
手动修正和已确认节点必须保留。

## 11. 分阶段实施

### 阶段 0：固定评测基线

交付物：

- 建立扫描书籍评测清单，覆盖中文教材、小说、论文、无目录书籍、双栏、竖排、旋转/倾斜和混合文字层。
- 为每份样本建立预期章节树、页面目标和可允许的标题归一化差异。
- 建立可在 CI 中运行的小型原生/扫描 fixture，大文档评测集保持在仓库外。

验收：评测工具能计算标题精确率、召回率、层级边准确率和目标页准确率。

### 阶段 1：MuPDF Outline 最小闭环

交付物：

- JNI 读取原生 Outline 和 Kotlin 解码器。
- JNI 使用 Outline iterator 导出两级测试树到新 PDF。
- 导出后重新打开和树对比验证。
- 正常、已有 Outline、CropBox/旋转、加密、签名和损坏 PDF 的行为测试。

验收：源 PDF 字节不变，新 PDF 页数不变，导出 Outline 的标题、层级和页面全部匹配。

### 阶段 2：应用内书签模型与导航

交付物：

- `PdfOutlineDocument`、repository、schema 版本和指纹检查。
- `PdfSidePanel.OUTLINE` 和全局目录工具的 PDF 分支。
- 书签点击导航、编辑、层级调整、持久化和恢复。
- 用户选择的保留/追加/替换导出策略。

验收：不依赖 OCR 也能完成“读取原生 Outline -> 应用内编辑 -> 导出新 PDF”。

### 阶段 3：原生文字层章节识别

交付物：

- 从 `fz_stext_page` 导出块、行、字形尺寸、样式和坐标的 JNI 协议。
- `NativePdfLayoutEngine` 与混合文字/扫描页分类。
- 标题编号解析器、页眉页脚重复抑制和基础层级构建。

验收：对带文字层但缺少 Outline 的 fixture，无 OCR 即可生成可审核章节树。

### 阶段 4：PP-StructureV3 集成

交付物：

- `DocumentAnalysisEngine` 协议、引擎 descriptor 和配置持久化。
- `HostComputeProfile` 硬件探测、实测模型启动检查和 `EngineAvailability` 可用性矩阵。
- 受管理的 PP-StructureV3 sidecar、版本检查、健康检查和取消。
- 页级 OCR 渲染、请求、标准化、缓存和进度更新。
- 中文、英文、混合文本、页面旋转和取消集成测试。

验收：扫描 fixture 产生稳定的坐标、label、文本、阅读顺序和缓存命中结果；设置页能正确区分“可运行”和“推荐”，并允许用户选择非推荐方案。

### 阶段 5：全文档章节推断

交付物：

- 目录页检测与条目解析。
- 印刷页码与 PDF 页序号对齐。
- 标题候选评分、全文样式聚类、跨页一致性和层级构建。
- 证据、置信度和待审核原因的可序列化结果。

验收：在固定评测集上达到第 12 节的准确率目标，且每个自动节点都能展示证据来源。

### 阶段 6：审校与完整导出体验

交付物：

- 低置信度列表、页面证据预览和批量确认。
- 手动修正保护与重新识别差异合并。
- 导出确认、临时文件校验、原子移动和独立阅读器互操作测试。

验收：用户可在不编辑 JSON 的情况下完成识别、审核、导航和导出全流程。

### 阶段 7：PaddleOCR-VL 可选回退

交付物：

- `PaddleOcrVlEngine` 与按需模型下载。
- 低置信度路由、OCR 文字块回对和无法回对时的待审核降级。
- PP-StructureV3 单独运行与启用 VL 回退的 A/B 评测报告。

验收：仅在用户已启用“允许低置信度自动回退”时，才在固定评测集上明确改善结果的类型中自动启用 VL；否则保持手动选项。

### 阶段 8：性能、打包与恢复

交付物：

- Linux、Windows 和 macOS 的 sidecar 安装/升级/校验流程。
- CPU/GPU 配置检测、模型可运行性矩阵、用户覆盖推荐和模型下载恢复。
- 大文档长时任务、崩溃恢复、缓存升级和旧 schema 迁移。
- 性能指标、日志脱敏和诊断信息。

验收：识别任务不破坏 PDF 首屏、缩放和滚动体验，中断后能从已完成页继续；更换驱动、显存或模型后能重新检测，但不改写用户选择。

## 12. 验收指标与测试矩阵

### 12.1 识别质量

在固定评测集上，第一版目标为：

- 一级章节精确率不低于 95%，召回率不低于 90%。
- 已自动确认节点的 PDF 目标页准确率不低于 98%。
- 章节父子边准确率不低于 90%。
- 所有低于自动确认阈值的节点都必须显式标记，不得静默进入导出。

指标按文档类型分开报告，不用大量简单文档稀释古籍、竖排和复杂版面的失败。

### 12.2 功能测试

- 原生 Outline 的多层读取、URI 解析和导出 round trip。
- 原生文字页、扫描页和混合页的路由。
- 中文数字、阿拉伯数字、罗马数字和无编号标题。
- 目录缺失、目录页码错误、多套页码和文档中途重置页码。
- 页眉页脚、图表标题和书名的假阳性抑制。
- 手动修改后重新识别、文件内容改变和 schema 迁移。
- 取消、sidecar 崩溃、模型缺失、磁盘已满和缓存损坏。
- 无 GPU、GPU 显存不足、驱动不匹配、模型未下载、模型已损坏和 CPU 回退。
- 用户选择非推荐方案、设置重启后恢复、按文档覆盖全局默认和任务级临时选择。
- 硬件或模型变化后推荐项可更新，但已选方案不被静默替换。
- 增量保存不可用时的完整保存回退。

### 12.3 性能与稳定性

- OCR 后台任务运行时，PDF 首屏和可见瓦片 P95 延迟不得回归超过 10%。
- 取消后 2 秒内不再调度新页，临时页面图像和进程资源被回收。
- 单页 OCR 内存峰值需要在 RGBA、编码图像和引擎输入之间做性能基线，不允许多页全尺寸图像同时常驻。
- 1,000 页以上文档使用页级缓存、流式落盘和可恢复任务，不在内存中保留全部 OCR 原始响应。

### 12.4 质量门禁

每个实施阶段交付前运行：

```bash
./gradlew ktlintFormat
./gradlew ktlintCheck detektMainDesktop detektTestDesktop desktopTest koverXmlReport
```

JNI 变更额外运行 MuPDF 原生桥接编译、Outline 导出 round trip 和地址/未定义行为检查；
不通过扩展静态分析 baseline 隐藏新问题。

## 13. 预期文件变更

| 位置 | 预期变更 |
| --- | --- |
| `tools/native/mupdf_bridge.c` | 结构化文本块、Outline 读取、Outline 导出和 PDF 保存 |
| `src/desktopMain/.../platform/MuPdfNative.kt` | 对应 JNI 声明、安全 handle 与负载解码 |
| `src/desktopMain/.../platform/MuPdfDocumentActor.kt` | 在 document actor 上读取原生 Outline |
| `src/desktopMain/.../platform/PdfReaderBackend.kt` | 增加只读 Outline 协议，不把导出写入塞入渲染缓存层 |
| `src/desktopMain/.../platform/PdfOutlineRepository.kt` | 持久化、schema 迁移、指纹和原子写入 |
| `src/desktopMain/.../platform/DocumentAnalysisEngine.kt` | 识别引擎协议与标准模型 |
| `src/desktopMain/.../platform/HostComputeDetector.kt` | CPU、内存、GPU 后端和显存探测，实测启动检查与可用性矩阵 |
| `src/desktopMain/.../platform/DocumentAnalysisSettings.kt` | 全局方案、按文档覆盖、任务级选择和用户回退开关 |
| `src/desktopMain/.../platform/PpStructureV3Engine.kt` | PP-StructureV3 请求、响应映射和错误诊断 |
| `src/desktopMain/.../platform/PaddleOcrServiceManager.kt` | sidecar 下载、校验、启动、健康检查和关闭 |
| `tools/paddleocr-sidecar/package.py` | 跨平台依赖安装、ZIP 构建、版本记录和 SHA-256 输出 |
| `src/desktopMain/.../platform/ChapterStructureDetector.kt` | 候选、目录对齐、层级和置信度 |
| `src/desktopMain/.../WorkbenchPdfOutlineController.kt` | 任务编排、缓存、审校、导航和导出 |
| `src/desktopMain/.../ui/PdfOutlinePanel.kt` | PDF 目录树、审校和导出界面 |
| `src/desktopMain/.../ui/DocumentSettings.kt` | 硬件检测状态、可用方案、推荐标记和用户选择 |
| `src/desktopMain/.../ui/PdfDocumentView.kt` | `OUTLINE` 侧栏入口与导航接线 |
| `src/desktopMain/.../ui/ToolWindows.kt` | 全局目录工具对 PDF 的复用 |
| `src/desktopTest/...` | 算法、持久化、sidecar、JNI round trip、导航和 UI 测试 |

新控制器与新 UI 文件用于避免继续扩大 `WorkbenchPdfController` 和 `PdfDocumentView`。
只在职责明确且已被多个调用方使用时才抽取公共层。

## 14. 风险与降级

| 风险 | 控制措施 |
| --- | --- |
| Paddle 运行时体积大、平台差异多 | 识别引擎独立，先支持配置 endpoint，再完成受管理 sidecar |
| OCR 抢占阅读器资源 | 页级低优先级调度、单页内存上限、保留 UI 核心和可取消任务 |
| VLM 生成不在页面上的标题 | 必须回对 OCR 文字与坐标，无证据时仅待审核 |
| 目录页页码与 PDF 页序号不同 | 独立建模印刷页码，最终导航始终使用物理页 |
| 重新识别破坏手动修正 | 保留 `MANUAL/CONFIRMED`，新结果通过差异显式合并 |
| MuPDF 导出特殊 PDF 失败 | 临时文件、可增量检查、完整保存回退和导出后重新校验 |
| 识别精度指标被简单样本稀释 | 按文档类型报告指标，保留困难文档独立门禁 |

## 15. 第一个可交付版本的完成定义

以下条件全部满足后，扫描件书签功能才可标记为完成：

- 原生 Outline 可读取、导航、编辑并用 MuPDF 导出到新 PDF。
- 原生文字 PDF 不经 OCR 即可生成章节候选。
- 扫描 PDF 可通过 PP-StructureV3 生成带坐标、证据和置信度的章节树。
- 目录页与正文页能完成印刷页码对齐，页码不确定时不自动导出。
- 用户可修正标题、页面和层级，重新识别不覆盖手动修正。
- 长任务可显示进度、取消、重试并从页级缓存恢复。
- 输出 PDF 经 MuPDF 重新读取验证，并至少在一个独立 PDF 阅读器中验证 Outline 互操作性。
- 识别质量、性能和仓库质量门禁全部通过。

## 16. 参考资料

- [PP-StructureV3 使用文档](https://www.paddleocr.ai/latest/en/version3.x/pipeline_usage/PP-StructureV3.html)
- [PaddleOCR 项目与 PaddleOCR-VL 说明](https://github.com/PaddlePaddle/PaddleOCR)
- [MuPDF 1.27.2 Outline iterator](https://github.com/ArtifexSoftware/mupdf/blob/1.27.2/include/mupdf/fitz/outline.h)
- [MuPDF 1.27.2 PDF Outline 实现](https://github.com/ArtifexSoftware/mupdf/blob/1.27.2/source/pdf/pdf-outline.c)
- [MuPDF 官方 Outline 复制与保存示例](https://github.com/ArtifexSoftware/mupdf/blob/1.27.2/docs/examples/copy-outline.js)
