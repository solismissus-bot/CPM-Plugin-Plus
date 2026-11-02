package com.tom.cpm.blockbench.proxy.ext;

import elemental2.promise.Promise;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W3-SETTINGS): browser / Blockbench natives of the settings dialogs. Every optional API is feature-detected.
 */
public final class W3SettingsNatives {
	private W3SettingsNatives() {
	}

	/**
	 * Model icon from a PNG data URL: resolves {b64, w, h, reencoded}. A PNG that is already 256x256 is returned
	 * byte for byte (no re-encode). Anything else is centre-cropped to a square and scaled to exactly 256x256
	 * (nearest-neighbour when a small square image is scaled up by a whole number, e.g. 64 -> 256 pixel art; smooth
	 * otherwise). Rejects with Error('notpng') when the data is not a PNG and Error('decode') when it can't be read.
	 */
	public static native Promise<JsPropertyMap<Object>> iconFromDataUrl(String dataUrl) /*-{
		return new $wnd.Promise(function(res, rej) {
			try {
				if (typeof dataUrl !== 'string' || dataUrl.indexOf('data:') !== 0 || dataUrl.indexOf(',') < 0) { rej(new $wnd.Error('notpng')); return; }
				var b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
				var head = dataUrl.substring(0, dataUrl.indexOf(','));
				// PNG signature 89 50 4E 47 0D 0A 1A 0A = 'iVBORw0KGgo' in base64 (the file type is never trusted)
				if (head.indexOf(';base64') < 0 || b64.indexOf('iVBORw0KGgo') !== 0) { rej(new $wnd.Error('notpng')); return; }
				var img = new $wnd.Image();
				img.onload = function() {
					try {
						var w = img.naturalWidth, h = img.naturalHeight;
						if (!w || !h) { rej(new $wnd.Error('decode')); return; }
						if (w === 256 && h === 256) { res({b64: b64, w: w, h: h, reencoded: false}); return; }
						var side = Math.min(w, h), sx = Math.floor((w - side) / 2), sy = Math.floor((h - side) / 2);
						var c = $wnd.document.createElement('canvas');
						c.width = 256; c.height = 256;
						var g = c.getContext('2d');
						var pixelArt = side < 256 && 256 % side === 0;
						g.imageSmoothingEnabled = !pixelArt;
						if (!pixelArt) g.imageSmoothingQuality = 'high';
						g.clearRect(0, 0, 256, 256);
						g.drawImage(img, sx, sy, side, side, 0, 0, 256, 256);
						var out = c.toDataURL('image/png');
						res({b64: out.substring(out.indexOf(',') + 1), w: w, h: h, reencoded: true});
					} catch (e) { rej(new $wnd.Error('decode')); }
				};
				img.onerror = function() { rej(new $wnd.Error('decode')); };
				img.src = 'data:image/png;base64,' + b64;
			} catch (e) {
				rej(new $wnd.Error('decode'));
			}
		});
	}-*/;

	/** size of a stored base64 PNG: resolves {w, h} (0 when it can't be decoded) */
	public static native Promise<JsPropertyMap<Object>> pngSize(String b64) /*-{
		return new $wnd.Promise(function(res) {
			try {
				var img = new $wnd.Image();
				img.onload = function() { res({w: img.naturalWidth || 0, h: img.naturalHeight || 0}); };
				img.onerror = function() { res({w: 0, h: 0}); };
				img.src = 'data:image/png;base64,' + b64;
			} catch (e) { res({w: 0, h: 0}); }
		});
	}-*/;

	/**
	 * Screenshot of the active 3D viewport as a PNG data URL (Blockbench crops it to the model), or rejects when
	 * there is no viewport / screenshot API.
	 */
	public static native Promise<String> viewportScreenshot() /*-{
		return new $wnd.Promise(function(res, rej) {
			try {
				var P = $wnd.Preview;
				var p = P && P.selected;
				if (!p) { rej(new $wnd.Error('nopreview')); return; }
				var done = false;
				var cb = function(url) { if (done) return; done = true; if (typeof url === 'string' && url.indexOf('data:') === 0) res(url); else rej(new $wnd.Error('noshot')); };
				if (typeof p.screenshot === 'function') p.screenshot({crop: true}, cb);
				else if ($wnd.Screencam && typeof $wnd.Screencam.screenshotPreview === 'function') $wnd.Screencam.screenshotPreview(p, {crop: true}, cb);
				else if (p.canvas && typeof p.canvas.toDataURL === 'function') { if (typeof p.render === 'function') p.render(); cb(p.canvas.toDataURL('image/png')); }
				else rej(new $wnd.Error('noshot'));
				$wnd.setTimeout(function() { if (!done) { done = true; rej(new $wnd.Error('timeout')); } }, 15000);
			} catch (e) {
				rej(e);
			}
		});
	}-*/;

	/** true where a premium (Mojang) name lookup can work: the desktop app (Node https) or a test stub */
	public static native boolean canLookupPremium(boolean testMode) /*-{
		if (testMode && typeof $wnd.__cpmplus_test_mojang === 'function') return true;
		return typeof $wnd.requireNativeModule === 'function' || typeof $wnd.require === 'function';
	}-*/;

	/**
	 * Mojang profile lookup (desktop only: api.mojang.com sends no CORS headers, the website can't call it): resolves
	 * the response text ({"id": "...", "name": "..."}), '' when the name does not exist (204/404); rejects on any
	 * other failure. Test mode: window.__cpmplus_test_mojang(nick) -> Promise<text> replaces the request.
	 */
	public static native Promise<String> lookupPremium(String nick, boolean testMode) /*-{
		return new $wnd.Promise(function(res, rej) {
			try {
				if (testMode && typeof $wnd.__cpmplus_test_mojang === 'function') {
					$wnd.Promise.resolve($wnd.__cpmplus_test_mojang(nick)).then(function(t) { res(t == null ? '' : String(t)); }, rej);
					return;
				}
				var https = null;
				if (typeof $wnd.requireNativeModule === 'function') {
					https = $wnd.requireNativeModule('https', {message: 'CPM+: buscar o UUID de um nick na Mojang (api.mojang.com)'});
				} else if (typeof $wnd.require === 'function') {
					https = $wnd.require('https');
				}
				if (!https || typeof https.get !== 'function') { rej(new $wnd.Error('nohttps')); return; }
				var req = https.get('https://api.mojang.com/users/profiles/minecraft/' + encodeURIComponent(nick), function(r) {
					var data = '';
					r.setEncoding && r.setEncoding('utf8');
					r.on('data', function(d) { data += d; });
					r.on('end', function() {
						if (r.statusCode === 204 || r.statusCode === 404) res('');
						else if (r.statusCode >= 200 && r.statusCode < 300) res(data);
						else rej(new $wnd.Error('http ' + r.statusCode));
					});
				});
				req.on('error', function(e) { rej(e); });
				if (typeof req.setTimeout === 'function') req.setTimeout(10000, function() { try { req.destroy(); } catch (e) {} rej(new $wnd.Error('timeout')); });
			} catch (e) {
				rej(e);
			}
		});
	}-*/;

	/** Canvas.updateAll / per-cube geometry + UV refresh after cubes were changed (all optional) */
	public static native void refreshCubes(Object[] cubes) /*-{
		try {
			var pc = $wnd.Cube && $wnd.Cube.preview_controller;
			if (cubes) for (var i = 0; i < cubes.length; i++) {
				var c = cubes[i];
				try {
					if (pc && typeof pc.updateGeometry === 'function') pc.updateGeometry(c);
					if (pc && typeof pc.updateUV === 'function') pc.updateUV(c);
					if (pc && typeof pc.updateTransform === 'function') pc.updateTransform(c);
				} catch (e) {}
			}
			if ($wnd.Canvas && typeof $wnd.Canvas.updateAllBones === 'function') $wnd.Canvas.updateAllBones();
			if ($wnd.Canvas && typeof $wnd.Canvas.updateAllPositions === 'function') $wnd.Canvas.updateAllPositions();
		} catch (e) {
			console.warn('CPM+: viewport refresh failed', e);
		}
	}-*/;

	/** setTimeout(r, ms) */
	public static native void later(Runnable r, int ms) /*-{
		$wnd.setTimeout(function() { r.@java.lang.Runnable::run()(); }, ms);
	}-*/;
}
