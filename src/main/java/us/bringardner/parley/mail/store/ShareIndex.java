package us.bringardner.parley.mail.store;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.io.IoUtils;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.Mode;
import us.bringardner.parley.net.lock.NamedLock;

/**
 * Which owners have shared mailboxes with which identifiers, so IMAP's LIST can find the other
 * users' mailboxes a user may see (the Other Users namespace) without opening every user's
 * store. It is a {@value #FILE} file in the maildrop root, one line per owner and identifier
 * (user, {@code $group} or {@code anyone}) that has rights on at least one of the owner's
 * mailboxes: the owner, a TAB, the identifier.
 * <p>
 * {@link MailStore} keeps an owner's lines up to date when its ACLs change (SETACL, DELETEACL,
 * CREATE, DELETE). It is only a hint: LIST still checks the {@code l} right on each mailbox,
 * so a line left over shows nothing.
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
public final class ShareIndex {

	/** The file in the maildrop root. */
	public static final String FILE = ".imap-shares";
	private static final String LOCK = "shares";
	private static final SecureRandom RANDOM = new SecureRandom();
	/** For a root without a lock domain: this JVM's lock. */
	private static final Object LOCAL = new Object();

	private final FileSource root;
	private final MailboxRegistry registry;

	/**
	 * @param root the maildrop root
	 * @param registry whose lock domains reach the other servers
	 */
	public ShareIndex(FileSource root, MailboxRegistry registry) {
		this.root = root;
		this.registry = registry;
	}

	public FileSource getRoot() {
		return root;
	}

	/**
	 * The owners who have given at least one of these identifiers rights somewhere.
	 *
	 * @param user the user (full login address)
	 * @param groups the user's groups (written {@code $name} in an ACL)
	 * @return the owners, sorted, never the user
	 */
	public List<String> ownersSharingWith(String user, Collection<String> groups) throws IOException {
		Set<String> ids = new LinkedHashSet<>();
		ids.add(user);
		ids.add(MailboxAcl.ANYONE);
		if (groups != null) {
			for (String g : groups) {
				ids.add(MailboxAcl.GROUP + g);
			}
		}
		Set<String> ret = new TreeSet<>();
		for (Map.Entry<String, Set<String>> e : read().entrySet()) {
			if (e.getKey().equals(user)) {
				continue;
			}
			for (String id : e.getValue()) {
				if (ids.contains(id)) {
					ret.add(e.getKey());
					break;
				}
			}
		}
		return new ArrayList<>(ret);
	}

	/** @return every owner's identifiers */
	public Map<String, Set<String>> read() throws IOException {
		Map<String, Set<String>> ret = new TreeMap<>();
		FileSource f = root.getChild(FILE);
		if (!f.exists()) {
			// a replace under way on a file system without a one-step replace
			f = root.getChild(FILE + ".old");
			if (!f.exists()) {
				return ret;
			}
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				int tab = line.indexOf('\t');
				if (tab > 0 && tab < line.length() - 1) {
					ret.computeIfAbsent(line.substring(0, tab), k -> new TreeSet<>()).add(line.substring(tab + 1));
				}
			}
		} catch (FileNotFoundException gone) {
			return new TreeMap<>();
		}
		return ret;
	}

	/**
	 * Replace an owner's lines.
	 *
	 * @param identifiers the identifiers with rights on any of the owner's mailboxes (the
	 * owner itself and negative entries are left out)
	 */
	public void update(String owner, Collection<String> identifiers) throws IOException {
		if (owner == null) {
			return;
		}
		Set<String> ids = new TreeSet<>();
		for (String id : identifiers) {
			if (id != null && !id.isEmpty() && id.charAt(0) != MailboxAcl.NEGATIVE && !id.equals(owner)) {
				ids.add(id);
			}
		}
		LockDomain d = registry.domainOf(root);
		if (d == null) {
			synchronized (LOCAL) {
				updateLocked(owner, ids, null);
			}
		} else {
			try (NamedLock l = d.lock(LOCK, Mode.EXCLUSIVE, d.options().timeout())) {
				updateLocked(owner, ids, l);
			}
		}
	}

	/**
	 * Remove an owner's lines (a user who was deleted).
	 * @since 2.0.0
	 */
	public void remove(String owner) throws IOException {
		update(owner, java.util.Collections.emptyList());
	}

	private void updateLocked(String owner, Set<String> ids, NamedLock l) throws IOException {
		Map<String, Set<String>> all = read();
		Set<String> before = all.get(owner);
		if (ids.isEmpty() ? before == null : ids.equals(before)) {
			return;
		}
		if (ids.isEmpty()) {
			all.remove(owner);
		} else {
			all.put(owner, ids);
		}
		FileSource tmp = root.getChild("." + FILE.substring(1) + "." + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE) + ".tmp");
		try {
			try (Writer w = new OutputStreamWriter(IoUtils.buffered(tmp.getOutputStream()), StandardCharsets.UTF_8)) {
				for (Map.Entry<String, Set<String>> e : all.entrySet()) {
					for (String id : e.getValue()) {
						w.write(e.getKey() + "\t" + id + "\n");
					}
				}
			}
			if (l != null) {
				l.checkValid();
			}
			FileSource f = root.getChild(FILE);
			if (root.getCapabilities().atomicReplace()) {
				if (!tmp.renameTo(f, true)) {
					throw new IOException("Can't replace " + f.getAbsolutePath());
				}
			} else {
				// no one-step replace: the previous file stands in (.old) while the new one goes in
				FileSource old = root.getChild(FILE + ".old");
				if (old.exists()) {
					old.delete();
				}
				if (f.exists() && !f.renameTo(old)) {
					throw new IOException("Can't replace " + f.getAbsolutePath());
				}
				if (!tmp.renameTo(f)) {
					old.renameTo(f);
					throw new IOException("Can't write " + f.getAbsolutePath());
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
}
