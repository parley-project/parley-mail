package us.bringardner.parley.mail.store;

/**
 * Names, special-use attributes and response codes the mail store uses.
 * <p>
 * These used to come from the IMAP interface; they moved here with the store so the
 * store (shared by IMAP, POP3 and SMTP local delivery) doesn't depend on the IMAP
 * module. The values are the IMAP ones (RFC 9051, RFC 6154), and the IMAP interface
 * uses the same values.
 */
public interface MailboxConstants {

	/** Hierarchy delimiter of mailbox names. */
	char DELIMITER = '/';
	/** The user's inbox (also the POP3 maildrop). */
	String INBOX = "INBOX";

	// special-use mailbox attributes (RFC 6154)
	String SENT = "\\Sent";
	String DRAFTS = "\\Drafts";
	String TRASH = "\\Trash";
	String JUNK = "\\Junk";
	String ARCHIVE = "\\Archive";

	// response codes carried by store errors (RFC 9051 section 7.1)
	String CODE_ALREADYEXISTS = "ALREADYEXISTS";
	String CODE_NONEXISTENT = "NONEXISTENT";
	String CODE_CANNOT = "CANNOT";
	String CODE_HASCHILDREN = "HASCHILDREN";
	String CODE_LIMIT = "LIMIT";
}
