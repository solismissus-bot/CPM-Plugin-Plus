package com.tom.cpm.blockbench.util;

/**
 * CPM+ (W2-TOOLS): Minecraft player UUIDs as plain text, pure Java (no Blockbench, no GWT-only API: compiled into the
 * plugin and testable with javac).
 * <ul>
 * <li>{@link #fromName(String)}: the UUID an offline-mode server gives a player name, the same value as Java's
 * {@code UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))}: MD5 of the UTF-8 bytes, version 3,
 * IETF variant (bits 10).</li>
 * <li>{@link #normalize(String)}: a UUID typed or pasted by the user (32 hex digits, with or without the four hyphens,
 * any case, surrounding spaces) as the canonical lower-case hyphenated form; null for anything else and for the nil
 * UUID (all zeros), which never belongs to a player.</li>
 * </ul>
 */
public final class OfflineUuid {
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private OfflineUuid() {
	}

	/** UUID v3 of MD5("OfflinePlayer:" + name), canonical lower-case hyphenated text */
	public static String fromName(String name) {
		byte[] h = md5(utf8("OfflinePlayer:" + (name != null ? name : "")));
		h[6] = (byte) ((h[6] & 0x0f) | 0x30);// version 3
		h[8] = (byte) ((h[8] & 0x3f) | 0x80);// IETF variant
		return format(h);
	}

	/**
	 * Canonical lower-case hyphenated UUID of a typed / pasted text, or null when it is not a UUID (wrong length, a
	 * non-hex character, hyphens in other places) or it is the nil UUID.
	 */
	public static String normalize(String text) {
		if (text == null)return null;
		String t = text.trim();
		String hex;
		if (t.length() == 32) {
			hex = t;
		} else if (t.length() == 36) {
			if (t.charAt(8) != '-' || t.charAt(13) != '-' || t.charAt(18) != '-' || t.charAt(23) != '-')return null;
			hex = t.substring(0, 8) + t.substring(9, 13) + t.substring(14, 18) + t.substring(19, 23) + t.substring(24);
		} else {
			return null;
		}
		StringBuilder sb = new StringBuilder(36);
		boolean nonZero = false;
		for (int i = 0; i < 32; i++) {
			char c = Character.toLowerCase(hex.charAt(i));
			boolean digit = c >= '0' && c <= '9';
			if (!digit && !(c >= 'a' && c <= 'f'))return null;
			if (c != '0')nonZero = true;
			if (i == 8 || i == 12 || i == 16 || i == 20)sb.append('-');
			sb.append(c);
		}
		return nonZero ? sb.toString() : null;
	}

	/** true when {@link #normalize(String)} accepts the text */
	public static boolean isValid(String text) {
		return normalize(text) != null;
	}

	/** 16 bytes as 8-4-4-4-12 lower-case hex */
	static String format(byte[] b) {
		StringBuilder sb = new StringBuilder(36);
		for (int i = 0; i < 16; i++) {
			if (i == 4 || i == 6 || i == 8 || i == 10)sb.append('-');
			sb.append(HEX[(b[i] >> 4) & 0xf]).append(HEX[b[i] & 0xf]);
		}
		return sb.toString();
	}

	/** UTF-8 bytes of a Java string (surrogate pairs combined; a lone surrogate becomes '?', like String.getBytes) */
	static byte[] utf8(String s) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			int c = s.charAt(i);
			if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length()) {
				int d = s.charAt(i + 1);
				if (d >= 0xDC00 && d <= 0xDFFF) {
					c = 0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00);
					i++;
				} else {
					c = '?';
				}
			} else if (c >= 0xD800 && c <= 0xDFFF) {
				c = '?';
			}
			if (c < 0x80) {
				out.write(c);
			} else if (c < 0x800) {
				out.write(0xC0 | (c >> 6));
				out.write(0x80 | (c & 0x3F));
			} else if (c < 0x10000) {
				out.write(0xE0 | (c >> 12));
				out.write(0x80 | ((c >> 6) & 0x3F));
				out.write(0x80 | (c & 0x3F));
			} else {
				out.write(0xF0 | (c >> 18));
				out.write(0x80 | ((c >> 12) & 0x3F));
				out.write(0x80 | ((c >> 6) & 0x3F));
				out.write(0x80 | (c & 0x3F));
			}
		}
		return out.toByteArray();
	}

	private static final int[] S = {
			7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
			5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
			4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
			6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};
	// floor(abs(sin(i + 1)) * 2^32), written out (the browser's Math.sin is not guaranteed bit-exact)
	private static final int[] K = {
			0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
			0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
			0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
			0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
			0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
			0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
			0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
			0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391};

	/**
	 * rotate left. Not Integer.rotateLeft: in the browser build int additions are not wrapped to 32 bits (GWT emits a
	 * plain '+'), and the web emulation of rotateLeft tests the sign of its argument; the shifts here convert their
	 * input to 32 bits first (JS ToInt32 / ToUint32, exact for these magnitudes), so the result is right in both.
	 */
	static int rotl(int x, int c) {
		return (x << c) | (x >>> (32 - c));
	}

	/** MD5 (RFC 1321) of the bytes; java.security is not available in the browser build */
	static byte[] md5(byte[] msg) {
		int len = msg.length;
		int blocks = ((len + 8) >>> 6) + 1;
		byte[] p = new byte[blocks << 6];
		System.arraycopy(msg, 0, p, 0, len);
		p[len] = (byte) 0x80;
		long bits = (long) len << 3;
		for (int i = 0; i < 8; i++)p[p.length - 8 + i] = (byte) (bits >>> (8 * i));
		int a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
		int[] m = new int[16];
		for (int blk = 0; blk < blocks; blk++) {
			int off = blk << 6;
			for (int i = 0; i < 16; i++) {
				int j = off + (i << 2);
				m[i] = (p[j] & 0xff) | ((p[j + 1] & 0xff) << 8) | ((p[j + 2] & 0xff) << 16) | ((p[j + 3] & 0xff) << 24);
			}
			int a = a0, b = b0, c = c0, d = d0;
			for (int i = 0; i < 64; i++) {
				int f, g;
				if (i < 16) {
					f = (b & c) | (~b & d);
					g = i;
				} else if (i < 32) {
					f = (d & b) | (~d & c);
					g = (5 * i + 1) & 15;
				} else if (i < 48) {
					f = b ^ c ^ d;
					g = (3 * i + 5) & 15;
				} else {
					f = c ^ (b | ~d);
					g = (7 * i) & 15;
				}
				int t = d;
				d = c;
				c = b;
				int x = a + f + K[i] + m[g];
				b = b + rotl(x, S[i]);
				a = t;
			}
			a0 += a;
			b0 += b;
			c0 += c;
			d0 += d;
		}
		byte[] out = new byte[16];
		int[] r = {a0, b0, c0, d0};
		for (int i = 0; i < 4; i++)for (int j = 0; j < 4; j++)out[i * 4 + j] = (byte) (r[i] >>> (8 * j));
		return out;
	}
}
