# ComicReader

ComicReader 是一个基于 Kotlin 和 Jetpack Compose 开发的 Android 漫画阅读器。项目支持从本地目录扫描漫画资源，并提供书架、漫画详情、章节阅读、阅读进度保存、双击缩放、双指缩放、横向/竖向阅读等基础漫画阅读功能。

## 下载

最新 APK 可在 [GitHub Releases](https://github.com/on11e/ComicReader/releases) 页面下载。创建新的 GitHub Release 后，工作流会自动构建 APK 并上传到对应 Release 附件。

## 最近更新

- 新增标签选择功能，编辑漫画资料或外部漫画入口时可以从已有标签中快速选择。
- 优化漫画详情页与章节列表 UI，章节区域更紧凑，并支持快速滑动浏览。
- 支持章节重命名，长按本地章节即可修改章节名称。
- 修复自动跳转下一章节相关问题，阅读进度在跨章节阅读时保存更准确。
- 优化文件排序，章节文件夹和漫画图片会按文件名自然排序。
- 优化编辑资料界面，支持自定义名称、简介、标签、封面和外部阅读地址。

## 功能特性

- 本地漫画书架
  - 选择一个漫画根目录后自动扫描漫画。
  - 支持文件夹漫画和压缩包漫画。
  - 支持按漫画名称排序展示。

- 漫画格式支持
  - 图片格式：`jpg`、`jpeg`、`png`、`webp`
  - 压缩包格式：`zip`、`cbz`

- 阅读体验
  - 支持竖向连续阅读。
  - 支持横向分页阅读。
  - 支持双击放大 / 缩小。
  - 支持双指缩放和拖动查看。
  - 双击缩放带平滑过渡动画。
  - 支持阅读进度保存。
  - 支持章节切换、上一章 / 下一章快捷跳转和自动进入下一章。
  - 支持跨章节连续阅读时按章节显示页码和保存未读完位置。

- 书籍管理
  - 支持自定义漫画名称、简介、标签和封面。
  - 支持按标签筛选，并可在编辑时从已有标签中选择。
  - 支持本地章节重命名。
  - 支持删除漫画。
  - 支持添加外部漫画入口和常用漫画网站入口。

- 构建发布
  - 支持通过 GitHub Actions 自动构建 debug APK。
  - 创建 GitHub Release 时可自动上传 APK 到 Release 附件。

## 项目结构

```text
ComicReader/
├── app/
│   ├── src/main/java/com/example/comicreader/
│   │   ├── MainActivity.kt        # 应用入口
│   │   ├── MainAppScreen.kt       # 主界面状态与页面切换
│   │   ├── BookshelfScreens.kt    # 书架和详情相关界面
│   │   ├── ReaderScreen.kt        # 漫画阅读界面
│   │   └── ComicParser.kt         # 漫画目录、章节、图片解析
│   └── build.gradle.kts           # App 模块构建配置
├── .github/workflows/
│   └── android-apk.yml            # GitHub Actions APK 构建流程
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE
└── README.md
```

## 本地运行

### 环境要求

- Android Studio
- JDK 17
- Android Gradle Plugin 对应的 Gradle 环境
- Android 7.0 及以上设备或模拟器，项目 `minSdk` 为 24

### 使用 Android Studio 运行

1. 克隆仓库：

```bash
git clone https://github.com/on11e/ComicReader.git
cd ComicReader
```

2. 用 Android Studio 打开项目。
3. 等待 Gradle Sync 完成。
4. 连接 Android 设备或启动模拟器。
5. 点击 Run 运行 App。

### 使用命令行构建 APK

Windows PowerShell：

```powershell
.\gradlew assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

构建完成后，debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以直接前往 [GitHub Releases](https://github.com/on11e/ComicReader/releases) 下载已经发布的 APK。

## 使用说明

1. 首次进入 App 后，选择一个漫画根目录。
2. App 会扫描该目录下的漫画文件夹或压缩包。
3. 点击漫画进入详情页。
4. 选择章节开始阅读。
5. 阅读界面中：
   - 单击：显示或隐藏控制栏。
   - 双击：放大或恢复原始大小。
   - 双指捏合：自由缩放。
   - 放大后单指拖动：移动查看画面。
   - 边缘横滑：返回。

## 推荐漫画目录格式

### 单本漫画文件夹

```text
漫画根目录/
└── 漫画A/
    ├── 001.jpg
    ├── 002.jpg
    └── 003.jpg
```

### 多章节漫画文件夹

```text
漫画根目录/
└── 漫画B/
    ├── 第01话/
    │   ├── 001.jpg
    │   └── 002.jpg
    └── 第02话/
        ├── 001.jpg
        └── 002.jpg
```

### 压缩包漫画

```text
漫画根目录/
├── 漫画C.cbz
└── 漫画D.zip
```

## GitHub Actions 自动打包

仓库包含 `.github/workflows/android-apk.yml` 工作流。它会在以下情况自动构建 APK：

- push 到 `master` 分支
- 手动触发 workflow
- 创建 GitHub Release

构建产物名称为：

```text
ComicReader-debug-apk
```

如果是通过 GitHub Release 触发，APK 会被上传到对应 Release 的附件中。

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Coil
- Android DocumentFile
- Gradle Kotlin DSL
- GitHub Actions

## 后续可优化方向

- 支持更多压缩包格式，例如 `rar`、`7z`
- 增加夜间阅读参数和亮度控制
- 增加阅读方向设置，例如从右到左翻页
- 增加漫画搜索和排序选项
- 增加正式 release 包签名配置
- 优化大图加载和内存占用

## License

本项目基于 MIT License 开源，详情请查看 [LICENSE](LICENSE) 文件。
