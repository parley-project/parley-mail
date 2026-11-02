package us.bringardner.parley.mail.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.files.FileSource;

/**
 * {@link MailStoreAdminMXBean} for a maildrop root, through the {@link MailboxRegistry}'s locks
 * (the running server's). Errors cross JMX as plain {@link IOException}s and
 * {@link IllegalArgumentException}s with their messages, so a client needs none of these classes.
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
public final class MailStoreAdmin implements MailStoreAdminMXBean {

	private final FileSource root;
	private final String description;
	private final MailboxRegistry registry;

	/**
	 * @param root the maildrop root
	 * @param description how to show the root (without secrets)
	 * @param registry whose locks reach the other servers on the store
	 */
	public MailStoreAdmin(FileSource root, String description, MailboxRegistry registry) {
		this.root = root;
		this.description = description;
		this.registry = registry;
	}

	@Override
	public String getMaildropRoot() {
		return description;
	}

	/** The store of a user, as it is (nothing is made). */
	MailStore store(String owner, String maildrop) throws IOException {
		if (owner == null || owner.trim().isEmpty()) {
			throw new IllegalArgumentException("No user given");
		}
		String drop = maildrop == null || maildrop.trim().isEmpty() ? owner : maildrop.trim();
		MailStore s = new MailStore(MaildropRoot.child(root, drop), registry, owner.trim());
		s.setShareIndex(new ShareIndex(root, registry));
		return s;
	}

	@Override
	public List<String> listMailboxes(String owner, String maildrop) throws IOException {
		List<String> ret = new ArrayList<>();
		try {
			MailStore s = store(owner, maildrop);
			if (!s.getInboxDirectory().isDirectory()) {
				return ret;
			}
			for (MailStore.Entry e : s.listAll()) {
				if (!e.noselect) {
					ret.add(e.name);
				}
			}
		} catch (IOException e) {
			throw plain(e);
		}
		return ret;
	}

	@Override
	public Map<String, String> getAcl(String owner, String maildrop, String mailbox) throws IOException {
		try {
			MailboxAcl acl = store(owner, maildrop).getAcl(mailbox);
			return acl == null ? new LinkedHashMap<>() : new LinkedHashMap<>(acl.entries());
		} catch (IOException e) {
			throw plain(e);
		}
	}

	@Override
	public String setRights(String owner, String maildrop, String mailbox, String identifier, String change) throws IOException {
		try {
			return store(owner, maildrop).setRights(mailbox, identifier, change == null ? "" : change);
		} catch (IOException e) {
			throw plain(e);
		}
	}

	@Override
	public boolean deleteRights(String owner, String maildrop, String mailbox, String identifier) throws IOException {
		try {
			return store(owner, maildrop).deleteRights(mailbox, identifier);
		} catch (IOException e) {
			throw plain(e);
		}
	}

	@Override
	public int forgetUser(String identifier, Map<String, String> maildrops) throws IOException {
		MailboxAcl.checkIdentifier(identifier);
		int removed = 0;
		List<String> failed = new ArrayList<>();
		if (maildrops != null) {
			for (Map.Entry<String, String> e : maildrops.entrySet()) {
				if (e.getKey().equals(identifier)) {
					continue;
				}
				try {
					removed += store(e.getKey(), e.getValue()).removeIdentifier(identifier);
				} catch (IOException | RuntimeException ex) {
					failed.add(e.getKey() + " (" + ex.getMessage() + ")");
				}
			}
		}
		try {
			new ShareIndex(root, registry).remove(identifier);
		} catch (IOException e) {
			failed.add("the share index (" + e.getMessage() + ")");
		}
		if (!failed.isEmpty()) {
			throw new IOException("Removed " + removed + " ACL entries of " + identifier + "; couldn't clean " + String.join(", ", failed));
		}
		return removed;
	}

	private static IOException plain(IOException e) {
		if (e.getClass() == IOException.class && e.getCause() == null) {
			return e;
		}
		return new IOException(e.getMessage() != null ? e.getMessage() : e.toString());
	}
}
