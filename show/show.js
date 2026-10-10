"use strict";
(() => {
  const None = () => ({ tag: "None" });
  const Some = (a0) => ({ tag: "Some", 0: a0 });
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
    return console.log("Hello, World!");
  }
  const stdlib__SetNil = () => ({ tag: "SetNil" });
  const stdlib__SetCons = (a0, a1) => ({ tag: "SetCons", 0: a0, 1: a1 });
  const stdlib__MapNil = () => ({ tag: "MapNil" });
  const stdlib__MapCons = (a0, a1, a2) => ({ tag: "MapCons", 0: a0, 1: a1, 2: a2 });
  function stdlib__setEmpty() {
    return stdlib__SetNil();
  }
  function stdlib__mapEmpty() {
    return stdlib__MapNil();
  }
  function stdlib__size$Set(xs) {
    return (() => {
  const __s0 = xs;
  if ((__s0.tag === "SetNil")) {  return 0; }
  if ((__s0.tag === "SetCons")) { const t = __s0[1];  return (1 + stdlib__size$Set(t)); }
  return null;
})();
  }
  function stdlib__isEmpty$Set(xs) {
    return (() => {
  const __s1 = xs;
  if ((__s1.tag === "SetNil")) {  return true; }
  if ((__s1.tag === "SetCons")) {  return false; }
  return null;
})();
  }
  function stdlib__contains$Set(xs, x) {
    return (() => {
  const __s2 = xs;
  if ((__s2.tag === "SetNil")) {  return false; }
  if ((__s2.tag === "SetCons")) { const h = __s2[0]; const t = __s2[1];  return (__eq(h, x) ? (() => {
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
  const __s3 = xs;
  if ((__s3.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s3.tag === "SetCons")) { const h = __s3[0]; const t = __s3[1];  return (__eq(h, x) ? (() => {
  return stdlib__remove$Set(t, x);
})() : (() => {
  return stdlib__SetCons(h, stdlib__remove$Set(t, x));
})()); }
  return null;
})();
  }
  function stdlib__union(xs, ys) {
    return (() => {
  const __s4 = xs;
  if ((__s4.tag === "SetNil")) {  return ys; }
  if ((__s4.tag === "SetCons")) { const h = __s4[0]; const t = __s4[1];  return (stdlib__contains$Set(ys, h) ? (() => {
  return stdlib__union(t, ys);
})() : (() => {
  return stdlib__SetCons(h, stdlib__union(t, ys));
})()); }
  return null;
})();
  }
  function stdlib__intersect(xs, ys) {
    return (() => {
  const __s5 = xs;
  if ((__s5.tag === "SetNil")) {  return stdlib__SetNil(); }
  if ((__s5.tag === "SetCons")) { const h = __s5[0]; const t = __s5[1];  return (stdlib__contains$Set(ys, h) ? (() => {
  return stdlib__SetCons(h, stdlib__intersect(t, ys));
})() : (() => {
  return stdlib__intersect(t, ys);
})()); }
  return null;
})();
  }
  function stdlib__toList(xs) {
    return (() => {
  const __s6 = xs;
  if ((__s6.tag === "SetNil")) {  return []; }
  if ((__s6.tag === "SetCons")) { const h = __s6[0]; const t = __s6[1];  return [h, ...(stdlib__toList(t))]; }
  return null;
})();
  }
  function stdlib__get(m, k) {
    return (() => {
  const __s7 = m;
  if ((__s7.tag === "MapNil")) {  return None(); }
  if ((__s7.tag === "MapCons")) { const k0 = __s7[0]; const v0 = __s7[1]; const t = __s7[2];  return (__eq(k0, k) ? (() => {
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
  const __s8 = m;
  if ((__s8.tag === "MapNil")) {  return 0; }
  if ((__s8.tag === "MapCons")) { const t = __s8[2];  return (1 + stdlib__size$Map(t)); }
  return null;
})();
  }
  function stdlib__containsKey(m, k) {
    return (() => {
  const __s9 = m;
  if ((__s9.tag === "MapNil")) {  return false; }
  if ((__s9.tag === "MapCons")) { const k0 = __s9[0]; const t = __s9[2];  return (__eq(k0, k) ? (() => {
  return true;
})() : (() => {
  return stdlib__containsKey(t, k);
})()); }
  return null;
})();
  }
  function stdlib__remove$Map(m, k) {
    return (() => {
  const __s10 = m;
  if ((__s10.tag === "MapNil")) {  return stdlib__MapNil(); }
  if ((__s10.tag === "MapCons")) { const k0 = __s10[0]; const v0 = __s10[1]; const t = __s10[2];  return (__eq(k0, k) ? (() => {
  return t;
})() : (() => {
  return stdlib__MapCons(k0, v0, stdlib__remove$Map(t, k));
})()); }
  return null;
})();
  }
  function stdlib__keys(m) {
    return (() => {
  const __s11 = m;
  if ((__s11.tag === "MapNil")) {  return []; }
  if ((__s11.tag === "MapCons")) { const k0 = __s11[0]; const t = __s11[2];  return [k0, ...(stdlib__keys(t))]; }
  return null;
})();
  }
  function stdlib__values(m) {
    return (() => {
  const __s12 = m;
  if ((__s12.tag === "MapNil")) {  return []; }
  if ((__s12.tag === "MapCons")) { const v0 = __s12[1]; const t = __s12[2];  return [v0, ...(stdlib__values(t))]; }
  return null;
})();
  }
  function stdlib__toSet(xs) {
    return (() => {
  const __s13 = xs;
  if (((Array.isArray(__s13) && __s13.length === 0))) {  return stdlib__SetNil(); }
  if (((Array.isArray(__s13) && __s13.length >= 1))) { const h = __s13[0]; const t = __s13.slice(1);  return stdlib__add(stdlib__toSet(t), h); }
  return null;
})();
  }
  function stdlib__toMap(xs) {
    return (() => {
  const __s14 = xs;
  if (((Array.isArray(__s14) && __s14.length === 0))) {  return stdlib__MapNil(); }
  if (((Array.isArray(__s14) && __s14.length >= 1))) { const pr = __s14[0]; const t = __s14.slice(1);  return (() => {
  const __s15 = pr;
  if (((Array.isArray(__s15) && __s15.length === 2))) { const k = __s15[0]; const v = __s15[1];  return stdlib__put(stdlib__toMap(t), k, v); }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__listLength(xs) {
    return (() => {
  const __s16 = xs;
  if (((Array.isArray(__s16) && __s16.length === 0))) {  return 0; }
  if (((Array.isArray(__s16) && __s16.length >= 1))) { const t = __s16.slice(1);  return (1 + (t).length); }
  return null;
})();
  }
  function stdlib__size$List(xs) {
    return (() => {
  const __s17 = xs;
  if (((Array.isArray(__s17) && __s17.length === 0))) {  return 0; }
  if (((Array.isArray(__s17) && __s17.length >= 1))) { const t = __s17.slice(1);  return (1 + (t).length); }
  return null;
})();
  }
  function stdlib__isEmpty$List(xs) {
    return (() => {
  const __s18 = xs;
  if (((Array.isArray(__s18) && __s18.length === 0))) {  return true; }
  if (((Array.isArray(__s18) && __s18.length >= 1))) {  return false; }
  return null;
})();
  }
  function stdlib__head(xs) {
    return (() => {
  const __s19 = xs;
  if (((Array.isArray(__s19) && __s19.length === 0))) {  return None(); }
  if (((Array.isArray(__s19) && __s19.length >= 1))) { const h = __s19[0];  return Some(h); }
  return null;
})();
  }
  function stdlib__last(xs) {
    return (() => {
  const __s20 = xs;
  if (((Array.isArray(__s20) && __s20.length === 0))) {  return None(); }
  if (((Array.isArray(__s20) && __s20.length >= 1)) && ((Array.isArray(__s20.slice(1)) && __s20.slice(1).length === 0))) { const h = __s20[0];  return Some(h); }
  if (((Array.isArray(__s20) && __s20.length >= 1))) { const t = __s20.slice(1);  return (() => { const a = t; return a.length ? Some(a[a.length - 1]) : None(); })(); }
  return null;
})();
  }
  function stdlib__contains$List(xs, x) {
    return (() => {
  const __s21 = xs;
  if (((Array.isArray(__s21) && __s21.length === 0))) {  return false; }
  if (((Array.isArray(__s21) && __s21.length >= 1))) { const h = __s21[0]; const t = __s21.slice(1);  return (__eq(h, x) ? (() => {
  return true;
})() : (() => {
  return (t).includes(x);
})()); }
  return null;
})();
  }
  function stdlib__elementAt(xs, i) {
    return (() => {
  const __s22 = xs;
  if (((Array.isArray(__s22) && __s22.length === 0))) {  return None(); }
  if (((Array.isArray(__s22) && __s22.length >= 1))) { const h = __s22[0]; const t = __s22.slice(1);  return (__eq(i, 0) ? (() => {
  return Some(h);
})() : (() => {
  return (() => { const a = t; const i = (i - 1); return i >= 0 && i < a.length ? Some(a[i]) : None(); })();
})()); }
  return null;
})();
  }
  function stdlib__count(xs, f) {
    return (() => {
  const __s23 = xs;
  if (((Array.isArray(__s23) && __s23.length === 0))) {  return 0; }
  if (((Array.isArray(__s23) && __s23.length >= 1))) { const h = __s23[0]; const t = __s23.slice(1);  return (f(h) ? (() => {
  return (1 + (t).filter(f).length);
})() : (() => {
  return (t).filter(f).length;
})()); }
  return null;
})();
  }
  function stdlib__map$List(xs, f) {
    return (() => {
  const __s24 = xs;
  if (((Array.isArray(__s24) && __s24.length === 0))) {  return []; }
  if (((Array.isArray(__s24) && __s24.length >= 1))) { const h = __s24[0]; const t = __s24.slice(1);  return [f(h), ...((t).map(f))]; }
  return null;
})();
  }
  function stdlib__flatMap$List(xs, f) {
    return (() => {
  const __s25 = xs;
  if (((Array.isArray(__s25) && __s25.length === 0))) {  return []; }
  if (((Array.isArray(__s25) && __s25.length >= 1))) { const h = __s25[0]; const t = __s25.slice(1);  return (f(h)).concat((t).flatMap(f)); }
  return null;
})();
  }
  function stdlib__filter$List(xs, f) {
    return (() => {
  const __s26 = xs;
  if (((Array.isArray(__s26) && __s26.length === 0))) {  return []; }
  if (((Array.isArray(__s26) && __s26.length >= 1))) { const h = __s26[0]; const t = __s26.slice(1);  return (f(h) ? (() => {
  return [h, ...((t).filter(f))];
})() : (() => {
  return (t).filter(f);
})()); }
  return null;
})();
  }
  function stdlib__fold(xs, init, f) {
    return (() => {
  const __s27 = xs;
  if (((Array.isArray(__s27) && __s27.length === 0))) {  return init; }
  if (((Array.isArray(__s27) && __s27.length >= 1))) { const h = __s27[0]; const t = __s27.slice(1);  return (t).reduce((acc, x) => (f)(x, acc), f(h, init)); }
  return null;
})();
  }
  function stdlib__forEach(xs, f) {
    (() => {
  const __s28 = xs;
  if (((Array.isArray(__s28) && __s28.length === 0))) {  return null; }
  if (((Array.isArray(__s28) && __s28.length >= 1))) { const h = __s28[0]; const t = __s28.slice(1);  return (() => {
  f(h);
  return (t).forEach(x => (f)(x));
})(); }
  return null;
})();
    return null;
  }
  function stdlib__append(xs, ys) {
    return (() => {
  const __s29 = xs;
  if (((Array.isArray(__s29) && __s29.length === 0))) {  return ys; }
  if (((Array.isArray(__s29) && __s29.length >= 1))) { const h = __s29[0]; const t = __s29.slice(1);  return [h, ...((t).concat(ys))]; }
  return null;
})();
  }
  function stdlib__reverse(xs) {
    return (() => {
  const __s30 = xs;
  if (((Array.isArray(__s30) && __s30.length === 0))) {  return []; }
  if (((Array.isArray(__s30) && __s30.length >= 1))) { const h = __s30[0]; const t = __s30.slice(1);  return ((t).slice().reverse()).concat([h, ...([])]); }
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
  const __s31 = xs;
  if (((Array.isArray(__s31) && __s31.length === 0))) {  return []; }
  if (((Array.isArray(__s31) && __s31.length >= 1))) { const h = __s31[0]; const t = __s31.slice(1);  return (__eq(n, 0) ? (() => {
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
  const __s32 = xs;
  if (((Array.isArray(__s32) && __s32.length === 0))) {  return []; }
  if (((Array.isArray(__s32) && __s32.length >= 1))) { const h = __s32[0]; const t = __s32.slice(1);  return (__eq(n, 0) ? (() => {
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
  const __s33 = xs;
  if (((Array.isArray(__s33) && __s33.length === 0))) {  return []; }
  if (((Array.isArray(__s33) && __s33.length >= 1))) { const h = __s33[0]; const t = __s33.slice(1);  return (() => {
  const __s34 = ys;
  if (((Array.isArray(__s34) && __s34.length === 0))) {  return []; }
  if (((Array.isArray(__s34) && __s34.length >= 1))) { const h2 = __s34[0]; const t2 = __s34.slice(1);  return [[h, h2], ...(stdlib__zip(t, t2))]; }
  return null;
})(); }
  return null;
})();
  }
  function stdlib__joinToString(xs, sep) {
    return (xs).reduce((acc, x) => ((x, acc) => (__eq(acc, "") ? (() => {
  return String(x);
})() : (() => {
  return (acc + (sep + String(x)));
})()))(x, acc), "");
  }
  function stdlib__sum(xs) {
    return (xs).reduce((acc, x) => ((x, acc) => (acc + x))(x, acc), 0);
  }
  function stdlib__maxNat(xs) {
    return (() => {
  const __s35 = xs;
  if (((Array.isArray(__s35) && __s35.length === 0))) {  return None(); }
  if (((Array.isArray(__s35) && __s35.length >= 1))) { const h = __s35[0]; const t = __s35.slice(1);  return (() => {
  const __s36 = t;
  if (((Array.isArray(__s36) && __s36.length === 0))) {  return Some(h); }
  if (((Array.isArray(__s36) && __s36.length >= 1))) {  return (() => {
  const m = stdlib__maxNat(t);
  return (() => {
  const __s37 = m;
  if ((__s37.tag === "Some")) { const v = __s37[0];  return ((h > v) ? (() => {
  return Some(h);
})() : (() => {
  return Some(v);
})()); }
  if ((__s37.tag === "None")) {  return Some(h); }
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
  const __s38 = xs;
  if (((Array.isArray(__s38) && __s38.length === 0))) {  return None(); }
  if (((Array.isArray(__s38) && __s38.length >= 1))) { const h = __s38[0]; const t = __s38.slice(1);  return (() => {
  const __s39 = t;
  if (((Array.isArray(__s39) && __s39.length === 0))) {  return Some(h); }
  if (((Array.isArray(__s39) && __s39.length >= 1))) {  return (() => {
  const m = stdlib__minNat(t);
  return (() => {
  const __s40 = m;
  if ((__s40.tag === "Some")) { const v = __s40[0];  return ((h < v) ? (() => {
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
  function stdlib__distinct(xs) {
    return (() => {
  const __s41 = xs;
  if (((Array.isArray(__s41) && __s41.length === 0))) {  return []; }
  if (((Array.isArray(__s41) && __s41.length >= 1))) { const h = __s41[0]; const t = __s41.slice(1);  return ((t).includes(h) ? (() => {
  return stdlib__distinct(t);
})() : (() => {
  return [h, ...(stdlib__distinct(t))];
})()); }
  return null;
})();
  }
  const stdlib__Ok = (a0) => ({ tag: "Ok", 0: a0 });
  const stdlib__Err = (a0) => ({ tag: "Err", 0: a0 });
  function stdlib__isOk(r) {
    return (() => {
  const __s42 = r;
  if ((__s42.tag === "Ok")) {  return true; }
  if ((__s42.tag === "Err")) {  return false; }
  return null;
})();
  }
  function stdlib__isErr(r) {
    return (() => {
  const __s43 = r;
  if ((__s43.tag === "Ok")) {  return false; }
  if ((__s43.tag === "Err")) {  return true; }
  return null;
})();
  }
  function stdlib__unwrapOr(r, d) {
    return (() => {
  const __s44 = r;
  if ((__s44.tag === "Ok")) { const x = __s44[0];  return x; }
  if ((__s44.tag === "Err")) {  return d; }
  return null;
})();
  }
  function stdlib__map$Result(r, f) {
    return (() => {
  const __s45 = r;
  if ((__s45.tag === "Ok")) { const x = __s45[0];  return stdlib__Ok(f(x)); }
  if ((__s45.tag === "Err")) { const e = __s45[0];  return stdlib__Err(e); }
  return null;
})();
  }
  function stdlib__flatMap$Result(r, f) {
    return (() => {
  const __s46 = r;
  if ((__s46.tag === "Ok")) { const x = __s46[0];  return f(x); }
  if ((__s46.tag === "Err")) { const e = __s46[0];  return stdlib__Err(e); }
  return null;
})();
  }
  function stdlib__mapErr(r, f) {
    return (() => {
  const __s47 = r;
  if ((__s47.tag === "Ok")) { const x = __s47[0];  return stdlib__Ok(x); }
  if ((__s47.tag === "Err")) { const e = __s47[0];  return stdlib__Err(f(e)); }
  return null;
})();
  }
  function stdlib__mapResult(r, f) {
    return (() => {
  const __s48 = r;
  if ((__s48.tag === "Ok")) { const x = __s48[0];  return stdlib__Ok(f(x)); }
  if ((__s48.tag === "Err")) { const e = __s48[0];  return stdlib__Err(e); }
  return null;
})();
  }
  function stdlib__map$Optional(o, f) {
    return (() => {
  const __s49 = o;
  if ((__s49.tag === "Some")) { const x = __s49[0];  return Some(f(x)); }
  if ((__s49.tag === "None")) {  return None(); }
  return null;
})();
  }
  function stdlib__flatMap$Optional(o, f) {
    return (() => {
  const __s50 = o;
  if ((__s50.tag === "Some")) { const x = __s50[0];  return f(x); }
  if ((__s50.tag === "None")) {  return None(); }
  return null;
})();
  }
  function stdlib__filter$Optional(o, f) {
    return (() => {
  const __s51 = o;
  if ((__s51.tag === "Some")) { const x = __s51[0];  return (f(x) ? (() => {
  return o;
})() : (() => {
  return None();
})()); }
  if ((__s51.tag === "None")) {  return None(); }
  return null;
})();
  }
  function stdlib__isSome(o) {
    return (() => {
  const __s52 = o;
  if ((__s52.tag === "Some")) {  return true; }
  if ((__s52.tag === "None")) {  return false; }
  return null;
})();
  }
  function stdlib__isNone(o) {
    return (() => {
  const __s53 = o;
  if ((__s53.tag === "Some")) {  return false; }
  if ((__s53.tag === "None")) {  return true; }
  return null;
})();
  }
  function stdlib__getOrElse(o, d) {
    return (() => {
  const __s54 = o;
  if ((__s54.tag === "Some")) { const x = __s54[0];  return x; }
  if ((__s54.tag === "None")) {  return d; }
  return null;
})();
  }
  function stdlib__orElse(o, d) {
    return (() => {
  const __s55 = o;
  if ((__s55.tag === "Some")) {  return o; }
  if ((__s55.tag === "None")) {  return d; }
  return null;
})();
  }
  function stdlib__toResult(o, e) {
    return (() => {
  const __s56 = o;
  if ((__s56.tag === "Some")) { const x = __s56[0];  return stdlib__Ok(x); }
  if ((__s56.tag === "None")) {  return stdlib__Err(e); }
  return null;
})();
  }
  main();
})();
