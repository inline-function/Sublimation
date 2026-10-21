package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 安卓内建函数表（android doc/安卓标准库.md）：@android 注解的内建函数，
 * 机制与 @root 同构——无体声明 + 白名单门 + 编译期 JS 特判（JsCodeGen.androidCall）。
 *
 * 函数集（根文档 §安卓API）：
 * - log(msg, level)：输出到应用日志（adb/shizuku 可取）；
 * - toast(msg, long)：弹出提示；
 * - layout(v)：把视图结构体设为当前活动布局；
 * - 生命周期钩子 onCreate/onStart/onResume/onPause/onStop/onDestroy/onRestart：
 *   各接一个 () => Null 函数体，KT 端在活动事件时回调；
 * - androidRecompose(view, ordinal, v)：重组通知桥（recomposion$X$N 的统一出口）。
 *
 * 签名在调用点没有用户声明时作回退（与 builtinimpure/builtinPure 同构）。
 */

/** @android 白名单：允许 @android 无体声明的内建函数名集合。 */
val ANDROID_WHITELIST: Set<String> = setOf(
    "log", "toast", "layout",
    "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onRestart",
    "androidRecompose",
)

/** 安卓内建函数签名（按名查询；impure）。 */
fun builtinAndroid(name: String): FunDecl? = when (name) {
    "log" -> FunDecl("log", listOf("impure", "android"), emptyList(),
        listOf(Param("msg", namedT("Str")), Param("level", namedT("Str"))),
        emptyList(), namedT("Null"), emptyList(), null)
    "toast" -> FunDecl("toast", listOf("impure", "android"), emptyList(),
        listOf(Param("msg", namedT("Str")), Param("long", namedT("Bool"))),
        emptyList(), namedT("Null"), emptyList(), null)
    "layout" -> FunDecl("layout", listOf("impure", "android"), emptyList(),
        listOf(Param("v", namedT("Any"))),
        emptyList(), namedT("Null"), emptyList(), null)
    "onStart" -> FunDecl("onStart", listOf("impure", "android"), emptyList(),
        listOf(Param("body", FunType(emptyList(), namedT("Null")))),
        emptyList(), namedT("Null"), emptyList(), null)
    "onCreate" -> FunDecl("onCreate", listOf("impure", "android"), emptyList(),
        listOf(Param("body", FunType(emptyList(), namedT("Null")))),
        emptyList(), namedT("Null"), emptyList(), null)
    "onResume" -> FunDecl("onResume", listOf("impure", "android"), emptyList(),
        listOf(Param("body", FunType(emptyList(), namedT("Null")))),
        emptyList(), namedT("Null"), emptyList(), null)
    "onPause" -> FunDecl("onPause", listOf("impure", "android"), emptyList(),
        listOf(Param("body", FunType(emptyList(), namedT("Null")))),
        emptyList(), namedT("Null"), emptyList(), null)
    "onStop" -> FunDecl("onStop", listOf("impure", "android"), emptyList(),
        listOf(Param("body", FunType(emptyList(), namedT("Null")))),
        emptyList(), namedT("Null"), emptyList(), null)
    "onDestroy" -> FunDecl("onDestroy", listOf("impure", "android"), emptyList(),
        listOf(Param("body", FunType(emptyList(), namedT("Null")))),
        emptyList(), namedT("Null"), emptyList(), null)
    "onRestart" -> FunDecl("onRestart", listOf("impure", "android"), emptyList(),
        listOf(Param("body", FunType(emptyList(), namedT("Null")))),
        emptyList(), namedT("Null"), emptyList(), null)
    "androidRecompose" -> FunDecl("androidRecompose", listOf("impure", "android"), emptyList(),
        listOf(Param("view", namedT("Str")), Param("ordinal", namedT("Nat")), Param("v", namedT("Any"))),
        emptyList(), namedT("Null"), emptyList(), null)
    else -> null
}