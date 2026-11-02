package us.bringardner.parley.mail.store;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.io.IoUtils;

/**
 * A mailbox's access control list (IMAP ACL, RFC 4314): which rights each identifier has.
 * It is kept in the mailbox's folder as {@value #FILE}, like {@code .imap-index}, one line
 * per identifier: the identifier, a TAB and its rights. A negative entry (rights taken away)
 * is written {@code -identifier}.
 * <ul>
 * <li>a user is named by the full login address ({@code bob@bringardner.us});</li>
 * <li>a group by {@code $} and its full name ({@code $sales@bringardner.us}), matching
 * {@link us.bringardner.parley.net.server.IPrincipal#getGroups()};</li>
 * <li>{@value #ANYONE} is every logged in user.</li>
 * </ul>
 * A mailbox with no file has only its owner's default rights ({@value #ALL}). The rights a
 * user has are the union of the entries that name the user, one of the user's groups or
 * {@value #ANYONE}, less the union of the negative ones. The owner always keeps
 * {@value #OWNER_ALWAYS} (seeing the mailbox and changing its ACL), so an owner can't be
 * locked out.
 * <p>
 * Objects are immutable: the changes return a new list. {@link MailStore} reads and writes
 * the file (under the user's tree lock), so IMAP and POP3 share it.
 *
 * <PRE>
 * Copyright 1998-2026 <A href="http://bringardner.us/tony">Tony Bringardner</A>
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 * @since 2.0.0
 */
public final class MailboxAcl {

	/** The file in the mailbox's folder. */
	public static final String FILE = ".imap-acl";
	/** The previous file, while a replace is under way on a file system that can't replace in one step. */
	static final String FILE_OLD = ".imap-acl.old";
	/** Every right, in the order they are written (RFC 4314 section 2.1). */
	public static final String ALL = "lrswipkxtea";
	/** The rights an owner always has on their own mailboxes. */
	public static final String OWNER_ALWAYS = "la";
	/** The identifier of every logged in user. */
	public static final String ANYONE = "anyone";
	/** Starts a group identifier. */
	public static final char GROUP = '$';
	/** Starts a negative entry. */
	public static final char NEGATIVE = '-';
	/** RFC 4314's rights not in RFC 2086, for the RIGHTS= capability. */
	public static final String NEW_RIGHTS = "texk";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final Map<String, String> entries;

	private MailboxAcl(Map<String, String> entries) {
		this.entries = Collections.unmodifiableMap(entries);
	}

	/** An empty list (no one has any rights but what the owner always keeps). */
	public static MailboxAcl empty() {
		return new MailboxAcl(new LinkedHashMap<>());
	}

	/** What a mailbox without a file has: the owner with every right. */
	public static MailboxAcl ownerDefault(String owner) {
		Map<String, String> m = new LinkedHashMap<>();
		if (owner != null) {
			m.put(owner, ALL);
		}
		return new MailboxAcl(m);
	}

	/** @return the entries in order (identifier, negative ones with their "-", to canonical rights) */
	public Map<String, String> entries() {
		return entries;
	}

	/** @return the rights of exactly this identifier (no groups), or null if it has no entry */
	public String get(String identifier) {
		return entries.get(identifier);
	}

	/**
	 * @param rights canonical or not; an empty string removes the entry
	 * @return a list with the identifier's entry replaced
	 */
	public MailboxAcl with(String identifier, String rights) {
		checkIdentifier(identifier);
		Map<String, String> m = new LinkedHashMap<>(entries);
		String r = canonical(rights);
		if (r.isEmpty()) {
			m.remove(identifier);
		} else {
			m.put(identifier, r);
		}
		return new MailboxAcl(m);
	}

	/** @return a list without the identifier's entry (a negative entry is its own identifier) */
	public MailboxAcl without(String identifier) {
		Map<String, String> m = new LinkedHashMap<>(entries);
		m.remove(identifier);
		return new MailboxAcl(m);
	}

	// ------------------------------------------------------------------ rights

	/**
	 * The rights in {@value #ALL}'s order without repeats. The obsolete rights of RFC 2086 are
	 * taken as RFC 4314 section 2.1.1 says: {@code c} as {@code k}, {@code d} as {@code xte}.
	 *
	 * @throws IllegalArgumentException for a right that isn't one (upper case included)
	 */
	public static String canonical(String rights) {
		if (rights == null) {
			return "";
		}
		boolean[] has = new boolean[ALL.length()];
		for (int i = 0; i < rights.length(); i++) {
			char c = rights.charAt(i);
			if (c == 'c') {
				has[ALL.indexOf('k')] = true;
			} else if (c == 'd') {
				has[ALL.indexOf('x')] = true;
				has[ALL.indexOf('t')] = true;
				has[ALL.indexOf('e')] = true;
			} else {
				int idx = ALL.indexOf(c);
				if (idx < 0) {
					throw new IllegalArgumentException("Not a right: " + c);
				}
				has[idx] = true;
			}
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < has.length; i++) {
			if (has[i]) {
				sb.append(ALL.charAt(i));
			}
		}
		return sb.toString();
	}

	/**
	 * The rights for a response (GETACL, MYRIGHTS): RFC 4314 section 2.1.1 says to add the
	 * obsolete {@code c} when {@code k} is there and {@code d} when one of {@code x t e} is, for
	 * older clients.
	 */
	public static String withObsolete(String rights) {
		String r = canonical(rights);
		StringBuilder sb = new StringBuilder(r);
		if (r.indexOf('k') >= 0) {
			sb.append('c');
		}
		if (r.indexOf('x') >= 0 || r.indexOf('t') >= 0 || r.indexOf('e') >= 0) {
			sb.append('d');
		}
		return sb.toString();
	}

	/**
	 * SETACL's change (RFC 4314 section 3.1): {@code +rights} adds, {@code -rights} takes away,
	 * anything else replaces.
	 *
	 * @param current the identifier's rights now (null for none)
	 * @return the canonical result
	 * @throws IllegalArgumentException for a right that isn't one
	 */
	public static String modify(String current, String change) {
		String now = canonical(current);
		if (change == null) {
			return now;
		}
		if (change.startsWith("+")) {
			return canonical(now + change.substring(1));
		}
		if (change.startsWith("-")) {
			String remove = canonical(change.substring(1));
			return minus(now, remove);
		}
		return canonical(change);
	}

	/** @return the rights of a that aren't in b, canonical */
	public static String minus(String a, String b) {
		String x = canonical(a);
		String y = canonical(b);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < x.length(); i++) {
			if (y.indexOf(x.charAt(i)) < 0) {
				sb.append(x.charAt(i));
			}
		}
		return sb.toString();
	}

	/** @return the rights in both, canonical */
	public static String intersect(String a, String b) {
		String x = canonical(a);
		String y = canonical(b);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < x.length(); i++) {
			if (y.indexOf(x.charAt(i)) >= 0) {
				sb.append(x.charAt(i));
			}
		}
		return sb.toString();
	}

	/** True if every right in want is in rights. */
	public static boolean has(String rights, String want) {
		for (int i = 0; i < want.length(); i++) {
			if (rights.indexOf(want.charAt(i)) < 0) {
				return false;
			}
		}
		return true;
	}

	/** True if any right in any is in rights. */
	public static boolean hasAny(String rights, String any) {
		for (int i = 0; i < any.length(); i++) {
			if (rights.indexOf(any.charAt(i)) >= 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The server's permissions are a ceiling on a mailbox's rights: with no READ permission
	 * nothing is allowed, with no WRITE permission only {@code l} and {@code r} (read only
	 * everywhere).
	 */
	public static String limit(String rights, boolean read, boolean write) {
		if (!read) {
			return "";
		}
		return write ? canonical(rights) : intersect(rights, "lr");
	}

	/**
	 * The rights a user has on a mailbox, before the server's permissions limit them.
	 *
	 * @param acl the mailbox's list, or null when it has no file (the owner's default)
	 * @param owner the mailbox's owner (full login address), or null for none
	 * @param user the user (full login address)
	 * @param groups the user's groups
	 */
	public static String rightsOf(MailboxAcl acl, String owner, String user, Collection<String> groups) {
		boolean isOwner = owner != null && owner.equals(user);
		if (acl == null) {
			return isOwner ? ALL : "";
		}
		String plus = "";
		String less = "";
		for (Map.Entry<String, String> e : acl.entries.entrySet()) {
			String id = e.getKey();
			boolean negative = id.length() > 1 && id.charAt(0) == NEGATIVE;
			String who = negative ? id.substring(1) : id;
			if (matches(who, user, groups)) {
				if (negative) {
					less = less + e.getValue();
				} else {
					plus = plus + e.getValue();
				}
			}
		}
		String ret = minus(plus, less);
		if (isOwner) {
			ret = canonical(ret + OWNER_ALWAYS);
		}
		return ret;
	}

	private static boolean matches(String identifier, String user, Collection<String> groups) {
		if (ANYONE.equals(identifier)) {
			return user != null;
		}
		if (identifier.length() > 1 && identifier.charAt(0) == GROUP) {
			return groups != null && groups.contains(identifier.substring(1));
		}
		return identifier.equals(user);
	}

	/**
	 * An identifier the file can hold: not empty, no TAB, CR or LF, and a group or a negative
	 * entry names something.
	 *
	 * @throws IllegalArgumentException if it isn't one
	 */
	public static void checkIdentifier(String identifier) {
		if (identifier == null || identifier.isEmpty()) {
			throw new IllegalArgumentException("An identifier can't be empty");
		}
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (c == '\t' || c == '\r' || c == '\n') {
				throw new IllegalArgumentException("An identifier can't hold a TAB or a line break");
			}
		}
		String who = identifier.charAt(0) == NEGATIVE ? identifier.substring(1) : identifier;
		if (who.isEmpty() || who.equals(String.valueOf(GROUP)) || who.charAt(0) == NEGATIVE) {
			throw new IllegalArgumentException("Not an identifier: " + identifier);
		}
	}

	// ------------------------------------------------------------------ the file

	/**
	 * Read a mailbox's list.
	 *
	 * @return the list, or null if the folder has none (the owner's default rights)
	 */
	public static MailboxAcl read(FileSource dir) throws IOException {
		FileSource f = dir.getChild(FILE);
		if (!f.exists()) {
			FileSource old = dir.getChild(FILE_OLD);
			if (!old.exists()) {
				return null;
			}
			f = old;
		}
		Map<String, String> m = new LinkedHashMap<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				int tab = line.indexOf('\t');
				if (tab <= 0) {
					continue; // damaged: ignored, like an index's unknown lines
				}
				String id = line.substring(0, tab);
				try {
					checkIdentifier(id);
					String rights = canonical(line.substring(tab + 1).trim());
					if (!rights.isEmpty()) {
						m.put(id, rights);
					}
				} catch (IllegalArgumentException e) {
					// a line this version can't read is left out
				}
			}
		} catch (FileNotFoundException gone) {
			return null;
		}
		return new MailboxAcl(m);
	}

	/** Write the list into the mailbox's folder, in one step where the file system can. */
	void write(FileSource dir) throws IOException {
		FileSource tmp = dir.getChild("." + FILE.substring(1) + "." + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE) + ".tmp");
		try {
			try (Writer w = new OutputStreamWriter(IoUtils.buffered(tmp.getOutputStream()), StandardCharsets.UTF_8)) {
				for (Map.Entry<String, String> e : entries.entrySet()) {
					w.write(e.getKey() + "\t" + e.getValue() + "\n");
				}
			}
			FileSource file = dir.getChild(FILE);
			if (dir.getCapabilities().atomicReplace()) {
				if (!tmp.renameTo(file, true)) {
					throw new IOException("Can't replace " + file.getAbsolutePath());
				}
			} else {
				FileSource old = dir.getChild(FILE_OLD);
				if (old.exists()) {
					old.delete();
				}
				if (file.exists() && !file.renameTo(old)) {
					throw new IOException("Can't replace " + file.getAbsolutePath());
				}
				if (!tmp.renameTo(file)) {
					old.renameTo(file);
					throw new IOException("Can't write " + file.getAbsolutePath());
				}
				if (old.exists()) {
					old.delete();
				}
			}
		} finally {
			if (tmp.exists()) {
				tmp.delete();
			}
		}
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof MailboxAcl && ((MailboxAcl) o).entries.equals(entries);
	}

	@Override
	public int hashCode() {
		return entries.hashCode();
	}

	@Override
	public String toString() {
		return entries.toString();
	}
}
