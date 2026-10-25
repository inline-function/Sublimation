# @mut（唯一可变所有权）

> wiki/02-类型-@mut · 事实来源：_langfacts.md §3/§6、Checker.kt 赋值闸与 binderNeedsCopy

`@mut T` 是「只有我能改」的承诺：同一时刻**只有一个**句柄可写该对象。

## 语法

```subl
var box : @mut T = e          // 绑定
@mut var sum = 0             // 变量注解（= Nat 的 @mut 视图）
@mut struct T(@mut n: Nat)  // 整体可变结构体；字段可再单独 @mut
fun f(x: @mut T): Null { … } // 参数（实参传值走拷贝判据）
box.n = 1                    // 字段赋值（box 持 @mut 权限）
sum = sum + 1                // 重绑（sum 类型含 @mut ⇒ 合法）
```

## 语义

### 赋值即版本前进

对 `@mut` 变量的每次写：
1. 新值生效（后续读看到新值）；
2. 旧值等式（如 `sum == 41`）作为事实注入 Γ 供给证明。

这就是 `@var 表达式` 的机制：`var m = var k = 0` 外层绑定的是**当前快照**，内层的前进历史以等式形态弥散。

### 不可变闸

类型不含 `@mut` 的变量再赋值 → **E-IMMUT-ASSIGN**。闸门仅在 `uncheckedDepth == 0` 时开启：

```subl
unchecked { n = 2 }          // 语句级局部豁免（唯一正路；别改类型去绕）
```

### 深拷触发

`@mut` 出现在**目标侧**时拷贝判据恒真（规则 1）——唯一所有权不容共享，见 [拷贝规则](02-类型-拷贝规则.md)：`@mut` 目标从任何源绑定都深拷。`@mut` 出现在**源侧**而目标是纯类型（规则 2）时也深拷。

## 示例

```subl
@mut struct T(@mut n: Nat)

@impure fun main() {
    var a : T = T(0)          // 纯视图，共享
    var b : @mut T = a        // 深拷（@mut 目标）
    b.n = 1
    print(a.n)                // 0

    @mut var sum = 0          // 可变累加器
    sum = sum + 42
    print(sum)                // 42
}
```

## 陷阱

- **`var` 关键字 ≠ 可变**：再赋值能力属于类型里的 `@mut`，与声明形式无关
- **`@mut` 目标永远深拷**：在循环里把大数据反复传 `fun f(x: @mut T)` 会放大拷贝代价——只读场景改用 `@ref` 形参
- **字段可单独 @mut**：`struct T(@mut n: Nat)` 里整体 `T` 不可变、仅 `n` 可写（对 `@mut` 句柄）；整体可变须 `@mut struct`
- **版本等式是旧值快照**：证明里用到的是 `sum == 41` 这类历史等式，不是「现在等于」
- **函数闭包捕获 @mut 变量被创建时深拷**：闭包持有的快照独立于外部后续前进
