# show —— Sublimation v4 终版演示

两个**自包含**目录（各自带 `sublimation.jar` 与 `core\` 标准库，可单独拷走演示）：

| 目录 | 执行方式 | 内容 |
|---|---|---|
| `编译器\` | `compile`：语义检查 + 注解处理器 + 代码生成 → `show.js`，再由 node 运行 | `@classic` 经典定理证明（√2 无理性命题链）+ `@allMut` 注解处理器（`apt\allMut.subs`） |
| `解释器\` | `run-script`：脚本模式直接解释求值（顶层语句即程序，无需 main、不生成 JS） | 六例脚本（彼此不共享）：集合算法、银行账户、几何图形、可空类型、字符串数学、定理证明 |

## 运行

```bat
cd 编译器
run.bat          :: 生成 show.js + show.log 并运行（node）

:: 或

cd 解释器
run.bat          :: 依次解释执行全部 *.subs → run.log
run.bat bank     :: 只跑某一脚本（名字可带可不带 .subs）
```

仅依赖 **java（JRE 17+）**；`编译器\` 另需 **node**（编译产物为 JavaScript）。

> jar 重建：仓库根执行 `gradlew fatJar`，把 `build\libs\sublimation.jar` 拷回两个目录。
> core 标准库即仓库根 `core\`，两目录各保留一份副本以自包含。
