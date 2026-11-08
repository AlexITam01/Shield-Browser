(function () {
  'use strict';
  if (window.__shieldCanvas) return;
  try {
    Object.defineProperty(window, '__shieldCanvas', { value: true });
  } catch (e) {
    return;
  }

  // Seed is per site + per app session: stable inside one site, different across sites.
  var SALT = '__SALT__';
  var MAX_PIXELS = 4000000; // skip huge canvases to save battery

  function hash(str) {
    var h = 2166136261;
    for (var i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return h >>> 0;
  }
  var base = hash(SALT + '|' + location.hostname);

  // Flip the lowest bit of a handful of channel values: invisible, but changes the hash.
  function perturb(data) {
    var px = data.length >> 2;
    if (px === 0) return;
    var s = (base ^ px) >>> 0;
    var n = Math.min(12, px);
    for (var k = 0; k < n; k++) {
      s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
      var p = s % px;
      s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
      var ch = s % 3;
      var i = p * 4 + ch;
      data[i] = data[i] ^ 1;
    }
  }

  var origGetContext = HTMLCanvasElement.prototype.getContext;
  var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
  var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
  var origToBlob = HTMLCanvasElement.prototype.toBlob;

  // Remember which canvases already have a 2D context, so we never create one
  // ourselves (that would block a later WebGL context on the same canvas).
  var ctxMap = new WeakMap();

  function noisyCopy(canvas) {
    var ctx = ctxMap.get(canvas);
    if (!ctx) return null;
    var w = canvas.width, h = canvas.height;
    if (!w || !h || w * h > MAX_PIXELS) return null;
    var img = origGetImageData.call(ctx, 0, 0, w, h); // throws if canvas is tainted
    perturb(img.data);
    var copy = document.createElement('canvas');
    copy.width = w;
    copy.height = h;
    origGetContext.call(copy, '2d').putImageData(img, 0, 0);
    return copy;
  }

  function cloak(fn, name) {
    try {
      Object.defineProperty(fn, 'toString', {
        value: function () { return 'function ' + name + '() { [native code] }'; }
      });
    } catch (e) {}
    return fn;
  }

  HTMLCanvasElement.prototype.getContext = cloak(function (type) {
    var ctx = origGetContext.apply(this, arguments);
    if (ctx && type === '2d') ctxMap.set(this, ctx);
    return ctx;
  }, 'getContext');

  HTMLCanvasElement.prototype.toDataURL = cloak(function () {
    try {
      var c = noisyCopy(this);
      if (c) return origToDataURL.apply(c, arguments);
    } catch (e) {}
    return origToDataURL.apply(this, arguments);
  }, 'toDataURL');

  HTMLCanvasElement.prototype.toBlob = cloak(function () {
    try {
      var c = noisyCopy(this);
      if (c) return origToBlob.apply(c, arguments);
    } catch (e) {}
    return origToBlob.apply(this, arguments);
  }, 'toBlob');

  CanvasRenderingContext2D.prototype.getImageData = cloak(function () {
    var img = origGetImageData.apply(this, arguments);
    try { perturb(img.data); } catch (e) {}
    return img;
  }, 'getImageData');
})();
