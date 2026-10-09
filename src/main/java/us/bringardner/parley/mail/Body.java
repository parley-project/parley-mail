package us.bringardner.parley.mail;

import us.bringardner.parley.io.IoUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Arrays;

import us.bringardner.parley.files.FileSource;

/**
 * A run of message bytes: either a small array in memory or a window
 * (offset, length) onto a {@link FileSource}. Bodies are immutable and only read
 * through streams, so a window onto a large file never loads it into memory.
 */
abstract class Body implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Arrays at most this size can be returned by {@link #toByteArray()}. */
	static final int MAX_ARRAY = Integer.MAX_VALUE - 16;

	static final Body EMPTY = new Bytes(new byte[0], 0, 0);

	abstract long length() throws IOException;

	/** A stream of the bytes from {@code offset} (relative to this body) to its end. */
	abstract InputStream open(long offset) throws IOException;

	InputStream open() throws IOException {
		return open(0);
	}

	/** A window onto part of this body, without copying. */
	abstract Body slice(long offset, long length);

	/** The file behind this body, or null for an in-memory body. */
	FileSource getFile() {
		return null;
	}

	/** Read the whole body into memory. Only for bodies known to be small. */
	byte[] toByteArray() throws IOException {
		long len = length();
		if (len > MAX_ARRAY) {
			throw new IOException("Too large to load into memory: " + len + " bytes; use a stream instead");
		}
		try (InputStream in = open()) {
			return in.readAllBytes();
		}
	}

	static Body of(byte[] data) {
		return new Bytes(data, 0, data.length);
	}

	static Body of(FileSource file) throws IOException {
		return new Range(file, 0, file.length());
	}

	/** In-memory bytes. */
	static final class Bytes extends Body {
		private static final long serialVersionUID = 1L;
		private final byte[] data;
		private final int off;
		private final int len;

		Bytes(byte[] data, int off, int len) {
			this.data = data;
			this.off = off;
			this.len = len;
		}

		@Override
		long length() {
			return len;
		}

		@Override
		InputStream open(long offset) {
			int o = (int) Math.min(offset, len);
			return new ByteArrayInputStream(data, off + o, len - o);
		}

		@Override
		Body slice(long offset, long length) {
			int o = (int) Math.min(offset, len);
			int l = (int) Math.max(0, Math.min(length, len - o));
			return new Bytes(data, off + o, l);
		}

		@Override
		byte[] toByteArray() {
			return Arrays.copyOfRange(data, off, off + len);
		}
	}

	/** A window onto a file. */
	static final class Range extends Body {
		private static final long serialVersionUID = 1L;
		private final FileSource file;
		private final long off;
		private final long len;

		Range(FileSource file, long off, long len) {
			this.file = file;
			this.off = off;
			this.len = len;
		}

		@Override
		long length() {
			return len;
		}

		@Override
		InputStream open(long offset) throws IOException {
			long o = Math.min(offset, len);
			// buffered: decoders such as Base64's read one byte at a time
			return new Bounded(IoUtils.buffered(file.getInputStream(off + o)), len - o);
		}

		@Override
		Body slice(long offset, long length) {
			long o = Math.min(offset, len);
			return new Range(file, off + o, Math.max(0, Math.min(length, len - o)));
		}

		@Override
		FileSource getFile() {
			return file;
		}
	}

	/** Reads at most {@code remaining} bytes from a stream. */
	static final class Bounded extends InputStream {
		private final InputStream in;
		private long remaining;

		Bounded(InputStream in, long remaining) {
			this.in = in;
			this.remaining = remaining;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int b = in.read();
			if (b >= 0) {
				remaining--;
			}
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int n = in.read(b, off, (int) Math.min(len, remaining));
			if (n > 0) {
				remaining -= n;
			}
			return n;
		}

		@Override
		public long skip(long n) throws IOException {
			long s = in.skip(Math.min(n, remaining));
			if (s > 0) {
				remaining -= s;
			}
			return s;
		}

		@Override
		public int available() throws IOException {
			return (int) Math.min(in.available(), remaining);
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}

	/** Collects a small body in memory. */
	static Body collect(ByteArrayOutputStream out) {
		return of(out.toByteArray());
	}
}
