"use strict";
(() => {
  const None = () => ({ tag: "None" });
  const Some = (a0) => ({ tag: "Some", 0: a0 });
  const __dshtoStr = (v) => (v && typeof v === "object" && Object.keys(v).length === 1 && "tag" in v) ? String(v.tag) : String(v);
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
  const __range = (lo, hi) => ({ lo, hi,
    contains: (x) => lo <= x && x <= hi,
    length: () => Math.max(0, hi - lo + 1),
    toList: () => Array.from({ length: Math.max(0, hi - lo + 1) }, (_, i) => lo + i),
    map: (f) => Array.from({ length: Math.max(0, hi - lo + 1) }, (_, i) => f(lo + i)),
    fold: (init, f) => { let acc = init; for (let i = lo; i <= hi; i++) acc = f([i, acc]); return acc; },
  });
  const Student = (name, score) => ({ tag: "Student", name: name, score: score });
  function Grade__A() { return { tag: "A" }; }
  const A = Grade__A;
  function Grade__B() { return { tag: "B" }; }
  const B = Grade__B;
  function Grade__C() { return { tag: "C" }; }
  const C = Grade__C;
  function Grade__D() { return { tag: "D" }; }
  const D = Grade__D;
  const dict_Gradable_Student = {
    grade: (d, __self) => (() => {
  if (((90 <= __self.score && __self.score <= 100))) {  return Grade__A(); }
  if (((80 <= __self.score && __self.score <= 89))) {  return Grade__B(); }
  if (((60 <= __self.score && __self.score <= 79))) {  return Grade__C(); }
   return Grade__D();
})()
  };
  async function main() {
    return stdlib__forEach$List(stdlib__map$Set(stdlib__SetCons(Student("Sun XiaoChuan", 54), stdlib__SetCons(Student("Fluvoxamine", 75), stdlib__SetCons(Student("Thirty-Seven", 100), stdlib__SetCons(Student("Sugared Functor", 91), stdlib__SetNil())))), (_) => [_.name, _.score, dict_Gradable_Student.grade(dict_Gradable_Student, _)]), (__dt0) => { const name = __dt0[0]; const score = __dt0[1]; const grade = __dt0[2];  return console.log(((((__dshtoStr(name) + ":") + __dshtoStr(score)) + ":") + __dshtoStr(grade))); });
  }
  function stdlib__Set__SetNil() { return { tag: "SetNil" }; }
  const stdlib__SetNil = stdlib__Set__SetNil;
  function stdlib__Set__SetCons(a0, a1) { return { tag: "SetCons", 0: a0, 1: a1 }; }
  const stdlib__SetCons = stdlib__Set__SetCons;
  function stdlib__Map__MapNil() { return { tag: "MapNil" }; }
  const stdlib__MapNil = stdlib__Map__MapNil;
  function stdlib__Map__MapCons(a0, a1, a2) { return { tag: "MapCons", 0: a0, 1: a1, 2: a2 }; }
  const stdlib__MapCons = stdlib__Map__MapCons;
  function stdlib__setEmpty() {
    return stdlib__SetNil();
  }
  function stdlib__mapEmpty() {
    return stdlib__MapNil();
  }
  function stdlib__size$Set(xs) {
    return (() => {
  const __s1 = xs;
  if ((__s1.tag === "SetNil")) {  return 0; }
  if ((__s1.tag === "SetCons")) { const t = __s1[1];  return (1 + stdlib__size$Set(t)); }
  return null;
})();
  }
  function stdlib__isEmpty$Set(xs) {
    return (() => {
  const __s2 = xs;
  if ((__s2.tag === "SetNil")) {  return true; }
  if ((__s2.tag === "SetCons")) {  return false; }
  return null;
})();
  }
  function stdlib__contains$Set(xs, x) {
    return (() => {
  const __s3 = xs;
  if ((__s3.tag === "SetNil")) {  return false; }
  if ((__s3.tag === "SetCons")) { const h = __s3[0]; const t = __s3[1];  return (__eq(h, x) ? (() => {
  return true;
})() : (() => {
  return stdlib__contains$Set(t, x);
})()); }
  return null;
})();
  }
  function stdlib__add(xs, x) {
    return (stdlib__contains$Set(xs, x) ? (() => {
  return xs;
})() : (() => {
  return stdlib__SetCons(x, xs);
})());
  }
  function stdlib__remove$Set(xs, x) {
    return (() => {
  const __s4 = xs;
  if ((__s4.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s4.tag === "SetCons")) { const h = __s4[0]; const t = __s4[1];  return (__eq(h, x) ? (() => {
  return stdlib__remove$Set(t, x);
})() : (() => {
  return stdlib__SetCons(h, stdlib__remove$Set(t, x));
})()); }
  return null;
})();
  }
  function stdlib__union(xs, ys) {
    return (() => {
  const __s5 = xs;
  if ((__s5.tag === "SetNil")) {  return ys; }
  if ((__s5.tag === "SetCons")) { const h = __s5[0]; const t = __s5[1];  return (stdlib__contains$Set(ys, h) ? (() => {
  return stdlib__union(t, ys);
})() : (() => {
  return stdlib__SetCons(h, stdlib__union(t, ys));
})()); }
  return null;
})();
  }
  function stdlib__intersect(xs, ys) {
    return (() => {
  const __s6 = xs;
  if ((__s6.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s6.tag === "SetCons")) { const h = __s6[0]; const t = __s6[1];  return (stdlib__contains$Set(ys, h) ? (() => {
  return stdlib__SetCons(h, stdlib__intersect(t, ys));
})() : (() => {
  return stdlib__intersect(t, ys);
})()); }
  return null;
})();
  }
  function stdlib__toList(xs) {
    return (() => {
  const __s7 = xs;
  if ((__s7.tag === "SetNil")) {  return []; }
  if ((__s7.tag === "SetCons")) { const h = __s7[0]; const t = __s7[1];  return [h, ...(stdlib__toList(t))]; }
  return null;
})();
  }
  function stdlib__get(m, k) {
    return (() => {
  const __s8 = m;
  if ((__s8.tag === "MapNil")) {  return None(); }
  if ((__s8.tag === "MapCons")) { const k0 = __s8[0]; const v0 = __s8[1]; const t = __s8[2];  return (__eq(k0, k) ? (() => {
  return Some(v0);
})() : (() => {
  return stdlib__get(t, k);
})()); }
  return null;
})();
  }
  function stdlib__put(m, k, v) {
    return stdlib__MapCons(k, v, m);
  }
  function stdlib__size$Map(m) {
    return (() => {
  const __s9 = m;
  if ((__s9.tag === "MapNil")) {  return 0; }
  if ((__s9.tag === "MapCons")) { const t = __s9[2];  return (1 + stdlib__size$Map(t)); }
  return null;
})();
  }
  function stdlib__containsKey(m, k) {
    return (() => {
  const __s10 = m;
  if ((__s10.tag === "MapNil")) {  return false; }
  if ((__s10.tag === "MapCons")) { const k0 = __s10[0]; const t = __s10[2];  return (__eq(k0, k) ? (() => {
  return true;
})() : (() => {
  return stdlib__containsKey(t, k);
})()); }
  return null;
})();
  }
  function stdlib__remove$Map(m, k) {
    return (() => {
  const __s11 = m;
  if ((__s11.tag === "MapNil")) {  return stdlib__MapNil(); }
  if ((__s11.tag === "MapCons")) { const k0 = __s11[0]; const v0 = __s11[1]; const t = __s11[2];  return (__eq(k0, k) ? (() => {
  return t;
})() : (() => {
  return stdlib__MapCons(k0, v0, stdlib__remove$Map(t, k));
})()); }
  return null;
})();
  }
  function stdlib__keys(m) {
    return (() => {
  const __s12 = m;
  if ((__s12.tag === "MapNil")) {  return []; }
  if ((__s12.tag === "MapCons")) { const k0 = __s12[0]; const t = __s12[2];  return [k0, ...(stdlib__keys(t))]; }
  return null;
})();
  }
  function stdlib__values(m) {
    return (() => {
  const __s13 = m;
  if ((__s13.tag === "MapNil")) {  return []; }
  if ((__s13.tag === "MapCons")) { const v0 = __s13[1]; const t = __s13[2];  return [v0, ...(stdlib__values(t))]; }
  return null;
})();
  }
  function stdlib__toSet(xs) {
    return (() => {
  const __s14 = xs;
  if (((Array.isArray(__s14) && __s14.length === 0))) {  return stdlib__SetNil(); }
  if (((Array.isArray(__s14) && __s14.length >= 1))) { const h = __s14[0]; const t = __s14.slice(1);  return stdlib__add(stdlib__toSet(t), h); }
  return null;
})();
  }
  function stdlib__toMap(xs) {
    return (() => {
  const __s15 = xs;
  if (((Array.isArray(__s15) && __s15.length === 0))) {  return stdlib__MapNil(); }
  if (((Array.isArray(__s15) && __s15.length >= 1))) { const pr = __s15[0]; const t = __s15.slice(1);  return (() => {
  const __s16 = pr;
  if (((Array.isArray(__s16) && __s16.length === 2))) { const k = __s16[0]; const v = __s16[1];  return stdlib__put(stdlib__toMap(t), k, v); }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__setOf(_items) {
    return stdlib__SetNil();
  }
  function stdlib__map$Set(xs, f) {
    return (() => {
  const __s17 = xs;
  if ((__s17.tag === "SetNil")) {  return []; }
  if ((__s17.tag === "SetCons")) { const h = __s17[0]; const t = __s17[1];  return [f(h), ...(stdlib__map$Set(t, f))]; }
  return null;
})();
  }
  function stdlib__forEach$Set(xs, f) {
    (() => {
  const __s18 = xs;
  if ((__s18.tag === "SetNil")) {  return null; }
  if ((__s18.tag === "SetCons")) { const h = __s18[0]; const t = __s18[1];  return (() => {
  f(h);
  return stdlib__forEach$Set(t, f);
})(); }
  return null;
})();
    return null;
  }
  function stdlib__listLength(xs) {
    return (() => {
  const __s19 = xs;
  if (((Array.isArray(__s19) && __s19.length === 0))) {  return 0; }
  if (((Array.isArray(__s19) && __s19.length >= 1))) { const t = __s19.slice(1);  return (1 + (t).length); }
  return null;
})();
  }
  function stdlib__size$List(xs) {
    return (() => {
  const __s20 = xs;
  if (((Array.isArray(__s20) && __s20.length === 0))) {  return 0; }
  if (((Array.isArray(__s20) && __s20.length >= 1))) { const t = __s20.slice(1);  return (1 + (t).length); }
  return null;
})();
  }
  function stdlib__isEmpty$List(xs) {
    return (() => {
  const __s21 = xs;
  if (((Array.isArray(__s21) && __s21.length === 0))) {  return true; }
  if (((Array.isArray(__s21) && __s21.length >= 1))) {  return false; }
  return null;
})();
  }
  function stdlib__head(xs) {
    return (() => {
  const __s22 = xs;
  if (((Array.isArray(__s22) && __s22.length === 0))) {  return None(); }
  if (((Array.isArray(__s22) && __s22.length >= 1))) { const h = __s22[0];  return Some(h); }
  return null;
})();
  }
  function stdlib__last(xs) {
    return (() => {
  const __s23 = xs;
  if (((Array.isArray(__s23) && __s23.length === 0))) {  return None(); }
  if (((Array.isArray(__s23) && __s23.length >= 1)) && ((Array.isArray(__s23.slice(1)) && __s23.slice(1).length === 0))) { const h = __s23[0];  return Some(h); }
  if (((Array.isArray(__s23) && __s23.length >= 1))) { const t = __s23.slice(1);  return (() => { const a = t; return a.length ? Some(a[a.length - 1]) : None(); })(); }
  return null;
})();
  }
  function stdlib__contains$List(xs, x) {
    return (() => {
  const __s24 = xs;
  if (((Array.isArray(__s24) && __s24.length === 0))) {  return false; }
  if (((Array.isArray(__s24) && __s24.length >= 1))) { const h = __s24[0]; const t = __s24.slice(1);  return (__eq(h, x) ? (() => {
  return true;
})() : (() => {
  return (t).includes(x);
})()); }
  return null;
})();
  }
  function stdlib__elementAt(xs, i) {
    return (() => {
  const __s25 = xs;
  if (((Array.isArray(__s25) && __s25.length === 0))) {  return None(); }
  if (((Array.isArray(__s25) && __s25.length >= 1))) { const h = __s25[0]; const t = __s25.slice(1);  return (__eq(i, 0) ? (() => {
  return Some(h);
})() : (() => {
  return (() => { const a = t; const i = (i - 1); return i >= 0 && i < a.length ? Some(a[i]) : None(); })();
})()); }
  return null;
})();
  }
  function stdlib__count(xs, f) {
    return (() => {
  const __s26 = xs;
  if (((Array.isArray(__s26) && __s26.length === 0))) {  return 0; }
  if (((Array.isArray(__s26) && __s26.length >= 1))) { const h = __s26[0]; const t = __s26.slice(1);  return (f(h) ? (() => {
  return (1 + (t).filter(f).length);
})() : (() => {
  return (t).filter(f).length;
})()); }
  return null;
})();
  }
  function stdlib__map$List(xs, f) {
    return (() => {
  const __s27 = xs;
  if (((Array.isArray(__s27) && __s27.length === 0))) {  return []; }
  if (((Array.isArray(__s27) && __s27.length >= 1))) { const h = __s27[0]; const t = __s27.slice(1);  return [f(h), ...((t).map(f))]; }
  return null;
})();
  }
  function stdlib__flatMap$List(xs, f) {
    return (() => {
  const __s28 = xs;
  if (((Array.isArray(__s28) && __s28.length === 0))) {  return []; }
  if (((Array.isArray(__s28) && __s28.length >= 1))) { const h = __s28[0]; const t = __s28.slice(1);  return (f(h)).concat((t).flatMap(f)); }
  return null;
})();
  }
  function stdlib__filter$List(xs, f) {
    return (() => {
  const __s29 = xs;
  if (((Array.isArray(__s29) && __s29.length === 0))) {  return []; }
  if (((Array.isArray(__s29) && __s29.length >= 1))) { const h = __s29[0]; const t = __s29.slice(1);  return (f(h) ? (() => {
  return [h, ...((t).filter(f))];
})() : (() => {
  return (t).filter(f);
})()); }
  return null;
})();
  }
  function stdlib__fold(xs, init, f) {
    return (() => {
  const __s30 = xs;
  if (((Array.isArray(__s30) && __s30.length === 0))) {  return init; }
  if (((Array.isArray(__s30) && __s30.length >= 1))) { const h = __s30[0]; const t = __s30.slice(1);  return (t).reduce((acc, x) => (f)(x, acc), f(h, init)); }
  return null;
})();
  }
  function stdlib__forEach$List(xs, f) {
    (() => {
  const __s31 = xs;
  if (((Array.isArray(__s31) && __s31.length === 0))) {  return null; }
  if (((Array.isArray(__s31) && __s31.length >= 1))) { const h = __s31[0]; const t = __s31.slice(1);  return (() => {
  f(h);
  return (t).forEach(x => (f)(x));
})(); }
  return null;
})();
    return null;
  }
  function stdlib__append(xs, ys) {
    return (() => {
  const __s32 = xs;
  if (((Array.isArray(__s32) && __s32.length === 0))) {  return ys; }
  if (((Array.isArray(__s32) && __s32.length >= 1))) { const h = __s32[0]; const t = __s32.slice(1);  return [h, ...((t).concat(ys))]; }
  return null;
})();
  }
  function stdlib__reverse(xs) {
    return (() => {
  const __s33 = xs;
  if (((Array.isArray(__s33) && __s33.length === 0))) {  return []; }
  if (((Array.isArray(__s33) && __s33.length >= 1))) { const h = __s33[0]; const t = __s33.slice(1);  return ((t).slice().reverse()).concat([h, ...([])]); }
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
  function stdlib__take(xs, n) {
    return (() => {
  const __s34 = xs;
  if (((Array.isArray(__s34) && __s34.length === 0))) {  return []; }
  if (((Array.isArray(__s34) && __s34.length >= 1))) { const h = __s34[0]; const t = __s34.slice(1);  return (__eq(n, 0) ? (() => {
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
  const __s35 = xs;
  if (((Array.isArray(__s35) && __s35.length === 0))) {  return []; }
  if (((Array.isArray(__s35) && __s35.length >= 1))) { const h = __s35[0]; const t = __s35.slice(1);  return (__eq(n, 0) ? (() => {
  return xs;
})() : (() => {
  const r = (t).slice((n - 1));
  return r;
})()); }
  return null;
})();
  }
  function stdlib__zip(xs, ys) {
    return (() => {
  const __s36 = xs;
  if (((Array.isArray(__s36) && __s36.length === 0))) {  return []; }
  if (((Array.isArray(__s36) && __s36.length >= 1))) { const h = __s36[0]; const t = __s36.slice(1);  return (() => {
  const __s37 = ys;
  if (((Array.isArray(__s37) && __s37.length === 0))) {  return []; }
  if (((Array.isArray(__s37) && __s37.length >= 1))) { const h2 = __s37[0]; const t2 = __s37.slice(1);  return [[h, h2], ...(stdlib__zip(t, t2))]; }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__joinToString(xs, sep) {
    return (xs).reduce((acc, x) => ((x, acc) => (__eq(acc, "") ? (() => {
  return __dshtoStr(x);
})() : (() => {
  return (acc + (sep + __dshtoStr(x)));
})()))(x, acc), "");
  }
  function stdlib__sum(xs) {
    return (xs).reduce((acc, x) => ((x, acc) => (acc + x))(x, acc), 0);
  }
  function stdlib__maxNat(xs) {
    return (() => {
  const __s38 = xs;
  if (((Array.isArray(__s38) && __s38.length === 0))) {  return None(); }
  if (((Array.isArray(__s38) && __s38.length >= 1))) { const h = __s38[0]; const t = __s38.slice(1);  return (() => {
  const __s39 = t;
  if (((Array.isArray(__s39) && __s39.length === 0))) {  return Some(h); }
  if (((Array.isArray(__s39) && __s39.length >= 1))) {  return (() => {
  const m = stdlib__maxNat(t);
  return (() => {
  const __s40 = m;
  if ((__s40.tag === "Some")) { const v = __s40[0];  return ((h > v) ? (() => {
  return Some(h);
})() : (() => {
  return Some(v);
})()); }
  if ((__s40.tag === "None")) {  return Some(h); }
  return null;
})();
})(); }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__minNat(xs) {
    return (() => {
  const __s41 = xs;
  if (((Array.isArray(__s41) && __s41.length === 0))) {  return None(); }
  if (((Array.isArray(__s41) && __s41.length >= 1))) { const h = __s41[0]; const t = __s41.slice(1);  return (() => {
  const __s42 = t;
  if (((Array.isArray(__s42) && __s42.length === 0))) {  return Some(h); }
  if (((Array.isArray(__s42) && __s42.length >= 1))) {  return (() => {
  const m = stdlib__minNat(t);
  return (() => {
  const __s43 = m;
  if ((__s43.tag === "Some")) { const v = __s43[0];  return ((h < v) ? (() => {
  return Some(h);
})() : (() => {
  return Some(v);
})()); }
  if ((__s43.tag === "None")) {  return Some(h); }
  return null;
})();
})(); }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__distinct(xs) {
    return (() => {
  const __s44 = xs;
  if (((Array.isArray(__s44) && __s44.length === 0))) {  return []; }
  if (((Array.isArray(__s44) && __s44.length >= 1))) { const h = __s44[0]; const t = __s44.slice(1);  return ((t).includes(h) ? (() => {
  return stdlib__distinct(t);
})() : (() => {
  return [h, ...(stdlib__distinct(t))];
})()); }
  return null;
})();
  }
  function stdlib__any(xs, f) {
    return (() => {
  const __s45 = xs;
  if (((Array.isArray(__s45) && __s45.length === 0))) {  return false; }
  if (((Array.isArray(__s45) && __s45.length >= 1))) { const h = __s45[0]; const t = __s45.slice(1);  return (f(h) ? (() => {
  return true;
})() : (() => {
  return (t).some(f);
})()); }
  return null;
})();
  }
  function stdlib__all(xs, f) {
    return (() => {
  const __s46 = xs;
  if (((Array.isArray(__s46) && __s46.length === 0))) {  return true; }
  if (((Array.isArray(__s46) && __s46.length >= 1))) { const h = __s46[0]; const t = __s46.slice(1);  return (f(h) ? (() => {
  return (t).every(f);
})() : (() => {
  return false;
})()); }
  return null;
})();
  }
  function stdlib__indexOf(xs, x) {
    return (() => {
  const __s47 = xs;
  if (((Array.isArray(__s47) && __s47.length === 0))) {  return None(); }
  if (((Array.isArray(__s47) && __s47.length >= 1))) { const h = __s47[0]; const t = __s47.slice(1);  return (__eq(h, x) ? (() => {
  return Some(0);
})() : (() => {
  return (() => {
  const __s48 = (() => { const i = (t).indexOf(x); return i === -1 ? None() : Some(i); })();
  if ((__s48.tag === "Some")) { const n = __s48[0];  return Some((n + 1)); }
  if ((__s48.tag === "None")) {  return None(); }
  return null;
})();
})()); }
  return null;
})();
  }
  function stdlib__slice(xs, a, b) {
    return (() => {
  const __s49 = xs;
  if (((Array.isArray(__s49) && __s49.length === 0))) {  return []; }
  if (((Array.isArray(__s49) && __s49.length >= 1))) { const h = __s49[0]; const t = __s49.slice(1);  return ((a > 0) ? (() => {
  const r = (t).slice((a - 1), (b - 1));
  return r;
})() : (() => {
  return (__eq(b, 0) ? (() => {
  return [];
})() : (() => {
  const r2 = (t).slice(0, (b - 1));
  return [h, ...(r2)];
})());
})()); }
  return null;
})();
  }
  function stdlib__sortBy(xs, f) {
    return (() => {
  const __s50 = xs;
  if (((Array.isArray(__s50) && __s50.length === 0))) {  return []; }
  if (((Array.isArray(__s50) && __s50.length >= 1))) { const h = __s50[0]; const t = __s50.slice(1);  return stdlib__insertSort(h, (t).slice().sort((a, b) => (f)(a, b) ? -1 : 1), f); }
  return null;
})();
  }
  function stdlib__insertSort(x, xs, f) {
    return (() => {
  const __s51 = xs;
  if (((Array.isArray(__s51) && __s51.length === 0))) {  return [x, ...([])]; }
  if (((Array.isArray(__s51) && __s51.length >= 1))) { const h = __s51[0]; const t = __s51.slice(1);  return (f(x, h) ? (() => {
  return [x, ...([h, ...(t)])];
})() : (() => {
  return [h, ...(stdlib__insertSort(x, t, f))];
})()); }
  return null;
})();
  }
  function stdlib__Result__Ok(a0) { return { tag: "Ok", 0: a0 }; }
  const stdlib__Ok = stdlib__Result__Ok;
  function stdlib__Result__Err(a0) { return { tag: "Err", 0: a0 }; }
  const stdlib__Err = stdlib__Result__Err;
  function stdlib__isOk(r) {
    return (() => {
  const __s52 = r;
  if ((__s52.tag === "Ok")) {  return true; }
  if ((__s52.tag === "Err")) {  return false; }
  return null;
})();
  }
  function stdlib__isErr(r) {
    return (() => {
  const __s53 = r;
  if ((__s53.tag === "Ok")) {  return false; }
  if ((__s53.tag === "Err")) {  return true; }
  return null;
})();
  }
  function stdlib__unwrapOr(r, d) {
    return (() => {
  const __s54 = r;
  if ((__s54.tag === "Ok")) { const x = __s54[0];  return x; }
  if ((__s54.tag === "Err")) {  return d; }
  return null;
})();
  }
  function stdlib__map$Result(r, f) {
    return (() => {
  const __s55 = r;
  if ((__s55.tag === "Ok")) { const x = __s55[0];  return stdlib__Ok(f(x)); }
  if ((__s55.tag === "Err")) { const e = __s55[0];  return stdlib__Err(e); }
  return null;
})();
  }
  function stdlib__flatMap$Result(r, f) {
    return (() => {
  const __s56 = r;
  if ((__s56.tag === "Ok")) { const x = __s56[0];  return f(x); }
  if ((__s56.tag === "Err")) { const e = __s56[0];  return stdlib__Err(e); }
  return null;
})();
  }
  function stdlib__mapErr(r, f) {
    return (() => {
  const __s57 = r;
  if ((__s57.tag === "Ok")) { const x = __s57[0];  return stdlib__Ok(x); }
  if ((__s57.tag === "Err")) { const e = __s57[0];  return stdlib__Err(f(e)); }
  return null;
})();
  }
  function stdlib__mapResult(r, f) {
    return (() => {
  const __s58 = r;
  if ((__s58.tag === "Ok")) { const x = __s58[0];  return stdlib__Ok(f(x)); }
  if ((__s58.tag === "Err")) { const e = __s58[0];  return stdlib__Err(e); }
  return null;
})();
  }
  function stdlib__map$Optional(o, f) {
    return (() => {
  const __s59 = o;
  if ((__s59.tag === "Some")) { const x = __s59[0];  return Some(f(x)); }
  if ((__s59.tag === "None")) {  return None(); }
  return null;
})();
  }
  function stdlib__flatMap$Optional(o, f) {
    return (() => {
  const __s60 = o;
  if ((__s60.tag === "Some")) { const x = __s60[0];  return f(x); }
  if ((__s60.tag === "None")) {  return None(); }
  return null;
})();
  }
  function stdlib__filter$Optional(o, f) {
    return (() => {
  const __s61 = o;
  if ((__s61.tag === "Some")) { const x = __s61[0];  return (f(x) ? (() => {
  return o;
})() : (() => {
  return None();
})()); }
  if ((__s61.tag === "None")) {  return None(); }
  return null;
})();
  }
  function stdlib__isSome(o) {
    return (() => {
  const __s62 = o;
  if ((__s62.tag === "Some")) {  return true; }
  if ((__s62.tag === "None")) {  return false; }
  return null;
})();
  }
  function stdlib__isNone(o) {
    return (() => {
  const __s63 = o;
  if ((__s63.tag === "Some")) {  return false; }
  if ((__s63.tag === "None")) {  return true; }
  return null;
})();
  }
  function stdlib__getOrElse(o, d) {
    return (() => {
  const __s64 = o;
  if ((__s64.tag === "Some")) { const x = __s64[0];  return x; }
  if ((__s64.tag === "None")) {  return d; }
  return null;
})();
  }
  function stdlib__orElse(o, d) {
    return (() => {
  const __s65 = o;
  if ((__s65.tag === "Some")) {  return o; }
  if ((__s65.tag === "None")) {  return d; }
  return null;
})();
  }
  function stdlib__toResult(o, e) {
    return (() => {
  const __s66 = o;
  if ((__s66.tag === "Some")) { const x = __s66[0];  return stdlib__Ok(x); }
  if ((__s66.tag === "None")) {  return stdlib__Err(e); }
  return null;
})();
  }
  const dict_stdlib_Collection_List = {
    contains: (d, __self, element) => (() => {
  const __s67 = __self;
  if (((Array.isArray(__s67) && __s67.length === 0))) {  return false; }
  if (((Array.isArray(__s67) && __s67.length >= 1))) { const h = __s67[0]; const t = __s67.slice(1);  return (__eq(h, element) ? (() => {
  return true;
})() : (() => {
  return dict_stdlib_Collection_List.contains(dict_stdlib_Collection_List, t, element);
})()); }
  return null;
})(),
    size: (d, __self) => (() => {
  const __s68 = __self;
  if (((Array.isArray(__s68) && __s68.length === 0))) {  return 0; }
  if (((Array.isArray(__s68) && __s68.length >= 1))) { const t = __s68.slice(1);  return (1 + dict_stdlib_Collection_List.size(dict_stdlib_Collection_List, t)); }
  return null;
})()
  };
  const dict_stdlib_Collection_Set = {
    contains: (d, __self, element) => (() => {
  const __s69 = __self;
  if ((__s69.tag === "SetNil")) {  return false; }
  if ((__s69.tag === "SetCons")) { const h = __s69[0]; const t = __s69[1];  return (__eq(h, element) ? (() => {
  return true;
})() : (() => {
  return dict_stdlib_Collection_Set.contains(dict_stdlib_Collection_Set, t, element);
})()); }
  return null;
})(),
    size: (d, __self) => (() => {
  const __s70 = __self;
  if ((__s70.tag === "SetNil")) {  return 0; }
  if ((__s70.tag === "SetCons")) { const t = __s70[1];  return (1 + dict_stdlib_Collection_Set.size(dict_stdlib_Collection_Set, t)); }
  return null;
})()
  };
  const dict_stdlib_Collection_Map = {
    contains: (d, __self, element) => (() => {
  const __s71 = __self;
  if ((__s71.tag === "MapNil")) {  return false; }
  if ((__s71.tag === "MapCons")) { const k0 = __s71[0]; const v0 = __s71[1]; const t = __s71[2];  return (__eq(k0, element) ? (() => {
  return true;
})() : (() => {
  return dict_stdlib_Collection_Map.contains(dict_stdlib_Collection_Map, t, element);
})()); }
  return null;
})(),
    size: (d, __self) => (() => {
  const __s72 = __self;
  if ((__s72.tag === "MapNil")) {  return 0; }
  if ((__s72.tag === "MapCons")) { const t = __s72[2];  return (1 + dict_stdlib_Collection_Map.size(dict_stdlib_Collection_Map, t)); }
  return null;
})()
  };
  const dict_stdlib_Collection_Optional = {
    contains: (d, __self, element) => (() => {
  const __s73 = __self;
  if ((__s73.tag === "Some")) { const x = __s73[0];  return __eq(x, element); }
  if ((__s73.tag === "None")) {  return false; }
  return null;
})(),
    size: (d, __self) => (() => {
  const __s74 = __self;
  if ((__s74.tag === "Some")) {  return 1; }
  if ((__s74.tag === "None")) {  return 0; }
  return null;
})()
  };
  const dict_stdlib_Collection_Result = {
    contains: (d, __self, element) => (() => {
  const __s75 = __self;
  if ((__s75.tag === "Ok")) { const x = __s75[0];  return __eq(x, element); }
  if ((__s75.tag === "Err")) {  return false; }
  return null;
})(),
    size: (d, __self) => (() => {
  const __s76 = __self;
  if ((__s76.tag === "Ok")) {  return 1; }
  if ((__s76.tag === "Err")) {  return 0; }
  return null;
})()
  };
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
  main();
})();
