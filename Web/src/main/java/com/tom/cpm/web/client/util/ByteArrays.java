package com.tom.cpm.web.client.util;

import java.io.IOException;

/**
 * CPM+ (load: A4/A5): conversions between JS typed arrays and Java byte[] without Base64 in between, and the text
 * decoding of project JSON files (the same result as the web InputStreamReader).
 */
public final class ByteArrays {
	private ByteArrays() {
	}

	/** length of a Uint8Array (or any array-like) */
	public static native int length(Object u8)/*-{
		return u8 ? u8.length | 0 : 0;
	}-*/;

	/** Uint8Array -> new Java byte[] (signed values, same content) */
	public static byte[] toBytes(Object u8) {
		int n = length(u8);
		byte[] out = new byte[n];
		copyInto(u8, out, n);
		return out;
	}

	private static native void copyInto(Object u8, byte[] out, int n)/*-{
		for (var i = 0; i < n; i++) out[i] = (u8[i] << 24) >> 24;
	}-*/;

	/** Java byte[] -> new Uint8Array (the Uint8Array constructor wraps the signed values modulo 256) */
	public static native Object toUint8Array(byte[] b)/*-{
		return new Uint8Array(b);
	}-*/;

	/** the 8 bytes after the PNG signature: IHDR bit depth (byte 24) and colour type (byte 25), -1 when too short */
	public static native int byteAt(Object u8, int i)/*-{
		return (u8 && i < u8.length) ? (u8[i] & 0xff) : -1;
	}-*/;

	/**
	 * Text of a project JSON file, decoded exactly like the web InputStreamReader (v0.7): strict UTF-8 when the bytes
	 * are valid UTF-8 (TextDecoder fatal), otherwise ISO-8859-1 / Latin-1 (one char per byte, NOT windows-1252); one
	 * leading BOM (U+FEFF) is removed.
	 */
	public static String decodeText(Object u8) throws IOException {
		String s = decodeUtf8OrNull(u8);
		if(s == null)s = decodeLatin1(u8);
		if(s == null)throw new IOException("Could not decode text");
		if(s.length() > 0 && s.charAt(0) == 0xFEFF)s = s.substring(1);
		return s;
	}

	private static native String decodeUtf8OrNull(Object u8)/*-{
		try {
			if (typeof TextDecoder === 'undefined') return null;
			return new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(u8);
		} catch (e) {
			return null;
		}
	}-*/;

	private static native String decodeLatin1(Object u8)/*-{
		var n = u8.length, parts = [], CH = 8192;
		for (var i = 0; i < n; i += CH) parts.push(String.fromCharCode.apply(null, u8.subarray(i, Math.min(n, i + CH))));
		return parts.join('');
	}-*/;
}
