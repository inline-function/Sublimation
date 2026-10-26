# 宿主桥 ABI 规范（globalThis.__subl）

> 状态：定案（t37 队长裁决）。适用范围：--target=browser 与 --target=wasm 的平台调用；本文是 SfA/SfG/SfW 三线宿主的接口边界基准。

## 1. 裁决

- 宿主桥采用**单入口 dispatch** 为唯一 ABI：globalThis.__subl.call(op, args)。
- **禁止宿主挂裸名**（如 globalThis.readFile）：插件框架（t15/t16）与注解处理器白名单只认 __subl.call 这一个统一拦截点，能力门控与版本协商都走单入口。
- --target=node 靠 NODE_DIRECT 直连（require-fs / process.argv / setTimeout），不经桥、不受本规范约束——这是 node 的兼容回归基线。
- 安卓 SfA 路线走 ANDROID_CALL_SPECIAL 内建桥（既有路线，兼容保留）；三线若统一改由本入口分发，由队长统筹另版裁决。
- 历史命名：t35 临时名 __subl.d，t37 定名 __subl.call（旧名不再生成）。

## 2. 入口与装载契约

- 调用形态：globalThis.__subl.call(op, args)；op 为字符串操作名，args 为数组；同步返回结果或抛错。

编译器在非 node 目标按 **S-1 装载模型**生成产物（eval-then-run）：

- 产物**不自执行** main()；装载（eval）后宿主调 globalThis.__subl.main() 启动。
- 产物以**赋值式合并**导出：(globalThis.__subl || (globalThis.__subl = {})).main = <mainJsName>;——不覆盖宿主预置的 call/ver 等入口。
- 脚本式顶层序列（无 fun main() 声明）包成 __subl.main = () => { … }；主序列与 main 并存时主序列导出为 __subl.prelude，宿主应在 main 前调用。

## 3. 版本协商（预留）

- 形态：globalThis.__subl.ver() → 正整数 ABI 版本；当前规范版本 = 1。
- 编译器**暂不生成**该调用；宿主应先于产品装载提供 __subl.ver（返回 1），供产品/插件框架在需要时读出版本协商能力集。
- 后续若启用能力门控（如「op 需 ver≥2」），扩展点固定在此入口，不再新增全局裸名。

## 4. op 表与调用约定

op 名即 Sublimation 平台调用名（print 映射为 println）；参数按源码顺序进数组。所有 op 同步返回；失败一律 throw（见 §5）。

| op | args | 返回 | 备注 |
|---|---|---|---|
| println | 任意值（已 toString 化） | null | wasm 经桥；browser 直连 console.log |
| readFile | [path] | string（文件内容） | Result 型：失败包 Err |
| writeFile | [path, content] | null | 覆盖写 |
| appendFile | [path, content] | null | 追加写 |
| readDir | [path] | string[] | 目录条目名 |
| fileExists | [path] | boolean | |
| readLine | [] | string 或 null | 无输入返回 null |
| getArgs | [] | string[] | 启动参数 |
| sleep | [ms] | null | 宿主可异步实现；编译器经 awaited 包裹保持挂起语义 |

op 表来源：compiler/.../codegen/Target.kt 的 Platform.BRIDGE_OPS（单一事实源；wasm 额外拒绝 Platform.WASM_REJECTED 中的同步 IO op，编译期报 E-PLATFORM-UNAVAILABLE）。

@host 无体声明（t43）：编译期无函数体的 @host 声明（如 @wasm @host @impure fun demoBridge(): Str）在 browser/wasm 目标下，调用点同样走单入口——globalThis.__subl.call("demoBridge", [args...])，op 为声明名（宿主注册表 OPS/__SG_OPS 按此名分发）。省略的尾部默认形参按声明默认值补齐进桥参数；调用 @async 的 @host 声明时按挂起点加 await。宿主不再需要裸名过渡别名（globalThis.demoBridge 之类可直接删除）。node 目标保持裸名调用不变。

## 5. 错误协议

- 失败一律 throw new Error（JS 原生异常通道）；**message 建议以 [__subl] 前缀**，便于产品/插件过滤宿主来源错误。
  - 未知 op 标准形态：[__subl] unknown op: <op>
  - 参数不合法标准形态：[__subl] invalid args for <op>: <细节>
- 编译器侧包装：Result 型 IO op（readFile/readLine/writeFile/appendFile/readDir）的生成代码为 try { Ok(__subl.call(...)) } catch (x) { Err(x.message) }——宿主抛出的错误 message 原样进入 Err。非 Result 型 op（println/sleep/getArgs/fileExists）的异常直接上浮到 __subl.main() 调用方。

## 6. 三线宿主接口边界

| 线 | 宿主实现要点 |
|---|---|
| **SfW**（wasm 目标） | 必须提供 __subl.call（含 println——wasm 宿主无 console 保证）与 __subl.ver；同步 IO op 编译期已拒，宿主侧可不实现并在被调用时 throw unknown op 由 Err 通道报告 |
| **SfG**（browser/JS 目标） | 提供 __subl.call 实现 IO op；println 由编译器直连 console.log（宿主有 console），但 call 表仍含 println 供统一拦截 |
| **SfA**（Android） | 既有 ANDROID_CALL_SPECIAL 桥不动；默认 --target=node 兼容路径不受影响。后续若统一改由 __subl.call 分发，另版裁决并同步改三线宿主与处理器白名单 |

## 7. 模块级可用性

模块粒度的 target 可用性见 Target.kt 的 Platform.MODULE_AVAILABILITY（当前：core/io→{node, browser}、core/net→{node}、core/android→{node}；未列=全 target）。不可用模块在编译期报 E-PLATFORM-UNAVAILABLE（「模块 core/io 在目标 wasm 下不可用」），与 op 级拒绝共用同一诊断码。

## 8. 参考实现位置

- 入口生成：JsCodeGen.bridgeCall（globalThis.__subl.call(op, [args...])）
- 分派判定：JsCodeGen.platformOp（node 直连 / 桥 / wasm 拒）
- 模块可用性：MultiModule.runPhases（bag.error E-PLATFORM-UNAVAILABLE）
- op 表与平台表：codegen/Target.kt 的 object Platform
