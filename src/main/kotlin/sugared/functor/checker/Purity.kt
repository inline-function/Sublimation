package sugared.functor.checker

/**
 * 纯度与可变性模块（占位，本轮无逻辑）。
 * 当前实现分布：
 *  - `Checker.registerPureFacts()`（Symbols.kt）：决策 34/54，为未标 @unpure 的函数注册 pure<name> 到 globalFacts
 *  - `Checker.checkCall()`（CallCheck.kt）：E-IMPURE-CALL 判定 + pure<callee> 放行
 *  - `Checker.checkStmt()`（Checker.kt）：E-IMMUT-ASSIGN 判定
 * 待类型级纯度/代数效应（决策 34 后续）落地后再行拆入本文件。
 */
