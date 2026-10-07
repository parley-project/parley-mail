package us.bringardner.parley.mail.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.Downgrader;
import us.bringardner.parley.mail.Message;

/**
 * An IMAP mailbox: a directory of message files (one message per file, as in the
 * POP3 maildrop) plus a hidden index, {@code .imap-index}, that keeps each
 * message's UID, flags and INTERNALDATE, and the mailbox's UIDVALIDITY and
 * UIDNEXT (RFC 9051 section 2.3.1.1).
 * <p>
 * One Mailbox object exists per directory in a server (see
 * {@link MailboxRegistry}); every session that selects the mailbox shares it and
 * is told about changes through its {@link MailboxView}. Files added or removed
 * by others (POP3, local delivery) are found by {@link #refresh(boolean)}.
 * Files are kept with CRLF line ends, so stored sizes are the sizes IMAP sends.
 * Names starting with "." are ignored, so a message being written is never seen
 * half written.
 */
public final class Mailbox {

	public static final String INDEX = ".imap-index";
	static final String INDEX_TMP = ".imap-index.tmp";
	static final String INDEX_OLD = ".imap-index.old";
	static final String CACHE_DIR = ".imap-cache";
	static final String HEADER = "BJLIMAP 1";

	/** Refresh from the directory at most this often (ms) unless forced. */
	private static final long REFRESH_INTERVAL = 1000;

	private static final AtomicLong LAST_UIDVALIDITY = new AtomicLong();
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final SecureRandom RANDOM = new SecureRandom();

	public enum FlagOp {
		SET, ADD, REMOVE
	}

	private final FileSource dir;
	private final String key;
	private final ReentrantLock lock = new ReentrantLock();
	private long uidValidity;
	private long uidNext = 1;
	private String specialUse;
	private final TreeMap<Long, MessageInfo> messages = new TreeMap<>();
	private final Map<String, MessageInfo> byName = new HashMap<>();
	private final Set<String> notMessages = new HashSet<>();
	private final List<MailboxView> views = new CopyOnWriteArrayList<>();
	private boolean dirty;
	private long lastRefresh;
	private volatile boolean deleted;
	int refCount;

	private Mailbox(FileSource dir, String key) {
		this.dir = dir;
		this.key = key;
	}

	/** Open a mailbox directory, reading its index (or creating one) and the files. */
	static Mailbox open(FileSource dir, String key) throws IOException {
		Mailbox m = new Mailbox(dir, key);
		m.lock();
		try {
			m.loadIndex();
			m.refresh(true);
		} finally {
			m.unlock();
		}
		return m;
	}

	public FileSource getDirectory() {
		return dir;
	}

	String getKey() {
		return key;
	}

	void lock() {
		lock.lock();
	}

	void unlock() {
		lock.unlock();
	}

	// ------------------------------------------------------------------ index

	private static long newUidValidity() {
		long now = System.currentTimeMillis() / 1000;
		return LAST_UIDVALIDITY.updateAndGet(last -> Math.max(now, last + 1)) & 0xFFFFFFFFL;
	}

	private void loadIndex() throws IOException {
		FileSource f = dir.getChild(INDEX);
		if (!f.exists()) {
			FileSource old = dir.getChild(INDEX_OLD);
			f = old.exists() ? old : null;
		}
		if (f == null) {
			uidValidity = newUidValidity();
			uidNext = 1;
			dirty = true;
			return;
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
			String line = r.readLine();
			if (!HEADER.equals(line)) {
				throw new IOException("Not an IMAP index: " + f.getAbsolutePath());
			}
			while ((line = r.readLine()) != null) {
				String[] p = line.split("\t", -1);
				switch (p[0]) {
				case "UIDVALIDITY":
					uidValidity = Long.parseLong(p[1]);
					break;
				case "UIDNEXT":
					uidNext = Long.parseLong(p[1]);
					break;
				case "SPECIALUSE":
					specialUse = p[1].isEmpty() ? null : p[1];
					break;
				case "M":
					if (p.length >= 7) {
						long uid = Long.parseLong(p[1]);
						MessageInfo info = new MessageInfo(uid, p[6], dir.getChild(p[6]), Long.parseLong(p[2]),
								Long.parseLong(p[3]), "1".equals(p[4]));
						if (!p[5].isEmpty()) {
							for (String flag : p[5].split(" ")) {
								info.flags.add(flag);
							}
						}
						messages.put(uid, info);
						byName.put(info.getName(), info);
						uidNext = Math.max(uidNext, uid + 1);
					}
					break;
				default:
					// unknown lines are ignored (a newer version may add some)
				}
			}
		} catch (RuntimeException e) {
			throw new IOException("Damaged IMAP index " + f.getAbsolutePath(), e);
		}
		if (uidValidity <= 0) {
			uidValidity = newUidValidity();
			dirty = true;
		}
	}

	/** Write the index if it changed. The old index is kept until the new one is in place. */
	public void saveIfDirty() throws IOException {
		lock();
		try {
			if (!dirty || deleted || !dir.exists()) {
				return;
			}
			FileSource tmp = dir.getChild(INDEX_TMP);
			try (Writer w = new OutputStreamWriter(new BufferedOutputStream(tmp.getOutputStream(), 64 * 1024),
					StandardCharsets.UTF_8)) {
				w.write(HEADER + "\n");
				w.write("UIDVALIDITY\t" + uidValidity + "\n");
				w.write("UIDNEXT\t" + uidNext + "\n");
				if (specialUse != null) {
					w.write("SPECIALUSE\t" + specialUse + "\n");
				}
				for (MessageInfo m : messages.values()) {
					w.write("M\t" + m.getUid() + "\t" + m.getInternalDate() + "\t" + m.getSize() + "\t"
							+ (m.isUtf8() ? "1" : "0") + "\t" + String.join(" ", m.flags) + "\t" + m.getName() + "\n");
				}
			}
			FileSource index = dir.getChild(INDEX);
			FileSource old = dir.getChild(INDEX_OLD);
			if (old.exists()) {
				old.delete();
			}
			if (index.exists() && !index.renameTo(old)) {
				throw new IOException("Can't replace " + index.getAbsolutePath());
			}
			if (!tmp.renameTo(dir.getChild(INDEX))) {
				throw new IOException("Can't write " + index.getAbsolutePath());
			}
			if (old.exists()) {
				old.delete();
			}
			dirty = false;
		} finally {
			unlock();
		}
	}

	// ------------------------------------------------------------------ state

	public long getUidValidity() {
		return uidValidity;
	}

	public long getUidNext() {
		lock();
		try {
			return uidNext;
		} finally {
			unlock();
		}
	}

	/** The RFC 6154 special-use attribute (e.g. "\Sent"), or null. */
	public String getSpecialUse() {
		return specialUse;
	}

	public void setSpecialUse(String attribute) throws IOException {
		lock();
		try {
			specialUse = attribute;
			dirty = true;
		} finally {
			unlock();
		}
		saveIfDirty();
	}

	public boolean isDeleted() {
		return deleted;
	}

	/** The messages in UID order. */
	public List<MessageInfo> getMessages() {
		lock();
		try {
			return new ArrayList<>(messages.values());
		} finally {
			unlock();
		}
	}

	public MessageInfo get(long uid) {
		lock();
		try {
			return messages.get(uid);
		} finally {
			unlock();
		}
	}

	/** UIDs greater than {@code uid}, ascending. */
	public List<Long> uidsAfter(long uid) {
		lock();
		try {
			return new ArrayList<>(messages.tailMap(uid, false).keySet());
		} finally {
			unlock();
		}
	}

	public int count() {
		lock();
		try {
			return messages.size();
		} finally {
			unlock();
		}
	}

	public int countWithout(String flag) {
		lock();
		try {
			int n = 0;
			for (MessageInfo m : messages.values()) {
				if (!hasFlag(m.flags, flag)) {
					n++;
				}
			}
			return n;
		} finally {
			unlock();
		}
	}

	public int countWith(String flag) {
		return count() - countWithout(flag);
	}

	public long totalSize() {
		lock();
		try {
			long n = 0;
			for (MessageInfo m : messages.values()) {
				n += m.getSize();
			}
			return n;
		} finally {
			unlock();
		}
	}

	/** Case-insensitive flag test (flags are case-insensitive, RFC 9051 section 2.3.2). */
	public static boolean hasFlag(Collection<String> flags, String flag) {
		for (String f : flags) {
			if (f.equalsIgnoreCase(flag)) {
				return true;
			}
		}
		return false;
	}

	public boolean hasFlag(MessageInfo m, String flag) {
		lock();
		try {
			return hasFlag(m.flags, flag);
		} finally {
			unlock();
		}
	}

	// ------------------------------------------------------------------ views

	/** A new session view of this mailbox (SELECT, EXAMINE). Close it when done. */
	public MailboxView newView(boolean readOnly) {
		return new MailboxView(this, readOnly);
	}

	void addView(MailboxView v) {
		views.add(v);
	}

	void removeView(MailboxView v) {
		views.remove(v);
	}

	// ------------------------------------------------------------------ refresh

	/**
	 * Find messages added or removed outside this server's IMAP sessions (POP3,
	 * delivery) and tell the views. Without {@code force}, at most once a second.
	 */
	public void refresh(boolean force) throws IOException {
		lock();
		try {
			long now = System.currentTimeMillis();
			if (!force && now - lastRefresh < REFRESH_INTERVAL) {
				return;
			}
			lastRefresh = now;
			Set<String> present = new HashSet<>();
			List<String> added = new ArrayList<>();
			FileSource[] list = deleted || !dir.exists() ? null : dir.listFiles();
			if (list != null) {
				for (FileSource f : list) {
					String name = f.getName();
					if (name.startsWith(".") || name.indexOf('\t') >= 0 || name.indexOf('\n') >= 0) {
						continue;
					}
					present.add(name);
					if (!byName.containsKey(name) && !notMessages.contains(name)) {
						if (f.isFile()) {
							added.add(name);
						} else {
							notMessages.add(name);
						}
					}
				}
			}
			notMessages.retainAll(present);
			for (Iterator<MessageInfo> it = messages.values().iterator(); it.hasNext();) {
				MessageInfo m = it.next();
				if (!present.contains(m.getName())) {
					it.remove();
					byName.remove(m.getName());
					deleteCache(m);
					dirty = true;
					for (MailboxView v : views) {
						v.expunged(m.getUid());
					}
				}
			}
			added.sort(null);
			for (String name : added) {
				MessageInfo m = index(dir.getChild(name), uidNext, new LinkedHashSet<>(), 0);
				if (m != null) {
					uidNext++;
					messages.put(m.getUid(), m);
					byName.put(name, m);
					dirty = true;
				}
			}
			if (!added.isEmpty()) {
				for (MailboxView v : views) {
					v.added();
				}
			}
		} finally {
			unlock();
		}
		saveIfDirty();
	}

	/**
	 * Make the index entry for a message file: rewrite it with CRLF line ends if
	 * needed, measure it and check whether it has UTF-8 headers.
	 */
	private MessageInfo index(FileSource file, long uid, Set<String> flags, long internalDate) throws IOException {
		Scan s = Scan.of(file);
		if (s.needsCrlf) {
			FileSource tmp = dir.getChild(".crlf-" + file.getName());
			try (InputStream in = new BufferedInputStream(file.getInputStream(), 64 * 1024);
					OutputStream out = new CrlfOutputStream(new BufferedOutputStream(tmp.getOutputStream(), 64 * 1024))) {
				in.transferTo(out);
			}
			if (!file.delete() || !tmp.renameTo(file)) {
				tmp.delete();
				throw new IOException("Can't rewrite " + file.getAbsolutePath() + " with CRLF line ends");
			}
			s = Scan.of(file);
		}
		boolean utf8 = false;
		if (s.nonAscii) {
			try {
				utf8 = Downgrader.needsUtf8(Message.parse(file));
			} catch (IOException | RuntimeException e) {
				utf8 = true; // can't tell: treat as needing UTF-8
			}
		}
		long date = internalDate > 0 ? internalDate : file.lastModified();
		if (date <= 0) {
			date = System.currentTimeMillis();
		}
		MessageInfo m = new MessageInfo(uid, file.getName(), file, date, s.size, utf8);
		m.flags.addAll(flags);
		return m;
	}

	/** Size, bare LF/CR and 8-bit content of a file. */
	private static final class Scan {
		long size;
		boolean needsCrlf;
		boolean nonAscii;

		static Scan of(FileSource f) throws IOException {
			Scan s = new Scan();
			int prev = '\n';
			try (InputStream in = new BufferedInputStream(f.getInputStream(), 64 * 1024)) {
				byte[] buf = new byte[64 * 1024];
				int n;
				while ((n = in.read(buf)) > 0) {
					for (int i = 0; i < n; i++) {
						int b = buf[i] & 0xff;
						if ((b == '\n' && prev != '\r') || (prev == '\r' && b != '\n')) {
							s.needsCrlf = true;
						}
						if (b >= 128) {
							s.nonAscii = true;
						}
						prev = b;
					}
					s.size += n;
				}
			}
			if (prev == '\r') {
				s.needsCrlf = true;
			}
			return s;
		}
	}

	/** Turns bare LF and bare CR into CRLF. */
	static final class CrlfOutputStream extends java.io.FilterOutputStream {
		private boolean pendingCr;

		CrlfOutputStream(OutputStream out) {
			super(out);
		}

		@Override
		public void write(int b) throws IOException {
			if (pendingCr) {
				pendingCr = false;
				out.write('\r');
				out.write('\n');
				if (b == '\n') {
					return;
				}
			}
			if (b == '\r') {
				pendingCr = true;
			} else if (b == '\n') {
				out.write('\r');
				out.write('\n');
			} else {
				out.write(b);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			for (int i = off; i < off + len; i++) {
				write(b[i]);
			}
		}

		@Override
		public void close() throws IOException {
			if (pendingCr) {
				pendingCr = false;
				out.write('\r');
				out.write('\n');
			}
			super.close();
		}
	}

	// ------------------------------------------------------------------ changes

	private static String newName() {
		return String.format("%013d.%08d.%08x.eml", System.currentTimeMillis(), SEQUENCE.incrementAndGet() % 100_000_000L,
				RANDOM.nextInt());
	}

	/**
	 * Add a message (APPEND, COPY): its content is copied into the mailbox with
	 * CRLF line ends.
	 *
	 * @return the new message
	 */
	public MessageInfo append(InputStream content, Set<String> flags, long internalDate) throws IOException {
		checkUsable();
		String name = newName();
		FileSource tmp = dir.getChild("." + name + ".tmp");
		try (OutputStream out = new CrlfOutputStream(new BufferedOutputStream(tmp.getOutputStream(), 64 * 1024))) {
			content.transferTo(out);
		} catch (IOException | RuntimeException e) {
			tmp.delete();
			throw e;
		}
		FileSource target = dir.getChild(name);
		if (!tmp.renameTo(target)) {
			tmp.delete();
			throw new IOException("Can't store the message in " + dir.getAbsolutePath());
		}
		return addFile(target, flags, internalDate);
	}

	/** Add a file already in the directory (or moved into it). */
	private MessageInfo addFile(FileSource file, Set<String> flags, long internalDate) throws IOException {
		MessageInfo m;
		lock();
		try {
			m = index(file, uidNext, cleanFlags(flags), internalDate);
			uidNext++;
			messages.put(m.getUid(), m);
			byName.put(m.getName(), m);
			dirty = true;
			for (MailboxView v : views) {
				v.added();
			}
		} finally {
			unlock();
		}
		saveIfDirty();
		return m;
	}

	/**
	 * Move messages into another mailbox (MOVE): the files are renamed when the
	 * file system allows it, copied otherwise.
	 *
	 * @return the new messages, in the order given
	 */
	public List<MessageInfo> moveTo(Mailbox target, List<MessageInfo> list, MailboxView source) throws IOException {
		List<MessageInfo> ret = new ArrayList<>();
		List<Long> done = new ArrayList<>();
		try {
			for (MessageInfo m : list) {
				Set<String> flags = new LinkedHashSet<>(m.getFlags(this));
				FileSource dest = target.dir.getChild(newName());
				if (m.getFile().renameTo(dest)) {
					lock();
					try {
						messages.remove(m.getUid());
						byName.remove(m.getName());
						deleteCache(m);
						dirty = true;
						for (MailboxView v : views) {
							if (v != source) {
								v.expunged(m.getUid());
							}
						}
					} finally {
						unlock();
					}
					ret.add(target.addFile(dest, flags, m.getInternalDate()));
				} else {
					try (InputStream in = m.getFile().getInputStream()) {
						ret.add(target.append(in, flags, m.getInternalDate()));
					}
					done.add(m.getUid());
				}
			}
		} finally {
			if (!done.isEmpty()) {
				expunge(done, source);
			}
			saveIfDirty();
		}
		return ret;
	}

	/** Copy messages into another mailbox, keeping flags and INTERNALDATE. */
	public List<MessageInfo> copyTo(Mailbox target, List<MessageInfo> list) throws IOException {
		List<MessageInfo> ret = new ArrayList<>();
		for (MessageInfo m : list) {
			try (InputStream in = m.getFile().getInputStream()) {
				ret.add(target.append(in, m.getFlags(this), m.getInternalDate()));
			}
		}
		return ret;
	}

	/**
	 * Remove messages (EXPUNGE): their files are deleted and every other view is
	 * told. The caller reports the expunges to its own session.
	 *
	 * @return the UIDs removed
	 */
	public List<Long> expunge(Collection<Long> uids, MailboxView source) throws IOException {
		List<Long> removed = new ArrayList<>();
		lock();
		try {
			for (long uid : new TreeSet<>(uids)) {
				MessageInfo m = messages.get(uid);
				if (m == null) {
					continue;
				}
				if (m.getFile().exists() && !m.getFile().delete()) {
					continue; // still there: not expunged
				}
				messages.remove(uid);
				byName.remove(m.getName());
				deleteCache(m);
				removed.add(uid);
				dirty = true;
				for (MailboxView v : views) {
					if (v != source) {
						v.expunged(uid);
					}
				}
			}
		} finally {
			unlock();
		}
		saveIfDirty();
		return removed;
	}

	/** Change a message's flags (STORE); every other view is told. Returns the new flags. */
	public Set<String> storeFlags(MessageInfo m, FlagOp op, Collection<String> flags, MailboxView source) {
		lock();
		try {
			if (messages.get(m.getUid()) != m) {
				return null;
			}
			Set<String> before = new LinkedHashSet<>(m.flags);
			Set<String> clean = cleanFlags(flags);
			if (op == FlagOp.SET) {
				m.flags.clear();
				m.flags.addAll(clean);
			} else if (op == FlagOp.ADD) {
				for (String f : clean) {
					if (!hasFlag(m.flags, f)) {
						m.flags.add(f);
					}
				}
			} else {
				m.flags.removeIf(f -> hasFlag(clean, f));
			}
			if (!before.equals(m.flags)) {
				dirty = true;
				for (MailboxView v : views) {
					if (v != source) {
						v.flagsChanged(m.getUid());
					}
				}
			}
			return new LinkedHashSet<>(m.flags);
		} finally {
			unlock();
		}
	}

	/** Canonical system flag names; \Recent can't be stored. */
	static Set<String> cleanFlags(Collection<String> flags) {
		Set<String> ret = new LinkedHashSet<>();
		for (String f : flags) {
			String c = canonical(f);
			if (c != null && !hasFlag(ret, c)) {
				ret.add(c);
			}
		}
		return ret;
	}

	private static final String[] SYSTEM = {"\\Seen", "\\Answered", "\\Flagged", "\\Deleted", "\\Draft"};
	private static final String[] KEYWORDS = {"$MDNSent", "$Forwarded", "$Junk", "$NotJunk", "$Phishing"};

	static String canonical(String f) {
		if (f.equalsIgnoreCase("\\Recent")) {
			return null;
		}
		for (String s : SYSTEM) {
			if (s.equalsIgnoreCase(f)) {
				return s;
			}
		}
		for (String s : KEYWORDS) {
			if (s.equalsIgnoreCase(f)) {
				return s;
			}
		}
		return f;
	}

	private void checkUsable() throws IOException {
		if (deleted || !dir.exists()) {
			throw new IOException("The mailbox no longer exists");
		}
	}

	/** Forget this mailbox (deleted or renamed): views see every message expunged. */
	void markDeleted() {
		lock();
		try {
			deleted = true;
			for (MessageInfo m : messages.values()) {
				for (MailboxView v : views) {
					v.expunged(m.getUid());
				}
			}
			messages.clear();
			byName.clear();
		} finally {
			unlock();
		}
	}

	// ------------------------------------------------------------------ surrogates

	/**
	 * The RFC 6858 surrogate of a message with UTF-8 headers, for sessions that
	 * aren't in UTF-8 mode. It is made once and kept in {@code .imap-cache}.
	 */
	public FileSource surrogate(MessageInfo m) throws IOException {
		FileSource cacheDir = dir.getChild(CACHE_DIR);
		FileSource f = cacheDir.getChild(m.getUid() + ".eml");
		lock();
		try {
			if (!f.exists()) {
				if (!cacheDir.exists()) {
					cacheDir.mkdirs();
				}
				Message msg = Message.parse(m.getFile());
				Downgrader.downgrade(msg);
				FileSource tmp = cacheDir.getChild(m.getUid() + ".tmp");
				try (OutputStream out = new BufferedOutputStream(tmp.getOutputStream(), 64 * 1024)) {
					msg.writeTo(out);
				}
				if (!tmp.renameTo(f)) {
					tmp.delete();
					throw new IOException("Can't cache the surrogate of " + m.getName());
				}
			}
			return f;
		} finally {
			unlock();
		}
	}

	private void deleteCache(MessageInfo m) {
		try {
			FileSource f = dir.getChild(CACHE_DIR).getChild(m.getUid() + ".eml");
			if (f.exists()) {
				f.delete();
			}
		} catch (IOException e) {
			// a stale cache file is harmless
		}
	}

	/** Read the special-use attribute from a mailbox's index without opening it. */
	static String readSpecialUse(FileSource dir) {
		try {
			FileSource f = dir.getChild(INDEX);
			if (!f.exists()) {
				return null;
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				int n = 0;
				while ((line = r.readLine()) != null && n++ < 10) {
					if (line.startsWith("SPECIALUSE\t")) {
						String v = line.substring(11);
						return v.isEmpty() ? null : v;
					}
					if (line.startsWith("M\t")) {
						break;
					}
				}
			}
		} catch (IOException e) {
			// none
		}
		return null;
	}
}
