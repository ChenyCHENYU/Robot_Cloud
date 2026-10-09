# Robot Cloud 品牌资产

Robot Cloud 的独立标识由「云冠机器人」和三个连接节点组成：机器人延续 Robot Admin 的产品家族，云形轮廓表达后端基础设施，节点表达服务协作。三节点是图形语言，可随架构演进持续使用。

深蓝提供稳定的视觉基底，冰蓝与青绿沿轮廓形成克制的渐变。画布透明，字标和英文副标全部是矢量路径，无需网络、字体文件或脚本。

| 文件 | 画布 | 用途 |
| --- | --- | --- |
| [logo.svg](logo.svg) | 256 × 256 | 独立彩色图标、文档与产品展示 |
| [logo-mono.svg](logo-mono.svg) | 256 × 256 | 单色印刷、图标和受限色彩场景 |
| [lockup-light.svg](lockup-light.svg) | 760 × 248 | README 及浅色背景品牌头图 |
| [lockup-dark.svg](lockup-dark.svg) | 760 × 248 | README 及深色背景品牌头图 |

## 使用约定

- 保持长宽比，不拉伸、旋转或额外添加阴影。
- 图标建议显示为 32 px 或更大；24 px 场景优先使用单色版。字标组合建议至少 360 px 宽。
- 图形四周保留至少图标画布宽度的 1/8；组合图已有预留留白。
- 深色字标使用浅色背景，浅色字标使用深色背景；独立彩色图标可用于两种背景。
- 单色版默认 `#16324F`，内联 SVG 可通过 `color` 设置颜色；以 `<img>` 引用时，宿主 CSS 不会改变文件中的颜色。
- 标识用于产品识别；服务数量、验证结果和部署状态以项目文档为准。

| 色彩 | 值 | 作用 |
| --- | --- | --- |
| Midnight | `#102439` | 主体与深色基底 |
| Navy | `#122E46` | 浅色背景字标 |
| Ice | `#B5E8F8` | 轮廓高光 |
| Teal | `#43D4B6` | 连接与强调 |
| Mist | `#EDF6FD` | 深色背景字标 |

字标由本机 Avenir Next 字形生成并转为路径，未随仓库分发字体文件。SVG 保留 `<title>`、`<desc>` 和可访问名称；用于 README 的 `<img>` 仍应提供 `alt`。

## README 引用

```html
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/lockup-dark.svg">
  <img src="docs/assets/brand/lockup-light.svg" width="760" alt="Robot Cloud — Microservice Foundation">
</picture>
```

所有资产均已通过 XML、`viewBox`、本地引用和外部资源检查，并使用 macOS Quick Look 实际渲染。浅色、GitHub 深色背景及 24 / 32 / 48 / 64 / 96 px 图标已做目视检查；最终在具体平台的 README 展示应继续以实际渲染为准。
