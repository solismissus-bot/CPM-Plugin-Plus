package com.tom.cpm.web.client.java.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.web.client.java.FakeReader;

public class InputStreamReader extends Reader implements FakeReader {
	private String text;
	private StringReader rd;

	public InputStreamReader(InputStream in) throws IOException {
		// CPM+ : the default charset was ISO-8859-1, but the web OutputStreamWriter always writes
		// UTF-8 and modern CPM writes UTF-8 too, so CJK/Cyrillic/emoji names in .cpmproject files
		// were corrupted (#952 / #662). Decode as UTF-8 when the bytes are valid UTF-8, otherwise
		// fall back to the old single-byte decoding (old files written with Windows-1252).
		this(in, null);
	}

	public InputStreamReader(InputStream in, Charset cs) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		IOHelper.copy(in, baos);
		byte[] b = baos.toByteArray();
		if(cs == null)cs = isValidUtf8(b) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
		text = new String(b, cs);
		if(text.length() > 0 && text.charAt(0) == 0xFEFF)text = text.substring(1);// CPM+ : strip BOM
		rd = new StringReader(text);
	}

	/** CPM+ : strict UTF-8 validation (no overlongs, no surrogates, max U+10FFFF) */
	public static boolean isValidUtf8(byte[] b) {
		int i = 0;
		int n = b.length;
		while (i < n) {
			int c = b[i] & 0xff;
			if(c < 0x80) {
				i++;
				continue;
			}
			int len;
			int min;
			if(c >= 0xC2 && c <= 0xDF) {
				len = 1;
				min = 0x80;
			} else if(c >= 0xE0 && c <= 0xEF) {
				len = 2;
				min = 0x800;
			} else if(c >= 0xF0 && c <= 0xF4) {
				len = 3;
				min = 0x10000;
			} else return false;
			if(i + len >= n)return false;// truncated sequence
			int cp = c & (0x3F >> len);
			for (int k = 1; k <= len; k++) {
				int cc = b[i + k] & 0xff;
				if((cc & 0xC0) != 0x80)return false;
				cp = (cp << 6) | (cc & 0x3F);
			}
			if(cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF))return false;
			i += len + 1;
		}
		return true;
	}

	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {
		return rd.read(cbuf, off, len);
	}

	@Override
	public void close() throws IOException {
	}

	@Override
	public String getContent() {
		return text;
	}
}
