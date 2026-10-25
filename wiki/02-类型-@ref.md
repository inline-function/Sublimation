# @ref（共享只读视图）

> wiki/02-类型-@ref · 事实来源：_langfacts.md §6、root doc《副作用与可变性控制》

`@ref T` 是「我借来看看」：对象可能在别处被改，但你不能通过它改。

## 语法

```subl
var c : @ref T = e            // 绑定只读视图
@ref var d : @mut Cell = c   // @ref + @mut = 共享可变别名
fun f(x: @ref T): Null { … } // 形参只读借用（免深拷）
print(c.n)                    // 读：合法
c.n = 1                       // 写：非法（@ref T 不可写）
```

## 语义

### 永远共享

只要目标类型是 `@ref T`（不带 @mut），绑定**从任何源都不深拷**——引用就是来看的：

- `var f : @ref T = e`（e 是纯或 @mut 或 @ref）一律共享同一对象
- 共享意味着读到的值**会随手别处修改而变**

### 与 @mut 组合成别名

`@ref @mut T`：共享**可变**视图。按拷贝规则，源为 `@mut T`/同为 `@ref @mut T` 时**别名共享**（都有写权，写互相可见）。

### 不可预测性待遇

「可能被别处改」意味着读 `@ref` 的结果**不可静态预测**——因此在上下文（证明）系统里，`@ref` 与 `@mut` 享受同等待遇：涉及 `$`/`$n` 的后件若读取引用值，约束需谨慎（其值可能在兑现前已变）。

### 免拷贝形参

循环里反复传大数据时，`fun f(x: @ref T)` 避开每次调用的深拷代价（`@mut` 形参会触发规则 1 深拷）。

## 示例

```subl
@mut struct Cell(@mut n: Nat)

@impure fun main() {
    @mut var c : Cell = Cell(0)
    var view : @ref Cell = c        // 共享只读视图
    c.n = 4
    print(view.n)                   // 4（视图看到最新值）

    @mut var d : Cell = Cell(0)
    @ref var alias : @mut Cell = d  // 别名（共享可变）
    d.n = 9
    print(alias.n)                  // 9
}
```

## 陷阱

- **`@ref T` 永远不深拷**：想脱离外部修改须显式转纯类型（`var t : T = view` 走规则 3 深拷）
- **`@ref` 的读不是快照**：两次 `print(view.n)` 之间手别处一改，结果就变——证明时把引用读当不可预测量
- **`@ref T` 不可写**：只有 `@ref @mut T`（或 `@mut`）才有写权
- **别名是双向的**：`@ref var alias : @mut Cell = d` 之后 `alias.n = 1` 与 `d.n = 1` 等价
- **形参 `@ref` 省拷贝**但要求调用方接受「被看」——不能与 `@mut` 唯一所有权并存于同一对象
