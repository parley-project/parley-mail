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

- **INBOX** is the user's POP3 maildrop: the directory named by the principal's `maildrop` parameter, or the user name, under the root. POP3 and IMAP see the same messages. A POP3 deletion appears to IMAP sessions as an expunge. Mail delivered into the maildrop appears in INBOX. New mail comes in one way, `MailStore.deliver(content, junk)` (SMTP's local delivery uses it): through `Mailbox.append`, which every new message file goes through, so it is stored with CRLF line ends and indexed.
- **Other mailboxes** are directories under `<inbox>/.mailboxes`; a child mailbox is a subdirectory of its parent's directory. Name segments are encoded so names stay case-sensitive and safe on any file system. For example, `Sent` is stored as `_sent` and `Über` as `%C3%9Cber`. POP3 ignores names starting with "." and directories, so it only sees INBOX.
- **New users** get Sent, Drafts, Trash, Junk and Archive, marked with their RFC 6154 special use and subscribed. The IMAP server turns this off with `parley.mail.imap.defaultMailboxes=false`.
- **Index:** each mailbox has a hidden `.imap-index` file holding UIDVALIDITY, UIDNEXT, and each message's UID, flags and INTERNALDATE. It is replaced safely (temp file, then rename). UIDs are never reused. Message files themselves are never modified, except for the next point.
- **Line ends:** a message file found with bare LF line ends is rewritten once with CRLF, so the sizes IMAP reports are exact.
- **Sharing within a server:** sessions in the same process share one `Mailbox` object per directory (`MailboxRegistry`), so changes reach other sessions at once (EXISTS, EXPUNGE and FETCH FLAGS, which include UID). Changes made outside the server, such as POP3 or delivery, are found by rescanning the directory, at most once a second.
- **Several servers on one store** (`parley.mail.sharedStore`): `off` (the default) means one server process serves the root, and its claim on the store refuses a second; `host` lets several servers on one machine share it, and `cluster` several on any machines. The locks come from parley-net (`us.bringardner.parley.net.lock`): the servers of a JVM share one lock domain for the maildrop root (`MailLocks`, named by `parley.mail.lockDomain`, default `mail`), kept the best way the store allows (its own locks on a local disk or PostgreSQL/MySQL, lease directories on FTP or SFTP; see parley-net's README for `parley.net.lock.provider` and the rules that keep servers from keeping one store's locks differently). With sharing on:
  - every change to a mailbox (APPEND, COPY, MOVE, EXPUNGE, a rescan) runs under its lock, after catching up with the index another server saved, and saves before the lock goes. Each save has a random id, checked with UIDVALIDITY, so a mailbox another server deleted and made again is never taken for the old one: sessions that have it selected are told to close it. The last UIDVALIDITY given is kept in the store (`.mail-uidvalidity` in the maildrop root, under the lock `uidvalidity`), so a mailbox made again by another server, or after a restart, gets a new one even within the same second.
  - UIDNEXT only goes up, so no UID is ever given twice; an APPEND whose index can't be saved takes its message back out, so a client that tries again doesn't get it twice.
  - flag changes are kept in memory and saved by the next change (or the next rescan, at most a second later), replayed on the latest index, so two servers' flag changes to one message are both kept.
  - MOVE within a store locks both mailboxes (in one order, so moves both ways can't wait for each other) and moves the files with their flags; a crash in between leaves each message in one mailbox. Across stores it copies, then expunges: a crash in between leaves a copy in both, never in neither.
  - CREATE, DELETE, RENAME and subscriptions run under the user's tree lock.
  - The index is replaced in one step where the store can (`atomicReplace`); elsewhere the previous one stands in as `.imap-index.old` while the new one goes in.
