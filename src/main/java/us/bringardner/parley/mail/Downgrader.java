package us.bringardner.parley.mail;

import java.text.Normalizer;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Set;

/**
 * Makes the "surrogate message" of RFC 6858 (Simplified POP and IMAP
 * Downgrading for Internationalized Email): a message whose headers are all
 * ASCII, for a client that doesn't support UTF-8 headers (RFC 6532).
 * <p>
 * Every header field, in the message header and in every MIME body part header,
 * that isn't plain ASCII is replaced:
 * <ul>
 * <li>In address fields (From, To, Cc, Bcc, Reply-To, Sender, Return-Path and the
 *     Resent- fields), each address whose mailbox isn't ASCII (e.g.
 *     {@code josé@exämple.com}) becomes
 *     {@code "José <josé@exämple.com>" <invalid@internationalized-address.invalid>}
 *     (RFC 6858 section 2.1), the display name RFC 2047-encoded. Other addresses
 *     keep their mailbox; a non-ASCII display name is RFC 2047-encoded.</li>
 * <li>Subject (RFC 6858 section 2.3) and the other unstructured fields
 *     (Comments, Content-Description, Thread-Topic, X-*) are RFC 2047-encoded.</li>
 * <li>Content-Type and Content-Disposition parameters are RFC 2231-encoded.
 *     (RFC 6858 section 2.2 allows simply removing them; encoding keeps
 *     attachment names and is still plain MIME.)</li>
 * <li>Any other field that isn't ASCII is removed (RFC 6858 section 2.4).</li>
 * </ul>
 * Bodies are not changed, so a surrogate of a large message is still streamed
 * from its file.
 */
public final class Downgrader {

	/** The invalid mailbox that replaces an internationalized one (RFC 2606 .invalid). */
	public static final String INVALID_LOCAL_PART = "invalid";
	public static final String INVALID_DOMAIN = "internationalized-address.invalid";

	private static final Set<String> ADDRESS_HEADERS = Set.of("bcc", "cc", "from", "reply-to", "resent-bcc",
			"resent-cc", "resent-from", "resent-sender", "resent-to", "return-path", "sender", "to");
	private static final Set<String> UNSTRUCTURED_HEADERS = Set.of("subject", "comments", "content-description",
			"thread-topic");

	private Downgrader() {
	}

	/** True if any header of the message or of its MIME parts has non-ASCII text. */
	public static boolean needsUtf8(Message message) {
		for (Header h : message.headers) {
			if (!isAscii(h.getName()) || !isAscii(h.getValue())) {
				return true;
			}
		}
		for (Message p : message.parts) {
			if (needsUtf8(p)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Turn the message (in place) into its surrogate and set it to ASCII header
	 * mode.
	 *
	 * @return true if anything was changed
	 */
	public static boolean downgrade(Message message) {
		boolean changed = downgradeHeaders(message);
		message.setUtf8Headers(false);
		return changed;
	}

	private static boolean downgradeHeaders(Message message) {
		boolean changed = false;
		for (ListIterator<Header> it = message.headers.listIterator(); it.hasNext();) {
			Header h = it.next();
			if (isAscii(h.getName()) && isAscii(h.getValue())) {
				continue;
			}
			changed = true;
			String value = isAscii(h.getName()) ? surrogateValue(h.getName(), h.getValue()) : null;
			if (value == null) {
				it.remove();
			} else {
				it.set(new Header(h.getName(), value));
			}
		}
		for (Message p : message.parts) {
			changed |= downgradeHeaders(p);
		}
		return changed;
	}

	/** The ASCII value for a header, or null if the header has to be removed. */
	static String surrogateValue(String name, String rawValue) {
		String n = name.toLowerCase(Locale.ROOT);
		String value = Normalizer.normalize(rawValue == null ? "" : rawValue, Normalizer.Form.NFC);
		String ret;
		if (ADDRESS_HEADERS.contains(n)) {
			ret = addresses(value, n.equals("return-path"));
		} else if (n.equals("content-type") || n.equals("content-disposition")) {
			MimeHeaderValue v = MimeHeaderValue.parse(value);
			ret = isAscii(v.getValue()) ? v.toString() : null;
		} else if (UNSTRUCTURED_HEADERS.contains(n) || n.startsWith("x-")) {
			ret = EncodedWord.encode(value, null, name.length() + 2);
		} else {
			ret = null; // RFC 6858 section 2.4
		}
		return ret != null && isAscii(ret) ? ret : null;
	}

	private static String addresses(String value, boolean returnPath) {
		List<Address> list = Address.parseAddressList(value);
		if (list.isEmpty()) {
			return null;
		}
		if (returnPath) {
			Address a = list.get(0);
			return "<" + (isAscii(a.getUser()) && isAscii(a.getDomain()) ? a.getUser() + "@" + a.getDomain()
					: INVALID_LOCAL_PART + "@" + INVALID_DOMAIN) + ">";
		}
		StringBuilder sb = new StringBuilder();
		for (Address a : list) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(surrogate(a).toString());
		}
		return sb.toString();
	}

	/** The address itself if its mailbox is ASCII, otherwise an invalid one naming it. */
	public static Address surrogate(Address a) {
		if (isAscii(a.getUser()) && isAscii(a.getDomain())) {
			return a;
		}
		String mailbox = a.getUser() + "@" + a.getDomain();
		String name = a.getDisplayName() == null || a.getDisplayName().trim().isEmpty() ? mailbox
				: a.getDisplayName() + " <" + mailbox + ">";
		return new Address(name, INVALID_LOCAL_PART, INVALID_DOMAIN);
	}

	static boolean isAscii(String s) {
		if (s == null) {
			return true;
		}
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) > 127) {
				return false;
			}
		}
		return true;
	}
}
