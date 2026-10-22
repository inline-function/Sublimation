# Sublimation for Android

Sublimation 语言（声明式、受 Rust/Kotlin 启发的设计验证语言）的安卓目标工程。
本仓库把安卓相关的注解处理器、安卓标准库（`stdlib/android`）与安卓壳工程（`android-app`）集中在一处：
用 Sublimation 写应用 → 编译器经 WebView 桥把 JS 产物跑在安卓壳里，KT 端用**普通 MD 视图**
（MaterialButton/TextView/LinearLayout，不使用 Compose）动态构建界面。

## 目录结构

| 目录 | 内容 |
| --- | --- |
| `android doc/` | 安卓目标设计文档（《安卓标准库》《安卓注解处理器》等） |
| `annotation processer/` | 安卓注解处理器（`@android` 内建校验、`@view` 视图变换、`@postcompile` APK 构建钩子） |
| `stdlib/android/` | 安卓标准库：`activity`（7 个生命周期）、`log`、`toast`、`layout` 与 `ui/`（Box/Button/Text/Modifier/Alignment） |
| `android-app/` | Gradle/AGP 8.7.3 壳工程：单活动 + WebView + `DshBridge`（JS-KT 桥） |
| `app/` | 示例应用（`main.subl`）与各 `@view`/`@android` 标准库源码 |

## 端到端流程

```
sublimationc compile <app-dir> -o out.js \
    -p "annotation processer" \
    -A "appDir=<android-app 路径>" -A "jsPath=<out.js 路径>" -A "outDir=<APK 输出目录>"
```

1. 语义检查（`@android` 白名单/`@impure`/无体校验、`@composable` 视图纪律）。
2. 生成 JS：`@android` 内建桥接为 `__dshAndroid(kind, args)`；生命周期注册为
   `__dshLifecycle_onCreate` 并经 `registerLifecycle` 告知 KT 端；`layout` 的视图树经
   `JSON.stringify` 传给 KT 端原生构建。
3. **编译后阶段**（`@postcompile build`）：把 `out.js` 拷进 `android-app/app/src/main/assets/app.js`，
   执行 `gradlew :app:assembleDebug`，把 APK 拷到 `outDir`。
4. `adb install` 后启动：壳活动的 WebView 载入 `app.js.html`，JS 注册生命周期 →
   KT 端按事件回调 JS（JS-KT 双向通信）。

## 架构要点

- **单活动**：`MainActivity` 持有 WebView 与 `DshBridge`（注入为全局 `__dshAndroid`）。
- **视图树协议**：JS 端 `@view` 结构体（Box/Button/Text）序列化为 `{tag, children, modifier, ...}` JSON；
  KT 端递归构建原生视图。函数型字段（`onClick`）注册为 `globalThis.__dshFn_<key>`、字段值换成 key，
  KT 端按 key 回调 JS 闭包。
- **重组合通道**：视图字段变异 → `__dc` 深拷贝 → `androidRecompose(viewName, ordinal, v)` → KT 端重建布局。
- **生命周期秩序**：KT 端在页面加载前触发的事件记入 `firedEvents`，JS 注册时补发，
  避免页面未就绪时注册丢失。

## 编译器参数（-A）

| 键 | 说明 |
| --- | --- |
| `appDir` | 安卓壳工程目录（含 `gradlew`/`app/`） |
| `jsPath` | 编译器 JS 产物路径 |
| `outDir` | APK 输出目录（默认 `appDir/build/apk`） |

## 相关仓库

- 编译器主仓：`KotlinProject/sublimation`（词法/语法/语义/JS 代码生成/注解处理器框架）。
