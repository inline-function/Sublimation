"use strict";
(() => {
  const None = () => ({ tag: "None" });
  const Some = (a0) => ({ tag: "Some", 0: a0 });
  const __dshtoStr = (v) => {
    if (v === null || v === undefined) return String(v);
    if (Array.isArray(v)) return "[" + v.map(__dshtoStr).join(",") + "]";
    if (typeof v === "object" && Object.keys(v).length === 1 && "tag" in v) return String(v.tag);
    return String(v);
  };
  const __isa = (v, t) => {
    if (t === "Nat" || t === "Int" || t === "Rat") return typeof v === "number";
    if (t === "Str") return typeof v === "string";
    if (t === "Bool") return typeof v === "boolean";
    if (t === "Null") return v === null || (v && v.tag === "Null");
    if (t === "(fn)") return typeof v === "function";
    if (t === "List") return Array.isArray(v);
    return v !== null && typeof v === "object" && v.tag === t;
  };
  const __eq = (a, b) => {
    if (a === b) return true;
    if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
    if (a.tag !== undefined && a.tag !== b.tag) return false;
    const ka = Object.keys(a), kb = Object.keys(b);
    if (ka.length !== kb.length) return false;
    return ka.every(k => __eq(a[k], b[k]));
  };
  async function main() {
    return console.log(__dshtoStr("证明成功!"));
  }
  function stdlib__size(d_Foldable_F, fa) {
    return d_Foldable_F.fold(d_Foldable_F, fa, fa, 0, (acc, _) => (acc + 1));
  }
  function stdlib__isEmpty(d_Foldable_F, fa) {
    return __eq(stdlib__size(d_Foldable_F, fa), 0);
  }
  function stdlib__contains(d_Foldable_F, fa, x) {
    return d_Foldable_F.fold(d_Foldable_F, fa, fa, false, (acc, y) => __eq((acc || y), x));
  }
  function stdlib__count(d_Foldable_F, fa, p) {
    return d_Foldable_F.fold(d_Foldable_F, fa, fa, 0, (acc, x) => (p(x) ? (() => {
  return (acc + 1);
})() : (() => {
  return acc;
})()));
  }
  function stdlib__any(d_Foldable_F, fa, p) {
    return d_Foldable_F.fold(d_Foldable_F, fa, fa, false, (acc, x) => (acc || p(x)));
  }
  function stdlib__all(d_Foldable_F, fa, p) {
    return d_Foldable_F.fold(d_Foldable_F, fa, fa, true, (acc, x) => (acc && p(x)));
  }
  function stdlib__find(d_Foldable_F, fa, p) {
    return d_Foldable_F.fold(d_Foldable_F, fa, fa, None(), (acc, x) => (() => {
  const __s0 = acc;
  if ((__s0.tag === "Some")) {  return acc; }
  if ((__s0.tag === "None")) {  return (p(x) ? (() => {
  return Some(x);
})() : (() => {
  return None();
})()); }
  return null;
})());
  }
  function stdlib__forEach(d_Foldable_F, fa, f) {
    return d_Foldable_F.fold(d_Foldable_F, fa, fa, null, (_, x) => f(x));
  }
  const dict_stdlib_Functor_List = {
    map: (d, fa, f) => (() => {
  const __s1 = fa;
  if (((Array.isArray(__s1) && __s1.length === 0))) {  return []; }
  if (((Array.isArray(__s1) && __s1.length >= 1))) { const h = __s1[0]; const t = __s1.slice(1);  return [f(h), ...(dict_stdlib_Functor_List.map(dict_stdlib_Functor_List, t, f))]; }
  return null;
})()
  };
  const dict_stdlib_Foldable_List = {
    fold: (d, fa, init, f) => (() => {
  const __s2 = fa;
  if (((Array.isArray(__s2) && __s2.length === 0))) {  return init; }
  if (((Array.isArray(__s2) && __s2.length >= 1))) { const h = __s2[0]; const t = __s2.slice(1);  return dict_stdlib_Foldable_List.fold(dict_stdlib_Foldable_List, t, f(init, h), f); }
  return null;
})()
  };
  const dict_stdlib_Monad_List = {
    pure: (d, x) => [x, ...([])],
    flatMap: (d, fa, f) => (() => {
  const __s3 = fa;
  if (((Array.isArray(__s3) && __s3.length === 0))) {  return []; }
  if (((Array.isArray(__s3) && __s3.length >= 1))) { const h = __s3[0]; const t = __s3.slice(1);  return (f(h)).concat(dict_stdlib_Monad_List.flatMap(dict_stdlib_Monad_List, t, f)); }
  return null;
})()
  };
  function stdlib__emptyList() {
    return [];
  }
  function stdlib__cons(x, xs) {
    return [x, ...(xs)];
  }
  function stdlib__listOf(items) {
    return items;
  }
  function stdlib__head(xs) {
    return (() => {
  const __s4 = xs;
  if (((Array.isArray(__s4) && __s4.length === 0))) {  return None(); }
  if (((Array.isArray(__s4) && __s4.length >= 1))) { const h = __s4[0];  return Some(h); }
  return null;
})();
  }
  function stdlib__last(xs) {
    return (() => {
  const __s5 = xs;
  if (((Array.isArray(__s5) && __s5.length === 0))) {  return None(); }
  if (((Array.isArray(__s5) && __s5.length >= 1)) && ((Array.isArray(__s5.slice(1)) && __s5.slice(1).length === 0))) { const h = __s5[0];  return Some(h); }
  if (((Array.isArray(__s5) && __s5.length >= 1))) { const t = __s5.slice(1);  return (() => { const a = t; return a.length ? Some(a[a.length - 1]) : None(); })(); }
  return null;
})();
  }
  function stdlib__elementAt(xs, i) {
    return (() => {
  const __s6 = xs;
  if (((Array.isArray(__s6) && __s6.length === 0))) {  return None(); }
  if (((Array.isArray(__s6) && __s6.length >= 1))) { const h = __s6[0]; const t = __s6.slice(1);  return (__eq(i, 0) ? (() => {
  return Some(h);
})() : (() => {
  return (() => { const a = t; const i = (i - 1); return i >= 0 && i < a.length ? Some(a[i]) : None(); })();
})()); }
  return null;
})();
  }
  function stdlib__get$List(xs, i) {
    return (() => {
  const __s7 = (() => { const a = xs; const i = i; return i >= 0 && i < a.length ? Some(a[i]) : None(); })();
  if ((__s7.tag === "Some")) { const v = __s7[0];  return v; }
  if ((__s7.tag === "None")) {  return (() => { throw new Error('panic: unreachable') })(); }
  return null;
})();
  }
  function stdlib__firstOr(xs, d) {
    return stdlib__getOrElse((() => { const a = xs; return a.length ? Some(a[0]) : None(); })(), d);
  }
  function stdlib__indexOf(xs, x) {
    return (() => {
  const __s8 = xs;
  if (((Array.isArray(__s8) && __s8.length === 0))) {  return None(); }
  if (((Array.isArray(__s8) && __s8.length >= 1))) { const h = __s8[0]; const t = __s8.slice(1);  return (__eq(h, x) ? (() => {
  return Some(0);
})() : (() => {
  return (() => {
  const __s9 = (() => { const i = (t).indexOf(x); return i === -1 ? None() : Some(i); })();
  if ((__s9.tag === "Some")) { const n = __s9[0];  return Some((n + 1)); }
  if ((__s9.tag === "None")) {  return None(); }
  return null;
})();
})()); }
  return null;
})();
  }
  function stdlib__append(xs, ys) {
    return (() => {
  const __s10 = xs;
  if (((Array.isArray(__s10) && __s10.length === 0))) {  return ys; }
  if (((Array.isArray(__s10) && __s10.length >= 1))) { const h = __s10[0]; const t = __s10.slice(1);  return [h, ...((t).concat(ys))]; }
  return null;
})();
  }
  function stdlib__reverse(xs) {
    return (() => {
  const __s11 = xs;
  if (((Array.isArray(__s11) && __s11.length === 0))) {  return []; }
  if (((Array.isArray(__s11) && __s11.length >= 1))) { const h = __s11[0]; const t = __s11.slice(1);  return ((t).slice().reverse()).concat([h, ...([])]); }
  return null;
})();
  }
  function stdlib__take(xs, n) {
    return (() => {
  const __s12 = xs;
  if (((Array.isArray(__s12) && __s12.length === 0))) {  return []; }
  if (((Array.isArray(__s12) && __s12.length >= 1))) { const h = __s12[0]; const t = __s12.slice(1);  return (__eq(n, 0) ? (() => {
  return [];
})() : (() => {
  const r = (t).slice(0, (n - 1));
  return [h, ...(r)];
})()); }
  return null;
})();
  }
  function stdlib__drop(xs, n) {
    return (() => {
  const __s13 = xs;
  if (((Array.isArray(__s13) && __s13.length === 0))) {  return []; }
  if (((Array.isArray(__s13) && __s13.length >= 1))) { const h = __s13[0]; const t = __s13.slice(1);  return (__eq(n, 0) ? (() => {
  return xs;
})() : (() => {
  const r = (t).slice((n - 1));
  return r;
})()); }
  return null;
})();
  }
  function stdlib__clear(xs) {
    return [];
  }
  function stdlib__filter$List(xs, p) {
    return (() => {
  const __s14 = xs;
  if (((Array.isArray(__s14) && __s14.length === 0))) {  return []; }
  if (((Array.isArray(__s14) && __s14.length >= 1))) { const h = __s14[0]; const t = __s14.slice(1);  return (p(h) ? (() => {
  return [h, ...((t).filter(p))];
})() : (() => {
  return (t).filter(p);
})()); }
  return null;
})();
  }
  function stdlib__distinct(xs) {
    return (() => {
  const __s15 = xs;
  if (((Array.isArray(__s15) && __s15.length === 0))) {  return []; }
  if (((Array.isArray(__s15) && __s15.length >= 1))) { const h = __s15[0]; const t = __s15.slice(1);  return (stdlib__contains(dict_stdlib_Foldable_List, t, h) ? (() => {
  return stdlib__distinct(t);
})() : (() => {
  return [h, ...(stdlib__distinct(t))];
})()); }
  return null;
})();
  }
  function stdlib__sortBy(xs, f) {
    return (() => {
  const __s16 = xs;
  if (((Array.isArray(__s16) && __s16.length === 0))) {  return []; }
  if (((Array.isArray(__s16) && __s16.length >= 1))) { const h = __s16[0]; const t = __s16.slice(1);  return stdlib__insertSort(h, (t).slice().sort((a, b) => (f)(a, b) ? -1 : 1), f); }
  return null;
})();
  }
  function stdlib__insertSort(x, xs, f) {
    return (() => {
  const __s17 = xs;
  if (((Array.isArray(__s17) && __s17.length === 0))) {  return [x, ...([])]; }
  if (((Array.isArray(__s17) && __s17.length >= 1))) { const h = __s17[0]; const t = __s17.slice(1);  return (f(x, h) ? (() => {
  return [x, ...([h, ...(t)])];
})() : (() => {
  return [h, ...(stdlib__insertSort(x, t, f))];
})()); }
  return null;
})();
  }
  function stdlib__range(a, b) {
    if ((a < b)) {
      // unchecked
      const rest = stdlib__range((a + 1), b);
      return [a, ...(rest)];
    }
    return [];
  }
  function stdlib__zip(xs, ys) {
    return (() => {
  const __s18 = xs;
  if (((Array.isArray(__s18) && __s18.length === 0))) {  return []; }
  if (((Array.isArray(__s18) && __s18.length >= 1))) { const h = __s18[0]; const t = __s18.slice(1);  return (() => {
  const __s19 = ys;
  if (((Array.isArray(__s19) && __s19.length === 0))) {  return []; }
  if (((Array.isArray(__s19) && __s19.length >= 1))) { const h2 = __s19[0]; const t2 = __s19.slice(1);  return [[h, h2], ...(stdlib__zip(t, t2))]; }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__joinToString(xs, sep) {
    return dict_stdlib_Foldable_List.fold(dict_stdlib_Foldable_List, xs, "", (acc, x) => (__eq(acc, "") ? (() => {
  return __dshtoStr(x);
})() : (() => {
  return (acc + (sep + __dshtoStr(x)));
})()));
  }
  function stdlib__sum(xs) {
    return dict_stdlib_Foldable_List.fold(dict_stdlib_Foldable_List, xs, 0, (acc, x) => (acc + x));
  }
  function stdlib__maxNat(xs) {
    return (() => {
  const __s20 = xs;
  if (((Array.isArray(__s20) && __s20.length === 0))) {  return None(); }
  if (((Array.isArray(__s20) && __s20.length >= 1))) { const h = __s20[0]; const t = __s20.slice(1);  return (() => {
  const __s21 = t;
  if (((Array.isArray(__s21) && __s21.length === 0))) {  return Some(h); }
  if (((Array.isArray(__s21) && __s21.length >= 1))) {  return (() => {
  const __s22 = stdlib__maxNat(t);
  if ((__s22.tag === "Some")) { const v = __s22[0];  return ((h > v) ? (() => {
  return Some(h);
})() : (() => {
  return Some(v);
})()); }
  if ((__s22.tag === "None")) {  return Some(h); }
  return null;
})(); }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__minNat(xs) {
    return (() => {
  const __s23 = xs;
  if (((Array.isArray(__s23) && __s23.length === 0))) {  return None(); }
  if (((Array.isArray(__s23) && __s23.length >= 1))) { const h = __s23[0]; const t = __s23.slice(1);  return (() => {
  const __s24 = t;
  if (((Array.isArray(__s24) && __s24.length === 0))) {  return Some(h); }
  if (((Array.isArray(__s24) && __s24.length >= 1))) {  return (() => {
  const __s25 = stdlib__minNat(t);
  if ((__s25.tag === "Some")) { const v = __s25[0];  return ((h < v) ? (() => {
  return Some(h);
})() : (() => {
  return Some(v);
})()); }
  if ((__s25.tag === "None")) {  return Some(h); }
  return null;
})(); }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__Optional__Some(a0) { return { tag: "Some", 0: a0 }; }
  const stdlib__Some = stdlib__Optional__Some;
  function stdlib__Optional__None() { return { tag: "None" }; }
  const stdlib__None = stdlib__Optional__None;
  const dict_stdlib_Functor_Optional = {
    map: (d, fa, f) => (() => {
  const __s26 = fa;
  if ((__s26.tag === "Some")) { const v = __s26[0];  return Some(f(v)); }
  if ((__s26.tag === "None")) {  return None(); }
  return null;
})()
  };
  const dict_stdlib_Foldable_Optional = {
    fold: (d, fa, init, f) => (() => {
  const __s27 = fa;
  if ((__s27.tag === "Some")) { const v = __s27[0];  return f(init, v); }
  if ((__s27.tag === "None")) {  return init; }
  return null;
})()
  };
  const dict_stdlib_Monad_Optional = {
    pure: (d, x) => Some(x),
    flatMap: (d, fa, f) => (() => {
  const __s28 = fa;
  if ((__s28.tag === "Some")) { const v = __s28[0];  return f(v); }
  if ((__s28.tag === "None")) {  return None(); }
  return null;
})()
  };
  function stdlib__isSome(o) {
    return (() => {
  const __s29 = o;
  if ((__s29.tag === "Some")) {  return true; }
  if ((__s29.tag === "None")) {  return false; }
  return null;
})();
  }
  function stdlib__isNone(o) {
    return (() => {
  const __s30 = o;
  if ((__s30.tag === "Some")) {  return false; }
  if ((__s30.tag === "None")) {  return true; }
  return null;
})();
  }
  function stdlib__getOrElse(o, d) {
    return (() => {
  const __s31 = o;
  if ((__s31.tag === "Some")) { const v = __s31[0];  return v; }
  if ((__s31.tag === "None")) {  return d; }
  return null;
})();
  }
  function stdlib__orElse(o, d) {
    return (() => {
  const __s32 = o;
  if ((__s32.tag === "Some")) {  return o; }
  if ((__s32.tag === "None")) {  return d; }
  return null;
})();
  }
  function stdlib__filter$Optional(o, p) {
    return (() => {
  const __s33 = o;
  if ((__s33.tag === "Some")) { const v = __s33[0];  return (p(v) ? (() => {
  return Some(v);
})() : (() => {
  return None();
})()); }
  if ((__s33.tag === "None")) {  return None(); }
  return null;
})();
  }
  function stdlib__toResult(o, e) {
    return (() => {
  const __s34 = o;
  if ((__s34.tag === "Some")) { const v = __s34[0];  return stdlib__Ok(v); }
  if ((__s34.tag === "None")) {  return stdlib__Err(e); }
  return null;
})();
  }
  function stdlib__toList$Optional(o) {
    return (() => {
  const __s35 = o;
  if ((__s35.tag === "Some")) { const v = __s35[0];  return [v, ...([])]; }
  if ((__s35.tag === "None")) {  return []; }
  return null;
})();
  }
  function stdlib__Result__Ok(a0) { return { tag: "Ok", 0: a0 }; }
  const stdlib__Ok = stdlib__Result__Ok;
  function stdlib__Result__Err(a0) { return { tag: "Err", 0: a0 }; }
  const stdlib__Err = stdlib__Result__Err;
  const dict_stdlib_Functor_Result = {
    map: (d, fa, f) => (() => {
  const __s36 = fa;
  if ((__s36.tag === "Ok")) { const v = __s36[0];  return stdlib__Ok(f(v)); }
  if ((__s36.tag === "Err")) { const e = __s36[0];  return stdlib__Err(e); }
  return null;
})()
  };
  const dict_stdlib_Foldable_Result = {
    fold: (d, fa, init, f) => (() => {
  const __s37 = fa;
  if ((__s37.tag === "Ok")) { const v = __s37[0];  return f(init, v); }
  if ((__s37.tag === "Err")) {  return init; }
  return null;
})()
  };
  const dict_stdlib_Monad_Result = {
    pure: (d, x) => stdlib__Ok(x),
    flatMap: (d, fa, f) => (() => {
  const __s38 = fa;
  if ((__s38.tag === "Ok")) { const v = __s38[0];  return f(v); }
  if ((__s38.tag === "Err")) { const e = __s38[0];  return stdlib__Err(e); }
  return null;
})()
  };
  function stdlib__isOk(r) {
    return (() => {
  const __s39 = r;
  if ((__s39.tag === "Ok")) {  return true; }
  if ((__s39.tag === "Err")) {  return false; }
  return null;
})();
  }
  function stdlib__isErr(r) {
    return (() => {
  const __s40 = r;
  if ((__s40.tag === "Ok")) {  return false; }
  if ((__s40.tag === "Err")) {  return true; }
  return null;
})();
  }
  function stdlib__unwrapOr(r, d) {
    return (() => {
  const __s41 = r;
  if ((__s41.tag === "Ok")) { const v = __s41[0];  return v; }
  if ((__s41.tag === "Err")) {  return d; }
  return null;
})();
  }
  function stdlib__mapErr(r, f) {
    return (() => {
  const __s42 = r;
  if ((__s42.tag === "Ok")) { const v = __s42[0];  return stdlib__Ok(v); }
  if ((__s42.tag === "Err")) { const e = __s42[0];  return stdlib__Err(f(e)); }
  return null;
})();
  }
  function stdlib__filter$Result(r, p) {
    return r;
  }
  function stdlib__toList$Result(r) {
    return (() => {
  const __s43 = r;
  if ((__s43.tag === "Ok")) { const v = __s43[0];  return [v, ...([])]; }
  if ((__s43.tag === "Err")) {  return []; }
  return null;
})();
  }
  function stdlib__Set__SetNil() { return { tag: "SetNil" }; }
  const stdlib__SetNil = stdlib__Set__SetNil;
  function stdlib__Set__SetCons(a0, a1) { return { tag: "SetCons", 0: a0, 1: a1 }; }
  const stdlib__SetCons = stdlib__Set__SetCons;
  const dict_stdlib_Functor_Set = {
    map: (d, fa, f) => (() => {
  const __s44 = fa;
  if ((__s44.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s44.tag === "SetCons")) { const h = __s44[0]; const t = __s44[1];  return stdlib__add(dict_stdlib_Functor_Set.map(dict_stdlib_Functor_Set, t, f), f(h)); }
  return null;
})()
  };
  const dict_stdlib_Foldable_Set = {
    fold: (d, fa, init, f) => (() => {
  const __s45 = fa;
  if ((__s45.tag === "SetNil")) {  return init; }
  if ((__s45.tag === "SetCons")) { const h = __s45[0]; const t = __s45[1];  return dict_stdlib_Foldable_Set.fold(dict_stdlib_Foldable_Set, t, f(init, h), f); }
  return null;
})()
  };
  function stdlib__setEmpty() {
    return stdlib__SetNil();
  }
  function stdlib__setOf(items) {
    return stdlib__SetNil();
  }
  function stdlib__add(s, x) {
    return (stdlib__contains(dict_stdlib_Foldable_Set, s, x) ? (() => {
  return s;
})() : (() => {
  return stdlib__SetCons(x, s);
})());
  }
  function stdlib__remove$Set(s, x) {
    return (() => {
  const __s46 = s;
  if ((__s46.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s46.tag === "SetCons")) { const h = __s46[0]; const t = __s46[1];  return (__eq(h, x) ? (() => {
  return t;
})() : (() => {
  return stdlib__SetCons(h, stdlib__remove$Set(t, x));
})()); }
  return null;
})();
  }
  function stdlib__union(s1, s2) {
    return (() => {
  const __s47 = s1;
  if ((__s47.tag === "SetNil")) {  return s2; }
  if ((__s47.tag === "SetCons")) { const h = __s47[0]; const t = __s47[1];  return (stdlib__contains(dict_stdlib_Foldable_Set, s2, h) ? (() => {
  return stdlib__union(t, s2);
})() : (() => {
  return stdlib__SetCons(h, stdlib__union(t, s2));
})()); }
  return null;
})();
  }
  function stdlib__intersect(s1, s2) {
    return (() => {
  const __s48 = s1;
  if ((__s48.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s48.tag === "SetCons")) { const h = __s48[0]; const t = __s48[1];  return (stdlib__contains(dict_stdlib_Foldable_Set, s2, h) ? (() => {
  return stdlib__SetCons(h, stdlib__intersect(t, s2));
})() : (() => {
  return stdlib__intersect(t, s2);
})()); }
  return null;
})();
  }
  function stdlib__filter$Set(s, p) {
    return (() => {
  const __s49 = s;
  if ((__s49.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s49.tag === "SetCons")) { const h = __s49[0]; const t = __s49[1];  return (p(h) ? (() => {
  return stdlib__SetCons(h, stdlib__filter$Set(t, p));
})() : (() => {
  return stdlib__filter$Set(t, p);
})()); }
  return null;
})();
  }
  function stdlib__flatMap(s, f) {
    return (() => {
  const __s50 = s;
  if ((__s50.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s50.tag === "SetCons")) { const h = __s50[0]; const t = __s50[1];  return stdlib__union(f(h), stdlib__flatMap(t, f)); }
  return null;
})();
  }
  function stdlib__toList$Set(s) {
    return (() => {
  const __s51 = s;
  if ((__s51.tag === "SetNil")) {  return []; }
  if ((__s51.tag === "SetCons")) { const h = __s51[0]; const t = __s51[1];  return [h, ...(stdlib__toList$Set(t))]; }
  return null;
})();
  }
  function stdlib__Map__MapNil() { return { tag: "MapNil" }; }
  const stdlib__MapNil = stdlib__Map__MapNil;
  function stdlib__Map__MapCons(a0, a1, a2) { return { tag: "MapCons", 0: a0, 1: a1, 2: a2 }; }
  const stdlib__MapCons = stdlib__Map__MapCons;
  const dict_stdlib_Functor_Map = {
    map: (d, fa, f) => (() => {
  const __s52 = fa;
  if ((__s52.tag === "MapNil")) {  return stdlib__MapNil(); }
  if ((__s52.tag === "MapCons")) { const k = __s52[0]; const v = __s52[1]; const t = __s52[2];  return stdlib__MapCons(k, f(v), dict_stdlib_Functor_Map.map(dict_stdlib_Functor_Map, t, f)); }
  return null;
})()
  };
  const dict_stdlib_Foldable_Map = {
    fold: (d, fa, init, f) => (() => {
  const __s53 = fa;
  if ((__s53.tag === "MapNil")) {  return init; }
  if ((__s53.tag === "MapCons")) { const v = __s53[1]; const t = __s53[2];  return dict_stdlib_Foldable_Map.fold(dict_stdlib_Foldable_Map, t, f(init, v), f); }
  return null;
})()
  };
  function stdlib__mapEmpty() {
    return stdlib__MapNil();
  }
  function stdlib__get$Map(m, k) {
    return (() => {
  const __s54 = m;
  if ((__s54.tag === "MapNil")) {  return None(); }
  if ((__s54.tag === "MapCons")) { const k0 = __s54[0]; const v0 = __s54[1]; const t = __s54[2];  return (__eq(k0, k) ? (() => {
  return Some(v0);
})() : (() => {
  return stdlib__get$Map(t, k);
})()); }
  return null;
})();
  }
  function stdlib__containsKey(m, k) {
    return (() => {
  const __s55 = m;
  if ((__s55.tag === "MapNil")) {  return false; }
  if ((__s55.tag === "MapCons")) { const k0 = __s55[0]; const t = __s55[2];  return (__eq(k0, k) ? (() => {
  return true;
})() : (() => {
  return stdlib__containsKey(t, k);
})()); }
  return null;
})();
  }
  function stdlib__keys(m) {
    return (() => {
  const __s56 = m;
  if ((__s56.tag === "MapNil")) {  return []; }
  if ((__s56.tag === "MapCons")) { const k0 = __s56[0]; const t = __s56[2];  return [k0, ...(stdlib__keys(t))]; }
  return null;
})();
  }
  function stdlib__values(m) {
    return (() => {
  const __s57 = m;
  if ((__s57.tag === "MapNil")) {  return []; }
  if ((__s57.tag === "MapCons")) { const v0 = __s57[1]; const t = __s57[2];  return [v0, ...(stdlib__values(t))]; }
  return null;
})();
  }
  function stdlib__put(m, k, v) {
    return stdlib__MapCons(k, v, stdlib__remove$Map(m, k));
  }
  function stdlib__remove$Map(m, k) {
    return (() => {
  const __s58 = m;
  if ((__s58.tag === "MapNil")) {  return stdlib__MapNil(); }
  if ((__s58.tag === "MapCons")) { const k0 = __s58[0]; const v0 = __s58[1]; const t = __s58[2];  return (__eq(k0, k) ? (() => {
  return t;
})() : (() => {
  return stdlib__MapCons(k0, v0, stdlib__remove$Map(t, k));
})()); }
  return null;
})();
  }
  function stdlib__mapValues(m, f) {
    return (() => {
  const __s59 = m;
  if ((__s59.tag === "MapNil")) {  return stdlib__MapNil(); }
  if ((__s59.tag === "MapCons")) { const k0 = __s59[0]; const v0 = __s59[1]; const t = __s59[2];  return stdlib__MapCons(k0, f(v0), stdlib__mapValues(t, f)); }
  return null;
})();
  }
  function stdlib__mapKeys(m, f) {
    return (() => {
  const __s60 = m;
  if ((__s60.tag === "MapNil")) {  return stdlib__MapNil(); }
  if ((__s60.tag === "MapCons")) { const k0 = __s60[0]; const v0 = __s60[1]; const t = __s60[2];  return stdlib__put(stdlib__mapKeys(t, f), f(k0), v0); }
  return null;
})();
  }
  function stdlib__filter$Map(m, p) {
    return (() => {
  const __s61 = m;
  if ((__s61.tag === "MapNil")) {  return stdlib__MapNil(); }
  if ((__s61.tag === "MapCons")) { const k0 = __s61[0]; const v0 = __s61[1]; const t = __s61[2];  return (p(v0) ? (() => {
  return stdlib__MapCons(k0, v0, stdlib__filter$Map(t, p));
})() : (() => {
  return stdlib__filter$Map(t, p);
})()); }
  return null;
})();
  }
  function stdlib__merge(m1, m2) {
    return (() => {
  const __s62 = m2;
  if ((__s62.tag === "MapNil")) {  return m1; }
  if ((__s62.tag === "MapCons")) { const k0 = __s62[0]; const v0 = __s62[1]; const t = __s62[2];  return stdlib__merge(stdlib__put(m1, k0, v0), t); }
  return null;
})();
  }
  function stdlib__toList$Map(m) {
    return stdlib__values(m);
  }
  const dict_stdlib_Tuple_EmptyTuple = {
    size: (d, __self) => 0,
    isEmpty: (d, __self) => true
  };
  const dict_stdlib_Tuple_SingleTuple = {
    size: (d, __self) => 1,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b_ = {
    size: (d, __self) => 2,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b__c_ = {
    size: (d, __self) => 3,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b__c__d_ = {
    size: (d, __self) => 4,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b__c__d__e_ = {
    size: (d, __self) => 5,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b__c__d__e__f_ = {
    size: (d, __self) => 6,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b__c__d__e__f__g_ = {
    size: (d, __self) => 7,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b__c__d__e__f__g__h_ = {
    size: (d, __self) => 8,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b__c__d__e__f__g__h__i_ = {
    size: (d, __self) => 9,
    isEmpty: (d, __self) => false
  };
  const dict_stdlib_Tuple__a__b__c__d__e__f__g__h__i__j_ = {
    size: (d, __self) => 10,
    isEmpty: (d, __self) => false
  };
  function stdlib__toSet(xs) {
    return (() => {
  const __s63 = xs;
  if (((Array.isArray(__s63) && __s63.length === 0))) {  return stdlib__SetNil(); }
  if (((Array.isArray(__s63) && __s63.length >= 1))) { const h = __s63[0]; const t = __s63.slice(1);  return stdlib__add(stdlib__toSet(t), h); }
  return null;
})();
  }
  function stdlib__toMap(xs) {
    return (() => {
  const __s64 = xs;
  if (((Array.isArray(__s64) && __s64.length === 0))) {  return stdlib__MapNil(); }
  if (((Array.isArray(__s64) && __s64.length >= 1))) { const pr = __s64[0]; const t = __s64.slice(1);  return (() => {
  const __s65 = pr;
  if (((Array.isArray(__s65) && __s65.length === 2))) { const k = __s65[0]; const v = __s65[1];  return stdlib__put(stdlib__toMap(t), k, v); }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__sqrt(_n) {
    return (() => { throw new Error('panic: unreachable') })();
  }
  function stdlib__pred(n) {
    return (__eq(n, 0) ? (() => {
  return 0;
})() : (() => {
  return (n - 1);
})());
  }
  function stdlib__isZero(n) {
    return __eq(n, 0);
  }
  function stdlib__isEven(n) {
    return ((n <= 1) ? (() => {
  return __eq(n, 0);
})() : (() => {
  return stdlib__isEven((n - 2));
})());
  }
  function stdlib__isOdd(n) {
    return (!stdlib__isEven(n));
  }
  function stdlib__natPow(a, b) {
    return (__eq(b, 0) ? (() => {
  return 1;
})() : (() => {
  return (a * stdlib__natPow(a, (b - 1)));
})());
  }
  function stdlib__factorial(n) {
    return ((n <= 1) ? (() => {
  return 1;
})() : (() => {
  return (n * stdlib__factorial((n - 1)));
})());
  }
  const dict_stdlib_Ord_Rat = {
    lt: (d, __self, other) => (__self < other),
    le: (d, __self, other) => (__self <= other),
    gt: (d, __self, other) => (__self > other),
    ge: (d, __self, other) => (__self >= other)
  };
  const dict_stdlib_Ord_Str = {
    lt: (d, __self, other) => ((__self < other ? -1 : (__self > other ? 1 : 0)) < 0),
    le: (d, __self, other) => ((__self < other ? -1 : (__self > other ? 1 : 0)) <= 0),
    gt: (d, __self, other) => ((__self < other ? -1 : (__self > other ? 1 : 0)) > 0),
    ge: (d, __self, other) => ((__self < other ? -1 : (__self > other ? 1 : 0)) >= 0)
  };
  main();
})();
