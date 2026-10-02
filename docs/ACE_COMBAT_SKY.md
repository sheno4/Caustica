# ACE COMBAT 8 天空适配

这是 `rewrite` 分支上的实验适配版本 `0.1.0-ac8-sky.1`。它导入原游戏的体积云纹理、云体位置和预设参数，并使用 Caustica 的渲染流程绘制云层。Cloudly 原始渲染器源码没有随游戏资源提供；适配中的解释和近似保存在独立的 `rendererAdapter` 中。

## 使用

使用 Minecraft 26.2 的 Vulkan 后端，安装本分支构建的 Fabric 或 NeoForge 包及该加载器所需的依赖。原模组的硬件要求仍适用。

将私有资源包保存在 Git 仓库外。在 Minecraft 的 `config/caustica.toml` 中加入：

```toml
["caustica:minecraft".sky.cloudly]
enabled = true
source-pack = 'D:/DESK/X/caustica/analysis-game/source-pack-hangar001/manifest.json'
samples = 64.0
max-distance-km = 200.0
```

路径应指向实际导入包的 `manifest.json`。在设置中的 **ACE COMBAT Clouds (Experimental)** 可以关闭效果、调整采样数和距离。初次启用或改变资源包后，需要重新进入世界。原始资源没有包含在公开构建包内。

资源在后台解码、上传，完成后才参与绘制。未配置资源包时采用普通 Minecraft 天空。导入包仅用于主世界。

## 已导入的资源

本地资源来自 Steam app `2288340`、build `25201480` 的 **ACE COMBAT 8: WINGS OF THEVE**，检查日期 2026-10-02。

| 内容 | 已恢复的数据 |
| --- | --- |
| 天空预设 | `CSP_Hangar001_Daytime01` |
| 云体预设 | `CVC_Hangar001_Daytime01`，4 个分组及 31 个云体 |
| 原始纹理 | 9 个实际关联的 `VT_` 体积纹理 |
| 纹理格式 | 每个 512 × 512 × 64、PF_DXT1 / BC1，共 90 层 mip |
| 原始 mip 总量 | 86,283,000 字节，每份都有尺寸、长度和 SHA-256 记录 |
| 原始太阳位置 | 仰角约 25°，方位角约 -72° |

纹理来自卷云、卷层云及积云资源；没有用程序噪声替换这些形状。导入包保留已序列化参数的原始名称和值、纹理编号、分组、位置、尺寸、旋转及 UV 信息。未保存的原生默认值保持缺失。原 JSON 中重复出现的静态数组字段以有序数组保存，避免丢失前面的元素。

## 渲染行为及边界

- 全部 BC1 通道和 mip 解码为数字 RGBA8，上传为不可变的 3D 纹理；文件路径、尺寸和哈希在加载时校验。
- 主视角沿实际云体包围盒求交、合并相交区间，再积分透射和单次散射。实体的物理深度限制积分终点，透过云可以看到地形。
- 云层在雾之前合成，使用当前帧的相机、采样偏移和曝光。离开世界时取消上传并等候后台准备退出，已提交的资源由帧引用保留到完成。
- 天空 LUT、保留的太阳光和云层使用同一预设太阳方向；亮度及太阳/月亮大小使用 Minecraft 的光度校准。
- 云体依据原始纹理编号和已保存的层密度放置。当前适配声明源坐标为 Z 向上，以 `(X,Z,-Y)` 对应 Minecraft，并将源零高度对齐海平面。

当前适配明确采用红通道密度、完整尺寸、绝对位置、Z 轴欧拉旋转、恒等密度映射和最大值合并。这些是兼容解释，原 Cloudly 通道公式尚未验证。只接受本预设所需的受支持字段；不支持的操作、旋转、剪切和拉伸会拒绝导入。

**尚未移植的效果：** 原 Cloudly 的噪声侵蚀、动画、降雨和吸收掩模、特殊相函数与多次散射、Lumen 光照，以及全部天空预设的切换。当前云层是主视角效果，六点自遮挡仅覆盖有限距离；它不进入次级反射光线，也不向地面投下云影。大气模型仍使用 Caustica 的 Hillaire 实现。它不是原 Cloudly 渲染器的等价实现。

没有执行图像识别测试，也没有确认实际画面、帧率或与原游戏的视觉一致性。

## 导出与复现

可复用的离线工具位于 [`tools/ace_combat_sky/exporter`](../tools/ace_combat_sky/exporter/README.md)。它使用固定版本的 CUE4Parse、托管解压器和本地属性映射读取资源，不运行原游戏或游戏 DLL。

导出步骤：读取本地安装信息；验证本地资源访问；按原生属性映射导出预设及其真实关联纹理；校验 native mip；生成私有资源包。将 [`caustica-adapter.example.json`](../tools/ace_combat_sky/exporter/caustica-adapter.example.json) 通过 `--renderer-adapter` 传给转换工具，显式选择当前兼容解释。

资源访问密钥、原始资源、原生映射及导出文件保存在仓库外。工具拒绝向 Git 仓库写入私有导出。公开仓库保留程序、说明和不含密钥的来源记录，见 [`ACE_COMBAT_SKY_PREFLIGHT.json`](ACE_COMBAT_SKY_PREFLIGHT.json)。

## 版本与验证

基线为 `ComfyFluffy/Caustica` 的 `rewrite`，提交 `0cc9d0af4f4118cd26b830084a14f2bafc0d904d`。工作位于用户 fork 的独立 `ace-combat-sky` 分支，基线标签为 `ace-combat-sky-base-2026-10-02`。既有 `main`、`rewrite` 及旧云层分支保留。

已完成：四个代表资源的原生属性解析、九个真实关联纹理及全部 mip 的校验，以及真实私有资源包的渲染模型检查。34 项相关数值检查通过，包括 5 项 BC1/完整性/所有权、6 项源云体适配、4 项太阳预设，以及 19 项既有天空/光照检查。Java、反射生成的 ABI、Slang、SPIR-V 和描述符布局验证通过。

[Windows 构建 37021534425](https://github.com/sheno4/Caustica/actions/runs/37021534425) 已成功生成 Fabric 和 NeoForge 包，对应提交 `e1234d0a9ec5b703124714ccd0fc7cd88417ea6f`，版本标签为 `v0.1.0-ac8-sky.1`。下载后的两个包均已确认：包含云层类、编译后的着色器、八份必要的 Windows 运行库及许可证，没有重复条目，也没有原始游戏资源。

| 安装包 | SHA-256 |
| --- | --- |
| `caustica-0.1.0-ac8-sky.1-fabric.jar` | `b20a8a486577d2d744d1858de8738226fc110ee71ef9e299056ce729d23d2cfc` |
| `caustica-0.1.0-ac8-sky.1-neoforge.jar` | `1fca2c1cacaeb07df8ac3ea218fc0c906282dc0a2fba56cc3bc50f4472acc3bb` |

Windows 构建工作流分别生成 Fabric 和 NeoForge 包，固定 Slang 2026.14.1、Java 25 及原项目的 SDK 版本。工作流只编译和打包；不运行游戏，不进行图像测试。

[官方开发者介绍](https://en.bandainamcoent.eu/ace-combat/news/ace-combat-8-wings-of-theve-developer-diary) 将 Cloudly 描述为内部技术，并介绍了其与 Lumen 的结合。这些原渲染器功能与本适配的实现边界需分别看待。
