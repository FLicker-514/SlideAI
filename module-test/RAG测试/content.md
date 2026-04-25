# SITP-墨色技术报告

# 一. 项目概述

中国书法具有独特的艺术价值与文化内涵，其风格不仅体现在字形结构上，更体现在墨色的干湿浓淡与笔触的轻重缓急。利用深度学习进行书法生成时，若仅依赖文本或单一图像条件，往往难以同时控制“写什么字”与“用什么风格写”，导致字形失真或风格不统一。因此，需要一种能解耦字形与风格的生成框架：字形由结构化条件（如骨架图）约束，风格由少量高质量样本通过轻量微调学习。

本工作的主要目标为：

（1）形成高质量的多墨色风格数据集  
（2）字形可控：通过骨架图（可视为笔画中心线）作为 ControlNet 条件，保证生成结果字形的准确性。  
（3）风格可训：针对干、湿、浓、淡等不同墨色风格，分别训练轻量级 LoRA 适配器，在保持基座模型通用性的前提下实现风格化生成。  
（4）数据高效：在字形条件强约束下，探索最少所需训练样本与训练步数，以降低数据制作与算力成本。

# 二. 总体设计框架

本项目旨在解决传统图像生成模型在书法合成中面临的“形神难以兼备”问题，即如何独立控制书法的“字形结构”与“笔触墨色”。为此，我们提出了一种基于特征解耦的生成方法：

（1）数据准备与预处理：对原始书法图像进行阈值分割以剥离背景噪声，提取纯粹的墨迹分布；同时构建 “骨架图-风格文本-目标图像” 的三元组数据集，为多模态对齐提供高质量的数据支撑。  
（2）多条件微调训练阶段：以 Stable Diffusion (SD) v1.5 为基础概率模型。在训练中，利用 ControlNet 引入空间结构的约束，同时通过在 U-Net 的注意力交叉层注入低秩适应矩阵（LoRA），实现对特定书法风格（如枯笔、飞白）的参数高效学习（Parameter-Efficient Fine-Tuning, PEFT）。  
（3）推理生成阶段：在测试环节，采用图生图（Img2Img）的逆向扩散采样策略。在给定初始随机噪声或底图的基础上，结合微调后的 LoRA 与 ControlNet，通过无分类器引导（Classifier-Free Guidance, CFG）技术，在字形骨架的约束下生成具备目标风格的书法图像。

# 2.1 数据预处理

真实的拓片或书法扫描件常包含复杂的背景流形（如纸张纹理、折痕、光照不均等）。若直接在像素空间或潜在空间进行拟合，扩散模型容易将高频的背景噪声与低频的书法笔触相混淆，导致生成的墨迹边缘不纯粹。

为此，我们通过自适应或设置固定阈值，实现墨迹和背景的剥离。将输入图像转

化为灰度场后，通过阶跃函数进行离散化映射：

$$
I _ {b i n} (x, y) = \left\{ \begin{array}{l l} 2 5 5, & \text {i f} I _ {g r a y} (x, y) > T \\ 0, & \text {o t h e r w i s e} \end{array} \right.
$$

其中， � 为区分前景墨迹与背景的阈值。这一操作在数学上等价于将高维的复杂图像分布投影到一个更低维、更纯粹的二值化子空间中。二值化后的图像 $I _ { b i n }$ 作为训练的目标图像，使模型在反向传播时将优化注意力完全集中在笔锋的走向、墨色的浓淡以及飞白的细节纹理上。

# 2.2 训练数据格式与多模态对齐

构建高质量的训练集是模型理解抽象书法概念的前提。我们将训练集定义为多模态三元组 � ={(� $\mathcal D = _ { i ( \boldsymbol X ^ { ( i ) } , \boldsymbol C _ { s k e l ^ { \prime } } ^ { ( i ) } } c _ { t e x t } ^ { ( i ) } ) \boldsymbol j _ { i = 1 } ^ { N } ,$ � ，包含：

（1）目标图像 $( x )$ ：预处理后的书法图像，被裁剪并缩放至 $5 7 2 \times 5 7 2$ 的标准分辨率，像素值归一化至 [ − 1,1]，以适配 VAE 的输入分布。  
（2）结构控制图 $( c _ { s k e l } )$ ：与 $x$ 具备严格像素级对齐的单通道骨架图。它剥离了笔触的粗细信息，仅保留汉字的拓扑连通性，作为 ControlNet 的空间约束条件。  
（3）文本描述 $( c _ { t e x t } )$ ：表征图像风格的离散文本标签（如 "dry brush calligraphy","wet brush calligraphy"）。在训练时，这些标签通过预训练的 CLIP 文本编码器投影到高维连续的语义潜空间中，以指导跨模态注意力（Cross-Attention）的计算。

# 2.3 模型架构

本系统基于潜在扩散模型（Latent Diffusion Model, LDM），并在其基础上引入了空间与语义的双重视角控制。

![该图展示了一个用于生成风格化书法的深度学习模型架构，包含编码器、U-Net和ControlNet等组件。](images/d01d7f39d1b18e934ec784807b7db6fd13663eb6bf57276b42f9d56ece30b46e.jpg)

# （1）VAE 降维与前向加噪（Forward Process）

为了降低高分辨率图像生成的计算复杂度，目标图像 $\boldsymbol { x } \in \mathbb { R } ^ { H \times W \times 3 }$ 首先被冻结的VAE 编码器 ℰ 映射到低维潜空间 $z _ { \theta } = \mathcal { E } \left( x \right) \in \mathbb { R } ^ { h \times w \times c }$ （通常空间维度压缩 8 倍，即

$$
6 4 \times 6 4 \times 4) 。
$$

前向过程定义为一个马尔可夫链，向 $z _ { o }$ 中逐步注入方差递增的高斯噪声：

$$
q \left(z _ {t} / z _ {o}\right) = \mathcal {N} \left(z _ {t}; \sqrt {\overline {{\alpha}} _ {t}} z _ {o}, (1 - \overline {{\alpha}} _ {t}) I\right)
$$

根据重参数化技巧，任意时间步 $t \in { } / { } l , T { } J$ 的潜变量可直接表示为： $z _ { t } = \sqrt { \overline { { \alpha } } _ { t } } z _ { \theta } +$ $\sqrt { \ I - \overline { { \alpha } } _ { t } } \epsilon$ ，其中 $\epsilon \sim \mathcal { N } ( \theta , I )$

# （2）ControlNet 与零卷积机制（Zero-Convolution）

为了在不破坏预训练 SD 模型强大先验知识的前提下注入骨架约束，我们采用ControlNet $\mathcal { C } _ { \phi }$ 提取结构特征。

ControlNet 复制了 U-Net 的编码器部分。为了确保训练初期的稳定性，其输出层采用了零卷积（权重和偏置初始化为零的 $7 \times ~ 7$ 卷积）。它接收加噪潜变量 $z _ { t }$ 、时间步 $t$ 、文本特征 $\tau _ { \theta } ( c _ { t e x t } )$ 以及骨架图 $c _ { s k e l }$ ，输出多尺度残差特征 $\boldsymbol { F } _ { c }$

$$
F _ {c} = \mathcal {Z} (\mathcal {C} _ {\phi} (z _ {t}, t, \tau_ {\theta} (c _ {t e x t}), c _ {s k e l}))
$$

其中 �( ⋅ ) 表示零卷积操作。这些残差特征被逐像素地加和到主 U-Net 的解码器对应层中。

# （3）LoRA 低秩微调

为了在极小的数据集上捕捉书法特有的笔触动态，同时避免灾难性遗忘，我们冻结了 U-Net 的主干参数 $W _ { \theta }$ ，仅在其 Transformer 模块的交叉注意力层（to_k, to_q,to_v, to_out.0）旁路注入 LoRA 矩阵。

假设原始权重矩阵为 $W _ { o } \in \mathbb { R } ^ { d \times k }$ ，更新后的权重表示为：

$$
W = W _ {0} + \Delta W = W _ {0} + \frac {\alpha}{r} B A
$$

其中，降维矩阵 $B \in \mathbb { R } ^ { d \times r }$ 初始化为零，升维矩阵 $A \in \mathbb { R } ^ { r \times k }$ 采用高斯初始化。实验中设定秩 $r = 8$ ，缩放系数 $\alpha = 8 ,$ 。这种低秩约束（ $\left. r \ll m i n ( d , k ) \right.$ ）隐式地充当了正则化项，迫使模型提取书法风格中最核心的主成分，有效防止了过拟合。

# （4）联合优化目标

在训练过程中，只有 LoRA 矩阵的参数 $A , B$ 被更新。模型预测注入的噪声，整体优化的 MSE 损失函数为：

$$
\mathcal {L} _ {L D M} = \mathbb {E} _ {z _ {\theta}, \epsilon \sim \mathcal {N} (\theta , l), t, c _ {t e x t}, c _ {s k e l}} [ \| \epsilon - \epsilon_ {\theta , \Delta \theta} (z _ {t}, t, \tau_ {\theta} (c _ {t e x t}), F _ {c}) \| _ {2} ^ {2} ]
$$

# 2.4 推理阶段：条件约束与无分类器引导

在推理生成（Inference）阶段，系统基于

StableDiffusionControlNetImg2ImgPipeline 运行。给定一张初始底图，根据去噪强度（Strength, 记为 $s \in ( 0 , 7 ] $ ），模型首先向底图添加对应时间步 $\textbf { \textit { T } } ^ { ' } = \lfloor s \times T \rfloor$ 的噪声，作为采样的起点 � ′ 。 $z _ { \scriptscriptstyle T ^ { \prime } }$

随后，模型在去噪过程中利用无分类器引导（CFG）技术来增强文本与风格的控

制力。在每个时间步 $t$ ，模型同时计算有条件预测 $\epsilon _ { c o n d }$ 和无条件预测 $\epsilon _ { u n c o n d }$ 。最终的预测噪声由 guidance_scale（记为 $\omega$ ）进行外推：

$$
\epsilon_ {t} = \epsilon_ {u n c o n d} + \boldsymbol {\omega} \cdot (\epsilon_ {c o n d} - \epsilon_ {u n c o n d})
$$

结合 ControlNet 提供的骨架特征 $\boldsymbol { F } _ { c }$ ，去噪方程更新为：

$$
z _ {t - \tau} = \frac {\mathcal {I}}{\sqrt {\alpha_ {t}}} (z _ {t} - \frac {\mathcal {I} - \alpha_ {t}}{\sqrt {\mathcal {I} - \overline {{\alpha}} _ {t}}} \tilde {\epsilon} _ {t} (z _ {t}, t, F _ {c})) + \sigma_ {t} z
$$

通过迭代上述过程，潜变量最终收敛为 $z _ { o }$ ，并由VAE 解码为像素级书法图像4$\begin{array} { r l } { x ^ { ' } } & { { } = \mathcal { D } ( z _ { o } ) } \end{array}$ 。通过调节CFG 的权重 $\omega$ 与 ControINet的控制强度，用户可实现对生成结果在字形骨架与墨色风格特征之间的连续插值与精细控制。

# 三. 实验设置

# 3.1 实验设置

（1）基座模型：Stable Diffusion v1.5（runwayml/stable-diffusion-v1-5），ControlNet 为 Canny（lllyasviel/sd-controlnet-canny）。  
（2）训练参数：分辨率 $5 1 2 \times 5 1 2$ ，batch size 4，学习率 1e-4，优化器 AdamW，LoRA rank 8，混合精度 fp16。  
（3）风格：针对干（dry）、湿（wet）、浓（strong）、淡（light）四种墨色风格分别构建小规模数据集并独立训练 LoRA。

# 3.2 数据规模与训练步数

（1）主要发现：在引入骨架图作为字形控制后，约 10～20 张高质量、风格一致的书法图像即可支撑一次风格 LoRA 的训练；约 1500 步（随 batch size 与数据集大小不同）即可达到较好的生成效果。  
（2）原因分析：ControlNet 已承担字形与结构的约束，LoRA 只需学习“在该结构上施加何种墨色与笔触风格”，任务难度降低，故小样本与较少步数即可收敛。

# 3.3 预处理与数据质量

（1）二值化能有效抑制背景与噪声，使模型更关注笔触与墨色；阈值需根据具体数据（纸张颜色、扫描质量）调节。  
（2）骨架图与文本需与图像严格对应，骨架质量（笔画连贯性、无多余干扰图形）对生成字形影响较大。

# 3.4 推理参数影响

（1）strength 较大时风格迁移更明显，但过大可能引入不稳定纹理。  
（2）controlnet_conditoning_scale 可保证字形紧跟骨架；若希望略放松字形、更偏风格，可适当调低。  
（3）不同风格使用对应 prompt（如 "dry brush calligraphy"）与对应 LoRA 权重，可

得到一致的风格输出。

# 四. 结论

本文基于 Stable Diffusion、LoRA 与 ControlNet，实现了以骨架图为字形引导、以 LoRA 为风格载体的中国书法风格化生成流程。通过二值化预处理、骨架图与文本的人工标注、以及 LoRA+ControlNet 的联合训练与图生图推理，能够在约 10～20 张高质量样本、约 1500 步训练下，得到干、湿、浓、淡等不同墨色风格的书法生成效果，且字形由骨架图稳定控制。

本项目将风格 LoRA 与结构 ControlNet 结合，在书法这一垂直领域验证了字形与风格解耦 $^ +$ 小样本风格微调的可行性。