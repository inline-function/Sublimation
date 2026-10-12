# 容器与函数式 API

## Collection 类型类

所有容器（List、Set、Map、Optional、Result 及 Tuple 族）实现 Collection：

```subl
class Collection {
    fun contains[T](element: T): Bool
    fun size(): Nat
    fun isEmpty(): Bool
}
```

Optional size 为 0/1、Result 同、Tuple 按元数。

## 构造函数

```subl
var xs = Cons(1, Cons(2, Nil()))          // List[Nat]
var s  = SetCons(1, SetCons(2, SetNil()))
var m  = MapCons("k", 1, MapNil())
var o  = Some(5)                          // Optional[Nat]
var r  = Ok("ok")                         // Result[Str, E]
var pr = (1, "a")                         // 元组
```

## 函数式 API（方法糖 + 普通调用两用）

```subl
xs.size(); xs.isEmpty(); xs.contains(2)
xs.map({ v -> v * 2 }); xs.filter({ v -> v > 1 })
xs.flatMap({ v -> Cons(v, Cons(v, Nil())) })
xs.fold(0, { v, acc -> acc + v })
xs.forEach({ v -> print(v) })       // @impure
xs.zip(ys); xs.any({..}); xs.all({..}); xs.indexOf(v)
xs.reverse(); xs.take(n); xs.drop(n); xs.sortBy({ a,b -> a < b })
xs.distinct(); xs.joinToString(",")
```

Set：add/remove/union/intersect/toList/…；Map：get/put/remove/containsKey/keys/values/…；Optional：map/filter/flatMap/…；Result：map/mapErr/flatMap/….

## 链式组合

```subl
var sum2 = xs.map({ v -> v * 2 }).filter({ v -> v > 3 }).fold(0, { v, acc -> acc + v })
```
