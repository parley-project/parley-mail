# parley-mail

Internet mail for **Parley**, a family of Java libraries for implementing internet protocols.
This module holds what every mail protocol shares; the protocols themselves are separate modules
built on it (`parley-smtp`, `parley-imap`, `parley-pop3`).

- `us.bringardner.parley.mail`: internet messages (`Message`), with MIME, RFC 2231 parameters,
  RFC 2047 encoded words and RFC 6532 UTF-8 headers. Messages are stored in a `FileSource`, so they
  can be larger than memory. `Downgrader` makes the RFC 6858 surrogate of a message with UTF-8
  headers. Also `Address`, `Header`, `Rfc2822Date`, `QuotedPrintable`, `EncodedWord` and `SaslPrep`.
- `us.bringardner.parley.mail.store`: the mailbox store: one user's mailboxes (`MailStore`),
  each a directory of message files with an index (`Mailbox`), shared between sessions
  (`MailboxRegistry`). SMTP delivers into it, IMAP reads and manages it, and POP3 sees its INBOX.

Requires Java 11 or later. Depends on `parley-files` (which brings in `parley-core` and `parley-io`).

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-mail</artifactId>
    <version>1.0.0</version>
</dependency>
```

> parley-mail was split out of `us.bringardner:bjl_email` (BjlEmail). `us.bringardner.net.email`
> is now `us.bringardner.parley.mail`, and the IMAP server's store, `us.bringardner.net.imap.server.store`,
> is now `us.bringardner.parley.mail.store`. The store's constants (INBOX, the special-use attributes,
> response codes) moved from the `IMAP` interface to `MailboxConstants`, with the same values.
> Mailboxes on disk are unchanged: the index format and file layout are the same.

## Build

```
mvn package
```

Tests run with a 64 MB heap, so the large-message tests only pass if message bodies stay out of memory.

## Mailbox store (`us.bringardner.parley.mail.store`)

- **INBOX** is the user's POP3 maildrop: the directory named by the principal's `maildrop` parameter, or the user name, under the root. POP3 and IMAP see the same messages. A POP3 deletion appears to IMAP sessions as an expunge. Mail delivered into the maildrop (by SMTP, or the POP3 server's `Maildrop.deliver`) appears in INBOX.
- **Other mailboxes** are directories under `<inbox>/.mailboxes`; a child mailbox is a subdirectory of its parent's directory. Name segments are encoded so names stay case-sensitive and safe on any file system. For example, `Sent` is stored as `_sent` and `Über` as `%C3%9Cber`. POP3 ignores names starting with "." and directories, so it only sees INBOX.
- **New users** get Sent, Drafts, Trash, Junk and Archive, marked with their RFC 6154 special use and subscribed. The IMAP server turns this off with `JImap.defaultMailboxes=false`.
- **Index:** each mailbox has a hidden `.imap-index` file holding UIDVALIDITY, UIDNEXT, and each message's UID, flags and INTERNALDATE. It is replaced safely (temp file, then rename). UIDs are never reused. Message files themselves are never modified, except for the next point.
- **Line ends:** a message file found with bare LF line ends is rewritten once with CRLF, so the sizes IMAP reports are exact.
- **Sharing:** sessions in the same process share one `Mailbox` object per directory (`MailboxRegistry`), so changes reach other sessions at once (EXISTS, EXPUNGE and FETCH FLAGS, which include UID). Changes made outside the server, such as POP3 or delivery, are found by rescanning the directory, at most once a second. Only one server process should serve a root.
