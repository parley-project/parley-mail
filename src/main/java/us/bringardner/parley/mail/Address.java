package us.bringardner.parley.mail;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC 5322                Internet Message Format             October 2008
 * 
 * 3.4.  Address Specification

   Addresses occur in several message header fields to indicate senders
   and recipients of messages.  An address may either be an individual
   mailbox, or a group of mailboxes.

   address         =   mailbox / group
   mailbox         =   name-addr / addr-spec
   name-addr       =   [display-name] angle-addr
   angle-addr      =   [CFWS] "<" addr-spec ">" [CFWS] /
                       obs-angle-addr
   group           =   display-name ":" [group-list] ";" [CFWS]
   display-name    =   phrase
   mailbox-list    =   (mailbox *("," mailbox)) / obs-mbox-list
   address-list    =   (address *("," address)) / obs-addr-list
   group-list      =   mailbox-list / CFWS / obs-group-list
   
 * 
 * 3.4.1.  Addr-Spec Specification
 * 
 * addr-spec       =   local-part "@" domain
 *
 * local-part      =   dot-atom / quoted-string / obs-local-part
 *
 *  domain          =   dot-atom / domain-literal / obs-domain
 *
 * domain-literal  =   [CFWS] "[" *([FWS] dtext) [FWS] "]" [CFWS]
 *
 * dtext           =   %d33-90 /          ; Printable US-ASCII
 *                      %d94-126 /         ;  characters not including
 *                      obs-dtext          ;  "[", "]", or "\"
 */
public class Address implements Serializable {

	private static final long serialVersionUID = 1L;
	
	static String rx = "(?<user>[a-zA-Z0-9._%+-]+)@(?<domain>[a-zA-Z0-9.-]+)";
	
	
	public static Address parseAddress(String addressText) {
		Address ret = new Address();
		ret.parse(addressText);
		return ret;
	}
	
	private void parse(String addressText1) {
		String addressText = addressText1;
		
		// the last '<' so a display name like "a <b" in quotes doesn't confuse it
		int idx = addressText.lastIndexOf('<');
		if( idx >= 0 ) {
			String tmp = addressText.substring(0,idx).trim();
			if( !tmp.isEmpty()) {
				displayName=decodeDisplayName(tmp);
			}
			addressText = addressText.substring(idx+1);
			idx = addressText.indexOf('>');
			if( idx >= 0 ) {
				addressText = addressText.substring(0,idx).trim();
			}
		}
		
		// The domain can't contain '@' but a quoted local part can ("a@b"@example.com),
		// so split on the last one.
		idx = addressText.lastIndexOf('@');
		if( idx > 0 ) {
			user = addressText.substring(0,idx).trim();
			domain = addressText.substring(idx+1).trim();
		}
	}
	
	
	/**
	 * Parse an address list such as a To or Cc header value:
	 * {@code "Smith, John" <john@example.com>, tony@bringardner.us}.
	 * Commas inside quotes, angle brackets or comments don't split addresses,
	 * comments are dropped, and group syntax ({@code Team: a@x, b@y;}) yields
	 * the group's members. Entries without an '@' are skipped.
	 */
	public static List<Address> parseAddressList(String text) {
		List<Address> ret = new ArrayList<>();
		if( text == null ) {
			return ret;
		}
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		int angle = 0;
		int paren = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if( quoted ) {
				cur.append(c);
				if( c == '\\' && i + 1 < text.length()) {
					cur.append(text.charAt(++i));
				} else if( c == '"') {
					quoted = false;
				}
			} else if( paren > 0 ) {
				if( c == '\\') {
					i++;
				} else if( c == '(') {
					paren++;
				} else if( c == ')') {
					paren--;
				}
			} else if( c == '(') {
				paren++;
			} else if( c == '"') {
				quoted = true;
				cur.append(c);
			} else if( c == '<') {
				angle++;
				cur.append(c);
			} else if( c == '>') {
				angle = Math.max(0, angle - 1);
				cur.append(c);
			} else if( angle == 0 && (c == ',' || c == ';')) {
				addIfValid(ret, cur);
			} else if( angle == 0 && c == ':') {
				// group display name: drop it, the members follow
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		addIfValid(ret, cur);
		return ret;
	}

	private static void addIfValid(List<Address> list, StringBuilder text) {
		String s = text.toString().trim();
		text.setLength(0);
		if( !s.isEmpty()) {
			Address a = parseAddress(s);
			if( a.user != null ) {
				list.add(a);
			}
		}
	}

	private static String decodeDisplayName(String name) {
		if( name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i < name.length() - 1; i++) {
				char c = name.charAt(i);
				if( c == '\\' && i + 1 < name.length() - 1) {
					c = name.charAt(++i);
				}
				sb.append(c);
			}
			// RFC 2047 forbids encoded words inside quotes, but many clients use them
			return EncodedWord.decode(sb.toString());
		}
		return EncodedWord.decode(name);
	}

	private static final String PHRASE_SAFE = "!#$%&'*+-/=?^_`{|}~ ";

	/**
	 * The address in header form: {@code user@domain}, or
	 * {@code Display Name <user@domain>}. A display name with special characters
	 * is quoted; one with non-ASCII characters is written as RFC 2047 encoded words.
	 */
	@Override
	public String toString() {
		return format(false);
	}

	/**
	 * The address in RFC 6532 form: like {@link #toString()}, but a non-ASCII
	 * display name is written as UTF-8 (quoted if needed) instead of encoded words.
	 */
	public String toUtf8String() {
		return format(true);
	}

	private String format(boolean utf8) {
		String addr = (user == null ? "" : user) + "@" + (domain == null ? "" : domain);
		if( user == null && domain == null ) {
			addr = "";
		}
		if( displayName == null || displayName.trim().isEmpty()) {
			return addr;
		}
		String name = java.text.Normalizer.normalize(displayName, java.text.Normalizer.Form.NFC);
		String phrase;
		if( !utf8 && EncodedWord.needsEncoding(name)) {
			phrase = EncodedWord.encode(name);
		} else {
			boolean plain = true;
			for (int i = 0; i < name.length() && plain; i++) {
				char c = name.charAt(i);
				plain = Character.isLetterOrDigit(c) || PHRASE_SAFE.indexOf(c) >= 0;
			}
			phrase = plain ? name : "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		return addr.isEmpty() ? phrase : phrase + " <" + addr + ">";
	}

	protected String displayName;
	// localPart is more commonly refereed to as user
	protected String user;
	protected String domain;
	
	
	
	Address() {		
	}
	
	public Address(String displayName, String user, String domain) {
		this(user,domain);
		this.displayName = displayName;
	}

	public Address(String user, String domain) {
		this.user = user;
		this.domain = domain;
	}
	
	public Address(String addressText) {
		parse(addressText);
	}
	
	public String getDisplayName() {
		return displayName;
	}
	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}
	public String getUser() {
		return user;
	}
	public void setUser(String user) {
		this.user = user;
	}
	public String getDomain() {
		return domain;
	}
	public void setDomain(String domain) {
		this.domain = domain;
	}

	public static Address parseAddressx(String addressText1) {
		Address ret = new Address();
		String addressText = addressText1;
		
		String myrx=rx;
		int idx = addressText.indexOf('<');
		if( idx >= 0 ) {
			String tmp = addressText.substring(0,idx).trim();
			if( !tmp.isEmpty()) {
				ret.displayName=tmp;
			}
			addressText = addressText.substring(idx+1);
			idx = addressText.indexOf('>');
			if( idx >= 0 ) {
				addressText = addressText.substring(0,idx).trim();
			}
		}
		
		Pattern p = Pattern.compile(myrx);
		Matcher m = p.matcher(addressText);
	
		if(m.matches()) {
			ret.user = m.group("user");
			ret.domain = m.group("domain");
		}
		
		return ret;
	}
	
	
	
}
