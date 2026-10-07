package us.bringardner.parley.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** RFC 6858 surrogate messages. */
public class TestDowngrader {

	static final String UTF8_MESSAGE =
			"From: José Núñez <josé@exämple.com>\r\n"
			+ "To: Tony <tony@bringardner.us>, 日本 <用户@例子.广告>, Zoë <zoe@example.com>\r\n"
			+ "Return-Path: <josé@exämple.com>\r\n"
			+ "Subject: Grüße 日本語\r\n"
			+ "Message-ID: <abc@exämple.com>\r\n"
			+ "Received: from exämple.com by bringardner.us\r\n"
			+ "X-Note: naïve\r\n"
			+ "Date: Fri, 02 Oct 2026 15:00:00 -0400\r\n"
			+ "MIME-Version: 1.0\r\n"
			+ "Content-Type: multipart/mixed; boundary=b\r\n"
			+ "\r\n"
			+ "--b\r\n"
			+ "Content-Type: text/plain; charset=UTF-8\r\n"
			+ "Content-Transfer-Encoding: 8bit\r\n"
			+ "\r\n"
			+ "Hällo, the body stays as it is\r\n"
			+ "--b\r\n"
			+ "Content-Type: application/pdf; name=\"résumé.pdf\"\r\n"
			+ "Content-Disposition: attachment; filename=\"résumé.pdf\"\r\n"
			+ "Content-Description: Lebenslauf für José\r\n"
			+ "\r\n"
			+ "%PDF\r\n"
			+ "--b--\r\n";

	private static boolean headersAscii(byte[] message) {
		String text = new String(message, StandardCharsets.UTF_8);
		// every header block: the message header and each part header
		for (String block : text.split("\r\n--b\r\n|\r\n--b--")) {
			int end = block.indexOf("\r\n\r\n");
			String headers = end < 0 ? block : block.substring(0, end);
			if (headers.startsWith("--b\r\n")) {
				headers = headers.substring(5);
			}
			if (!headers.chars().allMatch(c -> c < 128)) {
				return false;
			}
		}
		return true;
	}

	@Test
	public void testNeedsUtf8() {
		assertTrue(Downgrader.needsUtf8(Message.parse(UTF8_MESSAGE.getBytes(StandardCharsets.UTF_8))));
		assertFalse(Downgrader.needsUtf8(Message.parse("Subject: =?UTF-8?B?w6k=?=\r\n\r\nbödy\r\n".getBytes(StandardCharsets.UTF_8))),
				"8-bit body text alone doesn't need UTF-8 mode");
		// only a part header is UTF-8
		assertTrue(Downgrader.needsUtf8(Message.parse(("Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n"
				+ "Content-Disposition: attachment; filename=\"é.txt\"\r\n\r\nx\r\n--b--\r\n").getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	public void testSurrogate() {
		Message m = Message.parse(UTF8_MESSAGE.getBytes(StandardCharsets.UTF_8));
		assertTrue(Downgrader.downgrade(m));
		assertFalse(Downgrader.needsUtf8(m));
		byte[] out = m.toByteArray();
		assertTrue(headersAscii(out), new String(out, StandardCharsets.UTF_8));

		Message back = Message.parse(out);
		assertEquals("Grüße 日本語", back.getSubject());

		Address from = back.getFrom().get(0);
		assertEquals("invalid", from.getUser());
		assertEquals("internationalized-address.invalid", from.getDomain());
		assertEquals("José Núñez <josé@exämple.com>", from.getDisplayName());

		List<Address> to = back.getTo();
		assertEquals(3, to.size());
		assertEquals("tony@bringardner.us", to.get(0).getUser() + "@" + to.get(0).getDomain());
		assertEquals("invalid", to.get(1).getUser());
		assertEquals("日本 <用户@例子.广告>", to.get(1).getDisplayName());
		assertEquals("zoe", to.get(2).getUser(), "an ASCII mailbox is kept");
		assertEquals("Zoë", to.get(2).getDisplayName(), "its display name is encoded");

		assertEquals("<invalid@internationalized-address.invalid>", back.getHeader("Return-Path"));
		assertNull(back.getHeader("Message-ID"), "removed: no ASCII form");
		assertNull(back.getHeader("Received"));
		assertEquals("naïve", EncodedWord.decode(back.getHeader("X-Note")));
		assertEquals("Fri, 02 Oct 2026 15:00:00 -0400", back.getHeader("Date"));

		Message text = back.getParts().get(0);
		assertEquals("Hällo, the body stays as it is", text.getText());
		Message pdf = back.getParts().get(1);
		assertEquals("résumé.pdf", pdf.getFilename());
		assertEquals("résumé.pdf", pdf.getContentType().getParameter("name"));
		assertEquals("Lebenslauf für José", EncodedWord.decode(pdf.getHeader("Content-Description")));
		assertEquals("%PDF", new String(pdf.getContent(), StandardCharsets.US_ASCII));
	}

	@Test
	public void testAsciiMessageIsUnchanged() {
		byte[] in = "From: a@b.c\r\nSubject: hi\r\n\r\nbödy\r\n".getBytes(StandardCharsets.UTF_8);
		Message m = Message.parse(in);
		assertFalse(Downgrader.downgrade(m));
		assertEquals(new String(in, StandardCharsets.UTF_8), new String(m.toByteArray(), StandardCharsets.UTF_8));
	}

	@Test
	public void testSurrogateAddress() {
		Address a = new Address("Fred Foo", "fred", "EXÄMPLE.com");
		Address s = Downgrader.surrogate(a);
		assertEquals("invalid", s.getUser());
		assertTrue(s.toString().startsWith("=?UTF-8?B?"), s.toString());
		Address ascii = new Address("Fred", "fred", "example.com");
		assertEquals(ascii, Downgrader.surrogate(ascii));
		assertEquals("fred@exämple.com <invalid@internationalized-address.invalid>",
				Downgrader.surrogate(new Address("fred", "exämple.com")).getDisplayName() + " <invalid@internationalized-address.invalid>");
	}
}
