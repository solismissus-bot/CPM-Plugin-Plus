package com.tom.cpm.web.client.java.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;

import com.tom.cpm.web.client.FS;
import com.tom.cpm.web.client.LocalStorageFS;

public class FileOutputStream extends OutputStream {
	private ByteArrayOutputStream baos;
	private File file;

	public FileOutputStream(File file) throws IOException {
		this.file = file;
		baos = new ByteArrayOutputStream();
	}

	@Override
	public void write(byte[] b) throws IOException {
		if(baos == null)throw new IOException("File closed");
		baos.write(b);
	}

	@Override
	public void write(int b) throws IOException {
		if(baos == null)throw new IOException("File closed");
		baos.write(b);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		if(baos == null)throw new IOException("File closed");
		baos.write(b, off, len);
	}

	@Override
	public void flush() throws IOException {
		if(baos == null)throw new IOException("File closed");
		String path = file.getAbsolutePath();
		String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
		// CPM+ (B6): the promise of setContent was dropped, so a full storage was an unhandled rejection and the caller
		// (e.g. ModelWriter.finish of 'Export > Model') reported success for a file never written. localStorage is
		// synchronous: write it here and throw the error; '/mnt/..' stays the async download.
		if(!path.startsWith("/mnt") && FS.getImpl() instanceof LocalStorageFS) {
			((LocalStorageFS) FS.getImpl()).writeSync(path, b64);
		} else {
			FS.setContent(path, b64);
		}
	}

	@Override
	public void close() throws IOException {
		if(baos == null)return;
		try {
			flush();
		} finally {
			baos = null;
		}
	}
}
