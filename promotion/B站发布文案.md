# B 站发布包（标题 / 简介 / 标签 / 封面）

## 标题候选（选一个，或 A/B 再选）

1. 我手写了一个把程序逻辑当命题推理的编程语言（v1.0 开源）
2. 用 Kotlin 写了个编译到 JS 的语言：让编译器证明你的程序是对的
3. 编译期命题推理做进语言里会怎样？Sublimation v1.0 开源演示
4. 零依赖编译到 JS 的编程语言，285 个测试全绿｜Sublimation v1.0

## 简介模板（可直接用）

```
Sublimation（凝华）：把类型系统与程序逻辑融合的编程语言。
编译器用 Kotlin 手写、零第三方依赖，源码直接编译为纯 JavaScript。

本期看点：
00:00 开场
00:15 Sublimation 是什么
00:50 现场演示（show 一键编译+运行）
02:30 核心特性：上下文弥散 / 目录即模块 / 类型类 / when / 纯度受控
03:50 v1.0 状态：285 测试 + 12 示例 + fat jar
04:20 开源地址与下载

GitHub：https://github.com/inline-function/Sublimation
（Release 里有可直接下载的 fat jar：java -jar 即可使用）

特性速览：
- 上下文弥散：函数前后置命题自动推理，不手动传参
- 目录即模块：一个目录 = 一个模块，.settings 显式挂载
- 类型类（Rust 机制）：class + impl for，隐式 self
- 真模式匹配 when：解构/嵌套/守卫/穷尽判定
- 纯度受控：非纯只有 unchecked 一个逃逸口
- 运算符即函数：1+1 == +(1,1)，无优先级

v1.0：P0–P11 全部完成，285 项测试全绿，12 个示例全部编译运行正确。
```

## 标签

`编程语言 编译器 Kotlin JavaScript 开源 类型系统 命题逻辑 智能转换 模式匹配 零依赖`

## 封面文案建议

- 主标题：`Sublimation v1.0`
- 副标题：`把程序逻辑当作命题推理的语言`
- 右下角小字：`零依赖 · 编译到 JS · 285 tests ✓`

## 评论区置顶（可选）

```
项目仓库：https://github.com/inline-function/Sublimation
下载 jar：仓库 Releases → v1.0.0 → sublimation.jar
文档：仓库 Wiki（7 页）/ doc/（7 份面向用户的完整文档）

最想试的特性？命题供给（让编译器自动验证调用前提）还是模块挂载？欢迎评论区聊。
```

## 注意

发布前请确认 GitHub Release v1.0.0 **已发布**（非草稿），视频里放的下载链接才对得上。