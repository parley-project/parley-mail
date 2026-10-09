package us.bringardner.parley.mail.store;

import us.bringardner.parley.core.util.Hex;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import us.bringardner.parley.files.FileSource;

/**
 * One user's mailboxes. INBOX is the user's POP3 maildrop directory; every other
 * mailbox is a directory under {@code <inbox>/.mailboxes}, and a mailbox's
 * children are subdirectories of its directory. Each name segment is encoded so
 * names are case-sensitive and safe on any file system:
 * <ul>
 * <li>a-z, 0-9 and "-" are kept;</li>
 * <li>an upper case letter becomes "_" and the letter in lower case;</li>
 * <li>"_" becomes "__";</li>
 * <li>anything else becomes %XX for each of its UTF-8 bytes.</li>
 * </ul>
 * Encoded names never contain ".", so they can't clash with message files or the
 * hidden index files. POP3 ignores names starting with "." and directories, so
 * it sees only INBOX.
 */
public final class MailStore {

	public static final String MAILBOXES = ".mailboxes";
	public static final String SUBSCRIPTIONS = ".imap-subscriptions";
	public static final String NOSELECT_MARKER = ".imap-noselect";
	private static final int MAX_SEGMENT = 200;
	private static final int MAX_DEPTH = 32;

	/** Mailboxes created for a new user, with their special use (RFC 6154). */
	public static final String[][] DEFAULT_MAILBOXES = {
			{"Sent", MailboxConstants.SENT}, {"Drafts", MailboxConstants.DRAFTS}, {"Trash", MailboxConstants.TRASH}, {"Junk", MailboxConstants.JUNK},
			{"Archive", MailboxConstants.ARCHIVE}};

	/** A failed mailbox operation, with the response code to send (e.g. "NONEXISTENT"). */
	public static final class StoreException extends IOException {
		private static final long serialVersionUID = 1L;
		private final String code;

		public StoreException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String getCode() {
			return code;
		}
	}

	/** A mailbox name and what LIST says about it. */
	public static final class Entry {
		public final String name;
		public final FileSource dir;
		public final boolean noselect;
		public final boolean hasChildren;
		public final boolean noinferiors;
		public final String specialUse;

		Entry(String name, FileSource dir, boolean noselect, boolean hasChildren, boolean noinferiors, String specialUse) {
			this.name = name;
			this.dir = dir;
			this.noselect = noselect;
			this.hasChildren = hasChildren;
			this.noinferiors = noinferiors;
			this.specialUse = specialUse;
		}
	}

	private final FileSource inbox;
	private final MailboxRegistry registry;
	private final Object subscriptionLock = new Object();

	public MailStore(FileSource inbox, MailboxRegistry registry) {
		this.inbox = inbox;
		this.registry = registry;
	}

	public FileSource getInboxDirectory() {
		return inbox;
	}

	public MailboxRegistry getRegistry() {
		return registry;
	}

	/**
	 * Make the INBOX directory and, the first time, the default mailboxes (Sent,
	 * Drafts, Trash, Junk, Archive), all subscribed.
	 */
	public void init(boolean createDefaults) throws IOException {
		if (!inbox.exists() && !inbox.mkdirs()) {
			throw new IOException("Can't create " + inbox.getAbsolutePath());
		}
		FileSource root = inbox.getChild(MAILBOXES);
		if (!root.exists()) {
			root.mkdirs();
			if (createDefaults) {
				List<String> subscribed = new ArrayList<>();
				subscribed.add(MailboxConstants.INBOX);
				for (String[] d : DEFAULT_MAILBOXES) {
					FileSource dir = root.getChild(encodeSegment(d[0]));
					dir.mkdirs();
					Mailbox m = registry.acquire(dir);
					try {
						m.setSpecialUse(d[1]);
					} finally {
						registry.release(m);
					}
					subscribed.add(d[0]);
				}
				writeSubscriptions(subscribed);
			}
		}
	}

	// ------------------------------------------------------------------ names

	/**
	 * The canonical form of a mailbox name: NFC, "INBOX" in any case becomes
	 * "INBOX", no trailing delimiter.
	 */
	public static String normalize(String name) {
		String n = Normalizer.normalize(name, Normalizer.Form.NFC);
		while (n.length() > 1 && n.endsWith(String.valueOf(MailboxConstants.DELIMITER))) {
			n = n.substring(0, n.length() - 1);
		}
		if (n.equalsIgnoreCase(MailboxConstants.INBOX)) {
			return MailboxConstants.INBOX;
		}
		if (n.length() > 6 && n.substring(0, 6).equalsIgnoreCase(MailboxConstants.INBOX + MailboxConstants.DELIMITER)) {
			n = MailboxConstants.INBOX + n.substring(5);
		}
		return n;
	}

	public static boolean isInbox(String name) {
		return MailboxConstants.INBOX.equals(normalize(name));
	}

	private static List<String> segments(String name) throws StoreException {
		if (name.isEmpty()) {
			throw new StoreException(MailboxConstants.CODE_CANNOT, "Empty mailbox name");
		}
		List<String> ret = new ArrayList<>();
		for (String s : name.split(String.valueOf(MailboxConstants.DELIMITER), -1)) {
			if (s.isEmpty()) {
				throw new StoreException(MailboxConstants.CODE_CANNOT, "Empty hierarchy level in the mailbox name");
			}
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c < 0x20 || c == 0x7f || c == '*' || c == '%') {
					throw new StoreException(MailboxConstants.CODE_CANNOT, "Invalid character in the mailbox name");
				}
			}
			ret.add(s);
		}
		if (ret.size() > MAX_DEPTH) {
			throw new StoreException(MailboxConstants.CODE_LIMIT, "Mailbox hierarchy too deep");
		}
		return ret;
	}

	public static String encodeSegment(String s) {
		StringBuilder b = new StringBuilder();
		for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
			int c = x & 0xff;
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
				b.append((char) c);
			} else if (c >= 'A' && c <= 'Z') {
				b.append('_').append((char) (c + 32));
			} else if (c == '_') {
				b.append("__");
			} else {
				b.append('%');
				Hex.appendUpper(b, c);
			}
		}
		return b.toString();
	}

	/** The name of an encoded segment, or null if it isn't one. */
	public static String decodeSegment(String s) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
				out.write(c);
			} else if (c == '_' && i + 1 < s.length()) {
				char n = s.charAt(++i);
				if (n == '_') {
					out.write('_');
				} else if (n >= 'a' && n <= 'z') {
					out.write(n - 32);
				} else {
					return null;
				}
			} else if (c == '%' && i + 2 < s.length()) {
				try {
					out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
				} catch (NumberFormatException e) {
					return null;
				}
				i += 2;
			} else {
				return null;
			}
		}
		return out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	/** The directory of a mailbox name (it may not exist). */
	public FileSource directory(String name) throws IOException {
		name = normalize(name);
		if (MailboxConstants.INBOX.equals(name)) {
			return inbox;
		}
		if (name.startsWith(MailboxConstants.INBOX + MailboxConstants.DELIMITER)) {
			throw new StoreException(MailboxConstants.CODE_CANNOT, "INBOX has no child mailboxes");
		}
		FileSource dir = inbox.getChild(MAILBOXES);
		for (String s : segments(name)) {
			String e = encodeSegment(s);
			if (e.length() > MAX_SEGMENT) {
				throw new StoreException(MailboxConstants.CODE_LIMIT, "Mailbox name too long");
			}
			dir = dir.getChild(e);
		}
		return dir;
	}

	/** True if the mailbox exists and can be selected. */
	public boolean exists(String name) throws IOException {
		FileSource dir = directory(name);
		return dir.isDirectory() && (isInbox(name) || !dir.getChild(NOSELECT_MARKER).exists());
	}

	/** Exists, selectable or not. */
	private boolean existsAny(FileSource dir) throws IOException {
		return dir.isDirectory();
	}

	/** Open a selectable mailbox; release it with {@code getRegistry().release()}. */
	public Mailbox open(String name) throws IOException {
		if (!exists(name)) {
			throw new StoreException(MailboxConstants.CODE_NONEXISTENT, "No such mailbox");
		}
		return registry.acquire(directory(name));
	}

	private static List<FileSource> childDirs(FileSource dir) throws IOException {
		List<FileSource> ret = new ArrayList<>();
		FileSource[] list = dir.listFiles();
		if (list != null) {
			for (FileSource f : list) {
				String n = f.getName();
				if (!n.startsWith(".") && n.indexOf('.') < 0 && f.isDirectory() && decodeSegment(n) != null) {
					ret.add(f);
				}
			}
		}
		ret.sort((a, b) -> a.getName().compareTo(b.getName()));
		return ret;
	}

	public boolean hasChildren(String name) throws IOException {
		if (isInbox(name)) {
			return false;
		}
		FileSource dir = directory(name);
		return dir.isDirectory() && !childDirs(dir).isEmpty();
	}

	/** Every mailbox, INBOX first, parents before children. */
	public List<Entry> listAll() throws IOException {
		List<Entry> ret = new ArrayList<>();
		ret.add(new Entry(MailboxConstants.INBOX, inbox, false, false, true, null));
		FileSource root = inbox.getChild(MAILBOXES);
		if (root.isDirectory()) {
			walk(root, "", ret, 0);
		}
		return ret;
	}

	private void walk(FileSource dir, String prefix, List<Entry> out, int depth) throws IOException {
		if (depth >= MAX_DEPTH) {
			return;
		}
		for (FileSource child : childDirs(dir)) {
			String seg = decodeSegment(child.getName());
			String name = prefix + seg;
			if (MailboxConstants.INBOX.equalsIgnoreCase(name)) {
				continue; // can't be reached by name
			}
			List<FileSource> grand = childDirs(child);
			boolean noselect = child.getChild(NOSELECT_MARKER).exists();
			out.add(new Entry(name, child, noselect, !grand.isEmpty(), false,
					noselect ? null : Mailbox.readSpecialUse(child)));
			walk(child, name + MailboxConstants.DELIMITER, out, depth + 1);
		}
	}

	/** The LIST entry of one name, or null if it doesn't exist. */
	public Entry entry(String name) throws IOException {
		name = normalize(name);
		if (MailboxConstants.INBOX.equals(name)) {
			return new Entry(MailboxConstants.INBOX, inbox, false, false, true, null);
		}
		FileSource dir;
		try {
			dir = directory(name);
		} catch (StoreException e) {
			return null;
		}
		if (!dir.isDirectory()) {
			return null;
		}
		boolean noselect = dir.getChild(NOSELECT_MARKER).exists();
		return new Entry(name, dir, noselect, !childDirs(dir).isEmpty(), false, noselect ? null : Mailbox.readSpecialUse(dir));
	}

	// ------------------------------------------------------------------ changes

	/**
	 * CREATE: make the mailbox and any missing parents. An existing \Noselect
	 * mailbox becomes selectable.
	 */
	public void create(String name, String specialUse) throws IOException {
		name = normalize(name);
		if (MailboxConstants.INBOX.equals(name)) {
			throw new StoreException(MailboxConstants.CODE_ALREADYEXISTS, "INBOX always exists");
		}
		FileSource dir = directory(name);
		if (dir.isDirectory()) {
			FileSource marker = dir.getChild(NOSELECT_MARKER);
			if (!marker.exists()) {
				throw new StoreException(MailboxConstants.CODE_ALREADYEXISTS, "Mailbox already exists");
			}
			marker.delete();
		} else if (!dir.mkdirs()) {
			throw new StoreException(MailboxConstants.CODE_CANNOT, "Can't create the mailbox");
		}
		Mailbox m = registry.acquire(dir);
		try {
			if (specialUse != null) {
				m.setSpecialUse(specialUse);
			}
		} finally {
			registry.release(m);
		}
	}

	/**
	 * DELETE: a mailbox with children keeps its directory and becomes \Noselect
	 * (RFC 9051 section 6.3.4); others are removed.
	 */
	public void delete(String name) throws IOException {
		name = normalize(name);
		if (MailboxConstants.INBOX.equals(name)) {
			throw new StoreException(MailboxConstants.CODE_CANNOT, "INBOX can't be deleted");
		}
		FileSource dir = directory(name);
		if (!existsAny(dir)) {
			throw new StoreException(MailboxConstants.CODE_NONEXISTENT, "No such mailbox");
		}
		boolean children = !childDirs(dir).isEmpty();
		if (children && dir.getChild(NOSELECT_MARKER).exists()) {
			throw new StoreException(MailboxConstants.CODE_HASCHILDREN, "The mailbox has child mailboxes");
		}
		registry.invalidate(dir);
		if (children) {
			FileSource[] list = dir.listFiles();
			if (list != null) {
				for (FileSource f : list) {
					if (f.isFile() || f.getName().equals(Mailbox.CACHE_DIR)) {
						deleteTree(f);
					}
				}
			}
			try (OutputStream out = dir.getChild(NOSELECT_MARKER).getOutputStream()) {
				out.write('\n');
			}
		} else if (!deleteTree(dir)) {
			throw new StoreException(MailboxConstants.CODE_CANNOT, "Can't delete the mailbox");
		}
		removeSubscription(name, false);
	}

	/**
	 * RENAME (RFC 9051 section 6.3.6). Renaming INBOX moves its messages to the new
	 * mailbox and leaves INBOX empty.
	 */
	public void rename(String from, String to) throws IOException {
		from = normalize(from);
		to = normalize(to);
		if (MailboxConstants.INBOX.equals(to)) {
			throw new StoreException(MailboxConstants.CODE_ALREADYEXISTS, "INBOX already exists");
		}
		FileSource target = directory(to);
		if (target.exists()) {
			throw new StoreException(MailboxConstants.CODE_ALREADYEXISTS, "The new name already exists");
		}
		if (MailboxConstants.INBOX.equals(from)) {
			create(to, null);
			Mailbox src = registry.acquire(inbox);
			Mailbox dst = registry.acquire(target);
			try {
				src.refresh(true);
				src.moveTo(dst, src.getMessages(), null);
			} finally {
				registry.release(dst);
				registry.release(src);
			}
			return;
		}
		FileSource dir = directory(from);
		if (!existsAny(dir)) {
			throw new StoreException(MailboxConstants.CODE_NONEXISTENT, "No such mailbox");
		}
		String fromPrefix = from + MailboxConstants.DELIMITER;
		if (to.startsWith(fromPrefix)) {
			throw new StoreException(MailboxConstants.CODE_CANNOT, "A mailbox can't be moved under itself");
		}
		FileSource parent = target.getParentFile();
		if (parent != null && !parent.exists()) {
			// create the missing superiors as normal mailboxes
			int i = to.lastIndexOf(MailboxConstants.DELIMITER);
			create(to.substring(0, i), null);
		}
		registry.invalidate(dir);
		if (!dir.renameTo(target)) {
			throw new StoreException(MailboxConstants.CODE_CANNOT, "Can't rename the mailbox");
		}
		// subscriptions follow the mailbox and its children
		synchronized (subscriptionLock) {
			List<String> subs = readSubscriptions();
			List<String> next = new ArrayList<>();
			boolean changed = false;
			for (String s : subs) {
				if (s.equals(from)) {
					next.add(to);
					changed = true;
				} else if (s.startsWith(fromPrefix)) {
					next.add(to + s.substring(from.length()));
					changed = true;
				} else {
					next.add(s);
				}
			}
			if (changed) {
				writeSubscriptions(next);
			}
		}
	}

	static boolean deleteTree(FileSource f) throws IOException {
		if (f.isDirectory()) {
			FileSource[] list = f.listFiles();
			if (list != null) {
				for (FileSource c : list) {
					deleteTree(c);
				}
			}
		}
		return f.delete() || !f.exists();
	}

	// ------------------------------------------------------------------ subscriptions

	public List<String> readSubscriptions() throws IOException {
		synchronized (subscriptionLock) {
			FileSource f = inbox.getChild(SUBSCRIPTIONS);
			List<String> ret = new ArrayList<>();
			if (!f.exists()) {
				return ret;
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (!line.isEmpty() && !ret.contains(line)) {
						ret.add(line);
					}
				}
			}
			return ret;
		}
	}

	private void writeSubscriptions(List<String> names) throws IOException {
		FileSource tmp = inbox.getChild(SUBSCRIPTIONS + ".tmp");
		try (OutputStream out = tmp.getOutputStream()) {
			for (String n : names) {
				out.write((n + "\n").getBytes(StandardCharsets.UTF_8));
			}
		}
		FileSource f = inbox.getChild(SUBSCRIPTIONS);
		if (f.exists()) {
			f.delete();
		}
		if (!tmp.renameTo(f)) {
			throw new IOException("Can't save the subscriptions");
		}
	}

	/** SUBSCRIBE: a name may be subscribed whether or not it exists. */
	public void subscribe(String name) throws IOException {
		name = normalize(name);
		segments(name);
		synchronized (subscriptionLock) {
			List<String> subs = readSubscriptions();
			if (!subs.contains(name)) {
				subs.add(name);
				writeSubscriptions(subs);
			}
		}
	}

	/** UNSUBSCRIBE; unsubscribing a name that isn't subscribed is not an error (RFC 9051 section 6.3.8). */
	public void unsubscribe(String name) throws IOException {
		removeSubscription(normalize(name), false);
	}

	private void removeSubscription(String name, boolean mustExist) throws IOException {
		synchronized (subscriptionLock) {
			List<String> subs = readSubscriptions();
			if (subs.remove(name)) {
				writeSubscriptions(subs);
			} else if (mustExist) {
				throw new StoreException(MailboxConstants.CODE_NONEXISTENT, "Not subscribed");
			}
		}
	}

	public Set<String> subscriptionSet() throws IOException {
		return Collections.unmodifiableSet(new LinkedHashSet<>(readSubscriptions()));
	}

	@Override
	public String toString() {
		return "MailStore[" + inbox.getAbsolutePath() + "]";
	}
}
