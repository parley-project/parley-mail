package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * RFC 6532 (Internationalized Email Headers).
 */
public class TestUtf8Headers {

	private static byte[] utf8(String text) {
		return text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
	}

	private static String str(byte[] b) {
		return new String(b, StandardCharsets.UTF_8);
	}

	private static String headerBlock(Message m) {
		String text = str(m.toByteArray());
		return text.substring(0, text.indexOf("\r\n\r\n"));
	}

	private static boolean isAscii(byte[] b) {
		for (byte x : b) {
			if (x < 0) {
				return false;
			}
		}
		return true;
	}

	private static final String UTF8_MESSAGE =
			"From: José Núñez <josé@exämple.com>\n"
			+ "To: 日本 <用户@例子.广告>\n"
			+ "Subject: Grüße 日本語\n"
			+ "Content-Type: text/plain; charset=UTF-8; name=\"résumé.txt\"\n"
			+ "\n"
			+ "body\n";

	@Test
	public void testReadUtf8Headers() {
		byte[] in = utf8(UTF8_MESSAGE);
		Message m = Message.parse(in);
		assertEquals("Grüße 日本語", m.getSubject());
		Address from = m.getFrom().get(0);
		assertEquals("José Núñez", from.getDisplayName());
		assertEquals("josé", from.getUser());
		assertEquals("exämple.com", from.getDomain());
		Address to = m.getTo().get(0);
		assertEquals("日本", to.getDisplayName());
		assertEquals("用户", to.getUser());
		assertEquals("例子.广告", to.getDomain());
		assertEquals("résumé.txt", m.getContentType().getParameter("name"));

		// parsed raw UTF-8 headers: the message keeps that style
		assertTrue(m.isUtf8Headers());
		assertTrue(m.needsSmtpUtf8());
		assertArrayEquals(in, m.toByteArray());

		// an edited header stays UTF-8 too
		m.setSubject("Neue Grüße");
		assertTrue(headerBlock(m).contains("Subject: Neue Grüße"));
	}

	@Test
	public void testAsciiMessageIsNotUtf8Mode() {
		Message m = Message.parse(utf8("Subject: =?UTF-8?B?R3LDvMOfZQ==?=\n\nx\n"));
		assertFalse(m.isUtf8Headers());
		assertFalse(m.needsSmtpUtf8());
		assertEquals("Grüße", m.getSubject());
	}

	@Test
	public void testDefaultModeWritesAscii() {
		Message m = new Message();
		m.setFrom(new Address("José Núñez", "jose", "example.com"));
		m.setTo(new Address("Smith, John", "john", "example.com"));
		m.setSubject("Grüße");
		m.setHeader("X-Note", "naïve");
		m.setHeader("Content-Description", "Beschreibung über alles");
		m.setText("x");
		m.addAttachment("résumé.pdf", "application/pdf", new byte[] {1});

		// stored as Unicode
		assertEquals("Grüße", m.getHeader("Subject"));
		byte[] out = m.toByteArray();
		assertTrue(isAscii(out), str(out));
		assertFalse(m.needsSmtpUtf8());
		String h = headerBlock(m);
		assertTrue(h.contains("Subject: =?UTF-8?B?"), h);
		assertTrue(h.contains("From: =?UTF-8?B?"), h);
		assertTrue(h.contains("To: \"Smith, John\" <john@example.com>"), h);
		assertTrue(str(out).contains("filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"), str(out));

		Message back = Message.parse(out);
		assertEquals("Grüße", back.getSubject());
		assertEquals("José Núñez", back.getFrom().get(0).getDisplayName());
		assertEquals("naïve", EncodedWord.decode(back.getHeader("X-Note")));
		assertEquals("résumé.pdf", back.getAttachments().get(0).getFilename());
	}

	@Test
	public void testUtf8ModeWritesRawUtf8() {
		Message m = new Message().setUtf8Headers(true);
		m.setFrom(new Address("José Núñez", "josé", "exämple.com"));
		m.setTo(new Address("Smith, John", "john", "example.com"));
		m.setSubject("Grüße 日本語");
		m.setText("x");
		m.addAttachment("résumé.pdf", "application/pdf", new byte[] {1});
		m.addAttachment("report.txt", "text/plain", new byte[] {'a'}).setContentDisposition(
				new MimeHeaderValue("attachment").setParameter("filename", "report.txt", "en"));

		String all = str(m.toByteArray());
		String h = headerBlock(m);
		assertTrue(h.contains("From: José Núñez <josé@exämple.com>"), h);
		assertTrue(h.contains("To: \"Smith, John\" <john@example.com>"), h);
		assertTrue(h.contains("Subject: Grüße 日本語"), h);
		assertTrue(all.contains("filename=\"résumé.pdf\""), all);
		// a language still needs RFC 2231
		assertTrue(all.contains("filename*=UTF-8'en'report.txt"), all);
		assertTrue(m.needsSmtpUtf8());

		Message back = Message.parse(m.toByteArray());
		assertEquals("Grüße 日本語", back.getSubject());
		assertEquals("josé", back.getFrom().get(0).getUser());
		assertEquals("résumé.pdf", back.getAttachments().get(0).getFilename());
		assertEquals("en", back.getAttachments().get(1).getContentDisposition().getParameterInfo("filename").getLanguage());

		// switching the same message to ASCII mode
		m.setUtf8Headers(false);
		String ascii = headerBlock(m);
		assertTrue(ascii.contains("Subject: =?UTF-8?B?"), ascii);
		// the mailbox itself has no ASCII form, so the message still needs SMTPUTF8
		assertTrue(ascii.contains("<josé@exämple.com>"), ascii);
		assertTrue(m.needsSmtpUtf8());
		m.setFrom(new Address("José Núñez", "jose", "example.com"));
		assertFalse(m.needsSmtpUtf8());
		assertTrue(isAscii(m.toByteArray()));
	}

	@Test
	public void testFoldingCountsOctets() {
		// 30 words of 3 Japanese characters: 9 octets + space each
		String subject = ("日本語 ").repeat(30).trim();
		Message m = new Message().setUtf8Headers(true);
		m.setSubject(subject);
		for (String line : headerBlock(m).split("\r\n")) {
			assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 78,
					line.getBytes(StandardCharsets.UTF_8).length + ": " + line);
		}
		assertEquals(subject, Message.parse(m.toByteArray()).getSubject());
	}

	@Test
	public void testLineLimitIs998Octets() {
		String unbreakable = "日".repeat(400); // 1200 octets, no whitespace
		Message m = new Message().setUtf8Headers(true);
		m.setSubject(unbreakable);
		m.setHeader("X-Long", unbreakable);
		for (String line : headerBlock(m).split("\r\n")) {
			assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 998, line);
		}
		Message back = Message.parse(m.toByteArray());
		assertEquals(unbreakable, back.getSubject());
		assertEquals(unbreakable, EncodedWord.decode(back.getHeader("X-Long")));
	}

	@Test
	public void testNfc() {
		String nfd = Normalizer.normalize("José Ångström", Normalizer.Form.NFD);
		String nfc = Normalizer.normalize(nfd, Normalizer.Form.NFC);
		Message m = new Message().setUtf8Headers(true);
		m.setSubject(nfd);
		m.setFrom(new Address(nfd, "a", "b.com"));
		String h = headerBlock(m);
		assertTrue(h.contains("Subject: " + nfc), h);
		assertTrue(h.contains("From: " + nfc + " <a@b.com>"), h);
		assertFalse(h.contains(nfd));
		// ASCII mode encodes the NFC form
		m.setUtf8Headers(false);
		assertEquals(nfc, Message.parse(m.toByteArray()).getSubject());
	}

	@Test
	public void testParseMessageGlobal() {
		String inner = "From: 用户@例子.广告\r\nSubject: 日本\r\n\r\ninner body\r\n";
		String b64 = Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encodeToString(inner.getBytes(StandardCharsets.UTF_8));
		byte[] in = utf8("Subject: fwd\n"
				+ "Content-Type: multipart/mixed; boundary=b\n"
				+ "\n"
				+ "--b\n"
				+ "Content-Type: text/plain\n"
				+ "\n"
				+ "see attached\n"
				+ "--b\n"
				+ "Content-Type: message/global\n"
				+ "Content-Transfer-Encoding: base64\n"
				+ "\n"
				+ b64 + "\n"
				+ "--b--\n");
		Message m = Message.parse(in);
		Message part = m.getParts().get(1);
		Message inside = part.getAttachedMessage();
		assertNotNull(inside);
		assertEquals("日本", inside.getSubject());
		assertEquals("用户", inside.getFrom().get(0).getUser());
		assertEquals("inner body\r\n", inside.getText());
		assertTrue(part.isAttachment());
		assertEquals(1, m.getAttachments().size());
		// UTF-8 inside message/global is body content: the outer message is plain ASCII
		assertFalse(m.needsSmtpUtf8());
		assertArrayEquals(in, m.toByteArray());

		// a change to the attached message is written out, re-encoded
		inside.setSubject("改");
		Message back = Message.parse(m.toByteArray());
		Message backInside = back.getParts().get(1).getAttachedMessage();
		assertEquals("改", backInside.getSubject());
		assertEquals("base64", back.getParts().get(1).getTransferEncoding());
		assertEquals("inner body\r\n", backInside.getText());
	}

	@Test
	public void testParseMessageRfc822() {
		byte[] in = utf8("Content-Type: multipart/mixed; boundary=b\n\n--b\n"
				+ "Content-Type: message/rfc822\n\n"
				+ "Subject: =?UTF-8?B?R3LDvMOfZQ==?=\n\nforwarded\n"
				+ "--b--\n");
		Message m = Message.parse(in);
		Message inside = m.getParts().get(0).getAttachedMessage();
		assertEquals("Grüße", inside.getSubject());
		assertEquals("forwarded", inside.getText());
		assertArrayEquals(in, m.toByteArray());
	}

	@Test
	public void testAttachMessage() {
		Message ascii = new Message();
		ascii.setSubject("Grüße");
		ascii.setText("hello");
		Message utf8 = new Message().setUtf8Headers(true);
		utf8.setSubject("Grüße");
		utf8.setText("hallo");

		Message m = new Message();
		m.setSubject("two forwarded messages");
		m.setText("see attached");
		Message p1 = m.attachMessage(ascii);
		Message p2 = m.attachMessage(utf8);
		assertEquals("message/rfc822", p1.getMimeType());
		assertEquals("message/global", p2.getMimeType());
		assertEquals("8bit", p2.getTransferEncoding());
		assertFalse(m.needsSmtpUtf8());

		Message back = Message.parse(m.toByteArray());
		assertEquals(3, back.getParts().size());
		assertEquals(2, back.getAttachments().size());
		assertEquals("Grüße", back.getParts().get(1).getAttachedMessage().getSubject());
		assertEquals("hello", back.getParts().get(1).getAttachedMessage().getText());
		assertEquals("Grüße", back.getParts().get(2).getAttachedMessage().getSubject());
		assertTrue(back.getParts().get(2).getAttachedMessage().isUtf8Headers());
	}

	@Test
	public void testDeepNestingDoesNotOverflow() {
		StringBuilder sb = new StringBuilder();
		int depth = 5000;
		for (int i = 0; i < depth; i++) {
			sb.append("Content-Type: multipart/mixed; boundary=b").append(i).append("\r\n\r\n--b").append(i).append("\r\n");
		}
		sb.append("\r\nleaf\r\n");
		Message m = Message.parse(sb.toString().getBytes(StandardCharsets.US_ASCII));
		assertEquals(1, m.getParts().size());
		m.toByteArray();
		// same for attached messages
		StringBuilder msg = new StringBuilder();
		for (int i = 0; i < depth; i++) {
			msg.append("Content-Type: message/rfc822\r\n\r\n");
		}
		Message m2 = Message.parse(msg.append("Subject: x\r\n\r\nend\r\n").toString().getBytes(StandardCharsets.US_ASCII));
		assertNotNull(m2.getAttachedMessage());
		m2.toByteArray();
	}

	@Test
	public void testAddressUtf8String() {
		assertEquals("José <j@x.com>", new Address("José", "j", "x.com").toUtf8String());
		assertEquals("\"Núñez, José\" <j@x.com>", new Address("Núñez, José", "j", "x.com").toUtf8String());
		assertTrue(new Address("José", "j", "x.com").toString().startsWith("=?UTF-8?B?"));
		assertNull(Address.parseAddress("nobody").getUser());
	}
}
