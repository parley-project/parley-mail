package us.bringardner.parley.mail.store;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The mailbox ACLs (RFC 4314) of a mail store, for an administrator (parley-mail-admin): each
 * running mail server registers one as {@code us.bringardner.parley:type=MailStore,name=ID}, and
 * every change goes through that server's locks on the store, as SETACL does.
 * <p>
 * A user's mailboxes are named by the user (the full login address, the mailboxes' owner) and
 * the user's maildrop (the folder under the maildrop root, usually the user's name). Rights are
 * written as in {@link MailboxAcl} ({@value MailboxAcl#ALL}); a change is {@code +rights},
 * {@code -rights} or the rights.
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
public interface MailStoreAdminMXBean {

	/** The JMX type of the beans */
	String TYPE = "MailStore";

	/** @return the maildrop root, as the server describes it (no secrets) */
	String getMaildropRoot();

	/**
	 * @return the user's mailboxes, INBOX first; empty when the maildrop doesn't exist yet (no
	 * mail and no login)
	 */
	List<String> listMailboxes(String owner, String maildrop) throws IOException;

	/**
	 * A mailbox's ACL as it is stored: identifier to rights (a negative entry's identifier starts
	 * with {@code -}). The owner isn't in it unless the owner's own rights were set; without that
	 * entry the owner has every right.
	 *
	 * @throws IOException if the mailbox doesn't exist
	 */
	Map<String, String> getAcl(String owner, String maildrop, String mailbox) throws IOException;

	/**
	 * SETACL for one identifier.
	 *
	 * @return the identifier's rights now
	 * @throws IllegalArgumentException for a bad identifier or right
	 * @throws IOException if the mailbox doesn't exist or the ACL can't be written
	 */
	String setRights(String owner, String maildrop, String mailbox, String identifier, String change) throws IOException;

	/**
	 * DELETEACL for one identifier.
	 *
	 * @return false if it had no entry
	 */
	boolean deleteRights(String owner, String maildrop, String mailbox, String identifier) throws IOException;

	/**
	 * After a user is deleted: take the user out of every ACL of these users' mailboxes (entries and
	 * negative entries) and remove the user's own lines from the share index.
	 *
	 * @param identifier the deleted user (full login address)
	 * @param maildrops the users whose mailboxes to clean, owner to maildrop
	 * @return how many entries were removed
	 */
	int forgetUser(String identifier, Map<String, String> maildrops) throws IOException;
}
