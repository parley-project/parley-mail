package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;


public class TestMessage {

	/** Write test messages with \n and turn them into CRLF. */
	private static byte[] crlf(String text) {
		return text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
	}

	private static String str(byte[] b) {
		return new String(b, StandardCharsets.UTF_8);
	}

	private static final String SIMPLE =
			"Return-Path: <tony@bringardner.us>\n"
			+ "From: Tony Bringardner <tony@bringardner.us>\n"
			+ "To: \"Smith, John\" <john@example.com>,\n"
			+ "  jane@example.com (Jane)\n"
			+ "Subject: =?ISO-8859-1?Q?Caf=E9?= meeting\n"
			+ "Date: Tue, 16 Jul 2013 13:52:53 -0700 (PDT)\n"
			+ "Message-ID: <123@bringardner.us>\n"
			+ "\n"
			+ "Hello,\n"
			+ "\n"
			+ "See you there.\n";

	@Test
	public void testReadSimple() throws IOException, ParseException {
		try (Message m = Message.read(new ByteArrayInputStream(crlf(SIMPLE)))) {
			checkSimple(m);
		}
	}

	private static void checkSimple(Message m) throws ParseException {
		assertEquals(6, m.getHeaders().size());
		assertEquals("Café meeting", m.getSubject());
		assertEquals("<123@bringardner.us>", m.getMessageId());
		assertEquals("Tue, 16 Jul 2013 13:52:53 -0700", m.getDate().toString());

		List<Address> from = m.getFrom();
		assertEquals(1, from.size());
		assertEquals("Tony Bringardner", from.get(0).getDisplayName());

		// the folded To header is unfolded; the quoted comma doesn't split
		List<Address> to = m.getTo();
		assertEquals(2, to.size());
		assertEquals("Smith, John", to.get(0).getDisplayName());
		assertEquals("john@example.com", to.get(0).getUser() + "@" + to.get(0).getDomain());
		assertEquals("jane", to.get(1).getUser());

		assertFalse(m.isMultipart());
		assertEquals("text/plain", m.getMimeType());
		assertEquals("Hello,\r\n\r\nSee you there.\r\n", m.getText());
	}

	@Test
	public void testRoundTripIsByteIdentical() throws IOException {
		byte[] in = crlf(SIMPLE);
		assertArrayEquals(in, Message.parse(in).toByteArray());
		byte[] multi = crlf(MULTIPART);
		assertEquals(str(multi), str(Message.parse(multi).toByteArray()));
	}

	@Test
	public void testLfLineEndingsAreAccepted() {
		Message m = Message.parse(SIMPLE.getBytes(StandardCharsets.UTF_8));
		assertEquals("Café meeting", m.getSubject());
		assertEquals(2, m.getTo().size());
		// written with CRLF
		assertEquals(str(crlf(SIMPLE)), str(m.toByteArray()));
	}

	private static final String MULTIPART =
			"From: tony@bringardner.us\n"
			+ "To: john@example.com\n"
			+ "Subject: Report\n"
			+ "MIME-Version: 1.0\n"
			+ "Content-Type: multipart/mixed;\n"
			+ " boundary=\"outer\"\n"
			+ "\n"
			+ "This is a multi-part message in MIME format.\n"
			+ "--outer\n"
			+ "Content-Type: multipart/alternative; boundary=inner\n"
			+ "\n"
			+ "--inner\n"
			+ "Content-Type: text/plain; charset=UTF-8\n"
			+ "Content-Transfer-Encoding: quoted-printable\n"
			+ "\n"
			+ "Gr=C3=BC=C3=9Fe\n"
			+ "--inner\n"
			+ "Content-Type: text/html; charset=UTF-8\n"
			+ "\n"
			+ "<p>Grüße</p>\n"
			+ "--inner--\n"
			+ "\n"
			+ "--outer\n"
			+ "Content-Type: application/pdf; name=\"=?UTF-8?B?w6l0w6kucGRm?=\"\n"
			+ "Content-Disposition: attachment;\n"
			+ " filename*0*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E;\n"
			+ " filename*1*=%E3%81%AE%E5%A0%B1%E5%91%8A.pdf\n"
			+ "Content-Transfer-Encoding: base64\n"
			+ "\n"
			+ "JVBERi0xLjQK\n"
			+ "--outer--\n"
			+ "epilogue text\n";

	@Test
	public void testReadMultipart() {
		Message m = Message.parse(crlf(MULTIPART));
		assertTrue(m.isMultipart());
		assertEquals("This is a multi-part message in MIME format.", str(m.getPreamble()));
		assertEquals("epilogue text\r\n", str(m.getEpilogue()));
		assertEquals(2, m.getParts().size());

		Message alt = m.getParts().get(0);
		assertEquals("multipart/alternative", alt.getMimeType());
		assertEquals(2, alt.getParts().size());
		assertEquals("Grüße", alt.getParts().get(0).getText());
		assertEquals("<p>Grüße</p>", alt.getParts().get(1).getText());

		Message pdf = m.getParts().get(1);
		assertEquals("application/pdf", pdf.getMimeType());
		assertEquals("日本語の報告.pdf", pdf.getFilename());   // RFC 2231 Content-Disposition wins
		assertEquals("été.pdf", pdf.getContentType().getParameter("name"));
		assertEquals("%PDF-1.4\n", str(pdf.getContent()));
		assertTrue(pdf.isAttachment());

		List<Message> atts = m.getAttachments();
		assertEquals(1, atts.size());
		assertEquals("日本語の報告.pdf", atts.get(0).getFilename());
	}

	@Test
	public void testBuildAndReadBack() throws IOException, ParseException {
		byte[] pdf = new byte[3000];
		new Random(1).nextBytes(pdf);
		String longName = "Quarterly résumé of the Bringardner Java Library — very long file name 日本語.pdf";

		Message m = new Message();
		m.setFrom(new Address("Tony Bringardner", "tony", "bringardner.us"));
		m.setTo(new Address("Smith, John", "john", "example.com"), new Address("José Núñez", "jose", "example.com"));
		m.setSubject("Grüße — a subject long enough that it has to be folded across several header lines 日本語");
		m.setDate(Rfc2822Date.parseDate("Fri, 16 Jan 2026 07:54:55 -0500"));
		m.setMessageId("<1@bringardner.us>");
		m.setText("Héllo,\nthe report is attached.\n");
		Message att = m.addAttachment(longName, "application/pdf", pdf);
		m.addAttachment("notes.txt", "text/plain; charset=UTF-8", "plain notes\n".getBytes(StandardCharsets.UTF_8));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		m.writeTo(out);
		byte[] written = out.toByteArray();
		String text = new String(written, StandardCharsets.ISO_8859_1);

		// everything we generate is 7-bit ASCII with CRLF line endings and no long lines
		for (byte b : written) {
			assertTrue((b & 0xff) < 128, "non-ASCII byte in output");
		}
		assertFalse(text.replace("\r\n", "").contains("\n"), "bare LF in output");
		assertFalse(text.replace("\r\n", "").contains("\r"), "bare CR in output");
		String headerBlock = text.substring(0, text.indexOf("\r\n\r\n"));
		for (String line : headerBlock.split("\r\n")) {
			assertTrue(line.length() <= 78, line);
		}
		assertTrue(text.contains("filename*0*=UTF-8''"), "RFC 2231 continuation for the long file name");
		assertEquals(1, count(text, "MIME-Version: 1.0"));
		assertTrue(att.getHeader("MIME-Version") == null);

		Message back = Message.parse(written);
		assertEquals("Grüße — a subject long enough that it has to be folded across several header lines 日本語", back.getSubject());
		assertEquals("Fri, 16 Jan 2026 07:54:55 -0500", back.getDate().toString());
		assertEquals("Tony Bringardner", back.getFrom().get(0).getDisplayName());
		assertEquals(2, back.getTo().size());
		assertEquals("Smith, John", back.getTo().get(0).getDisplayName());
		assertEquals("José Núñez", back.getTo().get(1).getDisplayName());
		assertEquals("multipart/mixed", back.getMimeType());
		assertEquals(3, back.getParts().size());

		Message body = back.getParts().get(0);
		assertEquals("Héllo,\r\nthe report is attached.\r\n", body.getText());
		assertEquals("quoted-printable", body.getTransferEncoding());
		assertFalse(body.isAttachment());

		Message pdfPart = back.getParts().get(1);
		assertEquals(longName, pdfPart.getFilename());
		assertEquals(longName, pdfPart.getContentType().getParameter("name"));
		assertEquals("base64", pdfPart.getTransferEncoding());
		assertArrayEquals(pdf, pdfPart.getContent());

		Message notes = back.getParts().get(2);
		assertEquals("7bit", notes.getTransferEncoding());
		assertEquals("plain notes\r\n", notes.getText());
		assertEquals(2, back.getAttachments().size());

		// written again unchanged: identical
		assertArrayEquals(written, back.toByteArray());
	}

	@Test
	public void testAttachmentToExistingMultipartAlternative() {
		Message m = Message.parse(crlf(MULTIPART));
		Message alt = m.getParts().get(0);
		alt.addAttachment("x.bin", "application/octet-stream", new byte[] {1, 2, 3});
		// alternative isn't mixed, so it is wrapped
		assertEquals("multipart/mixed", alt.getMimeType());
		assertEquals("multipart/alternative", alt.getParts().get(0).getMimeType());
		assertEquals(2, alt.getParts().get(0).getParts().size());

		Message back = Message.parse(m.toByteArray());
		Message mixed = back.getParts().get(0);
		assertEquals(2, mixed.getParts().size());
		assertArrayEquals(new byte[] {1, 2, 3}, mixed.getParts().get(1).getContent());
		assertEquals("Grüße", mixed.getParts().get(0).getParts().get(0).getText());
	}

	@Test
	public void testAttachmentToEmptyMessage() {
		Message m = new Message();
		m.setSubject("only an attachment");
		m.addAttachment("a.txt", "text/plain", "abc".getBytes(StandardCharsets.US_ASCII));
		assertEquals(1, m.getParts().size());
		Message back = Message.parse(m.toByteArray());
		assertEquals(1, back.getParts().size());
		assertEquals("a.txt", back.getParts().get(0).getFilename());
	}

	@Test
	public void testHeaderEditing() {
		Message m = Message.parse(crlf(SIMPLE));
		m.setHeader("subject", "New subject");
		assertEquals("New subject", m.getSubject());
		assertEquals("Subject", m.getHeaders().get(3).getName().substring(0, 1).toUpperCase() + "ubject");
		m.addHeader("Received", "a");
		m.addHeader("Received", "b");
		assertEquals(List.of("a", "b"), m.getHeaders("RECEIVED"));
		m.removeHeader("received");
		assertTrue(m.getHeaders("Received").isEmpty());

		// header injection: line breaks in a value become spaces
		m.setHeader("X-Test", "one\r\nBcc: evil@example.com");
		String out = str(m.toByteArray());
		assertTrue(out.contains("X-Test: one Bcc: evil@example.com\r\n"), out);

		// a changed folded header is folded again; unchanged ones keep their folding
		assertTrue(out.contains("To: \"Smith, John\" <john@example.com>,\r\n  jane@example.com (Jane)\r\n"), out);
	}

	@Test
	public void testFold() {
		String line = "Subject: " + "word ".repeat(40).trim();
		String folded = Message.fold(line);
		for (String l : folded.split("\r\n")) {
			assertTrue(l.length() <= 78, l);
		}
		assertEquals(line, folded.replace("\r\n", ""));
		// a long run without whitespace is left alone
		String unbreakable = "X-Long: " + "x".repeat(100);
		assertEquals(unbreakable, Message.fold(unbreakable));
	}

	@Test
	public void testMboxFromLineAndMissingBlankLine() {
		Message m = Message.parse(crlf("From tony@bringardner.us Fri Jan 16 12:33:18 2026\nSubject: hi\nthis is the body\n"));
		assertEquals("hi", m.getSubject());
		assertEquals(1, m.getHeaders().size());
		assertEquals("this is the body\r\n", m.getText());
	}

	@Test
	public void testNoHeaders() {
		Message m = Message.parse(crlf("\nbody only\n"));
		assertTrue(m.getHeaders().isEmpty());
		assertEquals("body only\r\n", m.getText());
		assertEquals("text/plain", m.getMimeType());
		assertEquals("us-ascii", m.getContentType().getParameter("charset"));
	}

	@Test
	public void testMultipartWithoutClosingBoundary() {
		Message m = Message.parse(crlf("Content-Type: multipart/mixed; boundary=b\n\n--b\n\npart one\n--b\n\npart two\n"));
		assertEquals(2, m.getParts().size());
		assertEquals("part one", m.getParts().get(0).getText());
		assertEquals("part two\r\n", m.getParts().get(1).getText());
	}

	@Test
	public void testBoundaryPrefixIsNotADelimiter() {
		Message m = Message.parse(crlf("Content-Type: multipart/mixed; boundary=b\n\n--b\n\n--bogus line\n--b--\n"));
		assertEquals(1, m.getParts().size());
		assertEquals("--bogus line", m.getParts().get(0).getText());
	}

	@Test
	public void testMultipartWithNoBoundariesKeepsBody() {
		Message m = Message.parse(crlf("Content-Type: multipart/mixed; boundary=b\n\nno parts here\n"));
		assertTrue(m.getParts().isEmpty());
		assertEquals("no parts here\r\n", str(m.getBody()));
	}

	@Test
	public void testLatin1Headers() {
		byte[] data = "Subject: café\r\n\r\nx".getBytes(StandardCharsets.ISO_8859_1);
		assertEquals("café", Message.parse(data).getSubject());
	}

	@Test
	public void testSetTextAscii() {
		Message m = new Message().setText("plain\nascii");
		assertEquals("7bit", m.getTransferEncoding());
		assertEquals("us-ascii", m.getContentType().getParameter("charset"));
		assertEquals("plain\r\nascii", str(m.getBody()));
		m.setText("<b>x</b>", "html");
		assertEquals("text/html", m.getMimeType());
		// setText again doesn't duplicate MIME-Version
		assertEquals(1, m.getHeaders("MIME-Version").size());
	}

	@Test
	public void testSerializable() throws Exception {
		Message m = Message.parse(crlf(MULTIPART));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(m);
		}
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			Message back = (Message) in.readObject();
			assertArrayEquals(m.toByteArray(), back.toByteArray());
		}
	}

	@Test
	public void testAddressToString() {
		assertEquals("tony@bringardner.us", new Address("tony", "bringardner.us").toString());
		assertEquals("Tony Bringardner <tony@bringardner.us>", new Address("Tony Bringardner", "tony", "bringardner.us").toString());
		assertEquals("\"Smith, John\" <j@x.com>", new Address("Smith, John", "j", "x.com").toString());
		String enc = new Address("José", "j", "x.com").toString();
		assertTrue(enc.startsWith("=?UTF-8?B?"), enc);
		assertEquals("José", Address.parseAddress(enc).getDisplayName());
		List<Address> group = Address.parseAddressList("Team: a@x.com, b@x.com;, c@x.com, undisclosed-recipients:;");
		assertEquals(3, group.size());
		assertNull(Address.parseAddressList(null).isEmpty() ? null : "x");
	}

	private static int count(String text, String find) {
		int n = 0;
		for (int i = text.indexOf(find); i >= 0; i = text.indexOf(find, i + 1)) {
			n++;
		}
		return n;
	}
}
