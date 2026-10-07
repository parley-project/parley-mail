package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * Messages stored in FileSource, including ones far larger than the heap.
 * The Maven build runs tests with -Xmx64m (see the surefire argLine in pom.xml),
 * so the 150 MB attachment below can only pass if nothing loads it into memory.
 */
public class TestLargeMessage {

	private static final long BIG = 150L * 1024 * 1024;

	private FileSource dir;

	@BeforeEach
	public void setUp() throws IOException {
		FileSourceFactory f = FileSourceFactory.getDefaultFactory();
		dir = f.createTempDirectory("bjlmsgtest");
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (dir != null) {
			for (FileSource f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	/** Write {@code size} pseudo-random bytes to a file; returns their SHA-256. */
	private static byte[] writeRandom(FileSource file, long size, long seed) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		Random r = new Random(seed);
		byte[] buf = new byte[1024 * 1024];
		try (OutputStream out = new DigestOutputStream(file.getOutputStream(), md)) {
			long left = size;
			while (left > 0) {
				r.nextBytes(buf);
				int n = (int) Math.min(buf.length, left);
				out.write(buf, 0, n);
				left -= n;
			}
		}
		return md.digest();
	}

	private static byte[] sha256(InputStream in) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		try (InputStream d = new DigestInputStream(in, md)) {
			byte[] buf = new byte[1024 * 1024];
			while (d.read(buf) > 0) {
				// digesting
			}
		}
		return md.digest();
	}

	private static byte[] sha256(FileSource f) throws Exception {
		return sha256(f.getInputStream());
	}

	private int fileCount() throws IOException {
		return dir.listFiles().length;
	}

	@Test
	public void testMessageLargerThanHeap() throws Exception {
		System.out.println("TestLargeMessage: max heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024)
				+ " MB, attachment " + BIG / (1024 * 1024) + " MB");
		FileSource data = dir.getChild("big.bin");
		byte[] digest = writeRandom(data, BIG, 42);

		// build: the attachment is base64-encoded as a stream into a temp file in the work directory
		Message m = new Message().setWorkDirectory(dir);
		m.setFrom(new Address("Tony", "tony", "bringardner.us"));
		m.setTo(new Address("john", "example.com"));
		m.setSubject("Big attachment");
		m.setText("See the attached file.\n");
		Message att = m.addAttachment("big.bin", "application/octet-stream", data);
		assertEquals("base64", att.getTransferEncoding());
		assertTrue(att.getBodyLength() > BIG * 4 / 3, "encoded size " + att.getBodyLength());
		int withTemp = fileCount();

		FileSource msgFile = dir.getChild("message.eml");
		m.writeTo(msgFile);
		m.close();
		// big.bin, the encoded temp file and message.eml -> close() deletes the temp file
		assertEquals(withTemp + 1, 3);
		assertEquals(2, fileCount(), "close() deletes the encoded temp file");

		// parse the stored message in place
		Message p = Message.parse(msgFile);
		assertEquals("Big attachment", p.getSubject());
		assertEquals(2, p.getParts().size());
		assertEquals("See the attached file.\r\n", p.getParts().get(0).getText());
		Message bin = p.getAttachments().get(0);
		assertEquals("big.bin", bin.getFilename());
		assertArrayEquals(digest, sha256(bin.openContent()));

		FileSource saved = dir.getChild("saved.bin");
		bin.saveContent(saved);
		assertEquals(BIG, saved.length());
		assertArrayEquals(digest, sha256(saved));

		// unchanged: written back byte for byte
		FileSource copy = dir.getChild("copy.eml");
		p.writeTo(copy);
		assertEquals(msgFile.length(), copy.length());
		assertArrayEquals(sha256(msgFile), sha256(copy));

		// change a header and write back over the file the message is read from
		p.setSubject("Big attachment (updated)");
		p.addHeader("X-Stored", "yes");
		p.writeTo(msgFile);
		assertEquals("Big attachment (updated)", p.getSubject());
		Message again = Message.parse(msgFile);
		assertEquals("Big attachment (updated)", again.getSubject());
		assertEquals("yes", again.getHeader("X-Stored"));
		assertArrayEquals(digest, sha256(again.getAttachments().get(0).openContent()));
		// p was re-read from the new file and still works
		assertArrayEquals(digest, sha256(p.getAttachments().get(0).openContent()));

		// read from a stream: stored in a temp file the message owns
		int before = fileCount();
		Message fromStream = Message.read(msgFile.getInputStream(), dir.getChild("stored.eml"));
		assertEquals(before + 1, fileCount());
		assertEquals(2, fromStream.getParts().size());
		fromStream.close();
		assertEquals(before + 1, fileCount(), "a store file given by the caller is not deleted");

		Message.setDefaultWorkDirectory(dir);
		try {
			before = fileCount();
			Message owned = Message.read(msgFile.getInputStream());
			assertEquals(before + 1, fileCount());
			assertArrayEquals(digest, sha256(owned.getAttachments().get(0).openContent()));
			owned.close();
			assertEquals(before, fileCount(), "close() deletes the temp copy");
		} finally {
			Message.setDefaultWorkDirectory(null);
		}
	}

	@Test
	public void testLargeAttachedMessage() throws Exception {
		// a message/rfc822 part with a big 8-bit body, parsed in place (no decoding needed)
		FileSource file = dir.getChild("outer.eml");
		long innerBody = 40L * 1024 * 1024;
		try (OutputStream out = file.getOutputStream()) {
			out.write(("Subject: outer\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n"
					+ "Content-Type: message/rfc822\r\n\r\nSubject: inner\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			byte[] line = "0123456789012345678901234567890123456789012345678901234567890123456789\r\n".getBytes(StandardCharsets.US_ASCII);
			for (long n = 0; n < innerBody; n += line.length) {
				out.write(line);
			}
			out.write("\r\n--b--\r\n".getBytes(StandardCharsets.US_ASCII));
		}
		Message m = Message.parse(file);
		Message inner = m.getParts().get(0).getAttachedMessage();
		assertNotNull(inner);
		assertEquals("inner", inner.getSubject());
		assertTrue(inner.getBodyLength() >= innerBody);

		// unchanged: identical output, streamed
		FileSource copy = dir.getChild("copy.eml");
		m.writeTo(copy);
		assertArrayEquals(sha256(file), sha256(copy));

		// change the inner message: re-written as a stream
		inner.setSubject("inner (changed)");
		m.writeTo(copy);
		Message back = Message.parse(copy);
		Message backInner = back.getParts().get(0).getAttachedMessage();
		assertEquals("inner (changed)", backInner.getSubject());
		assertEquals(inner.getBodyLength(), backInner.getBodyLength());
		m.close();
	}

	@Test
	public void testStreamingTextContent() throws Exception {
		FileSource text = dir.getChild("notes.txt");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 20000; i++) {
			sb.append("Zeile ").append(i).append(": Grüße aus Köln\n"); // LF only, non-ASCII
		}
		try (OutputStream out = text.getOutputStream()) {
			out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
		}
		Message m = new Message().setWorkDirectory(dir);
		Message part = m.addAttachment("notes.txt", "text/plain; charset=UTF-8", text);
		assertEquals("quoted-printable", part.getTransferEncoding());
		Message back = Message.parse(m.toByteArray());
		assertEquals(sb.toString().replace("\n", "\r\n"), back.getAttachments().get(0).getText());

		FileSource ascii = dir.getChild("ascii.txt");
		try (OutputStream out = ascii.getOutputStream()) {
			out.write("line one\nline two\r\nline three".getBytes(StandardCharsets.US_ASCII));
		}
		Message a = new Message().setWorkDirectory(dir).setContent(ascii, "text/plain");
		assertEquals("7bit", a.getTransferEncoding());
		assertEquals("line one\r\nline two\r\nline three", a.getText());
		m.close();
		a.close();
	}

	@Test
	public void testMemoryFileSource() throws Exception {
		MemoryFileSourceFactory factory = new MemoryFileSourceFactory();
		factory.connect();
		FileSource memDir = factory.createTempDirectory("mail");
		FileSource file = memDir.getChild("msg.eml");
		String text = "Subject: hello\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\npre\r\n--b\r\n\r\none\r\n--b\r\n"
				+ "Content-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n--b--\r\nepi\r\n";
		Message m = Message.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)), file);
		assertEquals("hello", m.getSubject());
		assertEquals("pre", new String(m.getPreamble(), StandardCharsets.US_ASCII));
		assertEquals("epi\r\n", new String(m.getEpilogue(), StandardCharsets.US_ASCII));
		assertArrayEquals(new byte[] {1, 2, 3}, m.getParts().get(1).getContent());
		assertEquals(text, new String(m.toByteArray(), StandardCharsets.US_ASCII));

		m.setWorkDirectory(memDir);
		m.addAttachment("x.txt", "text/plain", "x".getBytes(StandardCharsets.US_ASCII));
		m.writeTo(file); // over its own file
		Message back = Message.parse(file);
		assertEquals(3, back.getParts().size());
		assertEquals("x", back.getParts().get(2).getText());
	}

	@Test
	public void testManyParts() throws Exception {
		FileSource file = dir.getChild("many.eml");
		int count = 5000;
		try (OutputStream out = file.getOutputStream()) {
			out.write("Content-Type: multipart/mixed; boundary=b\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			for (int i = 0; i < count; i++) {
				out.write(("--b\r\nContent-Type: text/plain\r\n\r\npart " + i + "\r\n").getBytes(StandardCharsets.US_ASCII));
			}
			out.write("--b--\r\n".getBytes(StandardCharsets.US_ASCII));
		}
		Message m = Message.parse(file);
		assertEquals(count, m.getParts().size());
		assertEquals("part 4321", m.getParts().get(4321).getText());
		FileSource copy = dir.getChild("many-copy.eml");
		m.writeTo(copy);
		assertArrayEquals(sha256(file), sha256(copy));
	}

	@Test
	public void testQuotedPrintableStreamsRoundTrip() throws Exception {
		Random r = new Random(5);
		for (int i = 0; i < 300; i++) {
			byte[] data = new byte[r.nextInt(3000)];
			for (int j = 0; j < data.length; j++) {
				// lots of spaces, tabs, CR, LF and '=' to exercise the edge cases
				int k = r.nextInt(10);
				data[j] = (byte) (k == 0 ? ' ' : k == 1 ? '\t' : k == 2 ? '\n' : k == 3 ? '\r' : k == 4 ? '=' : r.nextInt(256));
			}
			assertArrayEquals(data, QuotedPrintable.decode(QuotedPrintable.encode(data, false)), "binary " + i);
			byte[] enc = QuotedPrintable.encode(data, true);
			for (String line : new String(enc, StandardCharsets.ISO_8859_1).split("\r\n")) {
				assertTrue(line.length() <= 76, line);
				assertFalse(line.endsWith(" ") || line.endsWith("\t"), "trailing whitespace");
			}
		}
	}
}
