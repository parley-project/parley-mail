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

## Running the mail servers

The servers (parley-smtp, parley-imap, parley-pop3) run alone, several in one process, or under a
service manager, as any user. Run one protocol's servers with its own `main`
(`java ... us.bringardner.parley.pop3.server.Pop3Server`): it reads its settings file and the command
line, and runs that protocol's servers (with implicit TLS only when there is a TLS key store).
While each runs, its store's administration (the mailbox ACLs, `AbstractMailServer.getStoreAdmin()`)
is the JMX bean `us.bringardner.parley:type=MailStore,name=<id>` (`MailServerMain`).

| Id | Server | Port setting (default) |
|---|---|---|
| `smtp` | SMTP relay (MX) | `parley.mail.smtp.port` (25) |
| `submission` | SMTP submission | `parley.mail.submission.port` (587) |
| `submissions` | submission with implicit TLS | `parley.mail.submissions.port` (465) |
| `imap` / `imaps` | IMAP / with implicit TLS | `parley.mail.imap.port` (143) / `parley.mail.imaps.port` (993) |
| `pop3` / `pop3s` | POP3 / with implicit TLS | `parley.mail.pop3.port` (110) / `parley.mail.pop3s.port` (995) |

A server with implicit TLS (`submissions`, `imaps`, `pop3s`) needs a TLS key store
(`parley.mail.tls.keyStore`, or `<prefix>.keyStoreName` for that server alone). 1.x's
`parley.mail.pop3.secure`, `parley.mail.imap.secure` and `parley.mail.smtp.secure` are no longer read
(a warning says so), and there is no relay with implicit TLS (`parley.mail.smtps`): RFC 8314's
implicit TLS is submission's.

Running servers of several protocols together (which run, what they share, how a failure is
handled) is an application's job. An application makes each server from its own configuration
(parley-core's `BaseObject.makeWith`), may give the servers that read one users file one list
(`getUsersFile()`, then `setAccessControl` before they start), and registers their beans while they
run. For an example that runs them together (one launcher with `parley.servers`, JMX management, a
web admin), see parley-mail-admin.

Each mail server is written as if it were the only server: it is made from a configuration of its
own and reads nothing else (not the system properties, not another server's settings), so nothing
changes when it shares a process with other servers. A server's own `main` reads its settings file
(`parley-smtp.properties`, `parley-imap.properties`, `parley-pop3.properties` in the settings folder,
`/etc/parley` or the folder of the file `PARLEY_CONF` names), the JVM's `-D` options and the command
line; an application running it builds its configuration as it chooses. A setting's name means
the same to every server; its value is each server's own. Settings every mail server reads (with no
prefix of its own):

| Setting | Meaning |
|---|---|
| `parley.mail.root` | The maildrop root (required: a server without one doesn't start) |
| `parley.mail.users` | The user file (parley-net's `FileBasedAcl` format) of every server that has no `<prefix>.authenticationProvider` of its own; read again when the file changes; each server reads it into a list of its own, unless the application running it gives it one (`getUsersFile()`, `setAccessControl`) |
| `parley.mail.tls.keyStore`, `.keyStorePassword`, `.keyStoreType` (PKCS12) | The TLS key store of every server that has no `<prefix>.keyStoreName` of its own |
A server's own settings use its prefix (`parley.mail.pop3`, `parley.mail.pop3s`, ...), with no
fallback from one prefix to another; its id among the servers sharing a store is
`<prefix>.serverId` (default: host, name and port).

**The store carries its own settings**: `.parley-store.properties` in the maildrop root, read by
every server that uses it, so they can't disagree:

| Store setting | Meaning |
|---|---|
| `sharing` | `off`: one server at a time (its claim refuses a second, in the same process or another); `host`: several servers on one machine; `cluster`: several anywhere. Servers in one process count as several servers: servers in one process sharing one root need `host` |
| `lockDomain` | The lock domain's name (default `mail`); the same for every server |

A store without the file isn't used: making it is an administrator's job
(`MailLocks.writeStoreSettings(root, Sharing.HOST)`).

While a server runs, its store is the JMX bean `us.bringardner.parley:type=MailStore,name=<id>`
(registered by whoever runs it: a server's own `main` does, through `MailServerMain`).

## Build

```
mvn package
```

Tests run with a 64 MB heap, so the large-message tests only pass if message bodies stay out of memory.

## Mailbox store (`us.bringardner.parley.mail.store`)

- **INBOX** is the user's POP3 maildrop: the directory named by the principal's `maildrop` parameter, or the user name, under the root. POP3 and IMAP see the same messages. A POP3 deletion appears to IMAP sessions as an expunge. Mail delivered into the maildrop appears in INBOX. New mail comes in one way, `MailStore.deliver(content, junk)` (SMTP's local delivery uses it): through `Mailbox.append`, which every new message file goes through, so it is stored with CRLF line ends and indexed.
- **Other mailboxes** are directories under `<inbox>/.mailboxes`; a child mailbox is a subdirectory of its parent's directory. Name segments are encoded so names stay case-sensitive and safe on any file system. For example, `Sent` is stored as `_sent` and `Über` as `%C3%9Cber`. POP3 ignores names starting with "." and directories, so it only sees INBOX.
- **New users** get Sent, Drafts, Trash, Junk and Archive, marked with their RFC 6154 special use and subscribed. The IMAP server turns this off with `parley.mail.imap.defaultMailboxes=false`.
- **Access control (RFC 4314):** each mailbox may have a hidden `.imap-acl` file, one line per identifier: the identifier, a TAB, its rights (`lrswipkxtea`); a negative entry is `-identifier`. A user is named by the full login address (`bob@bringardner.us`), a group by `$` and its full name (`$sales@bringardner.us`, as `IPrincipal.getGroups()` gives it), and `anyone` is every logged in user. A mailbox with no file gives only its owner every right. The owner (the user whose store it is) isn't written in the file unless the owner's own rights are set, so without an entry of its own the owner keeps every right, and users who share one maildrop each keep theirs; the owner always keeps `l` and `a`. Lines starting with `#` are comments, so an identifier can't start with one. The server's permissions are a ceiling: without READ nothing, without WRITE only `l` and `r`. RENAME takes the file along, DELETE removes it, CREATE copies the parent's and gives the creator every right. It is read and written through `MailStore` (`getAcl`, `setRights`, `deleteRights`, `rightsOf`; the format and calculation are `MailboxAcl`), under the user's tree lock, so IMAP and POP3 share it. The maildrop root's hidden `.imap-shares` file (`ShareIndex`) records which owners have given rights to which identifiers, so IMAP's Other Users namespace can find the mailboxes shared with a user; `MailStore` keeps an owner's lines up to date when its ACLs change (`setShareIndex`). An administrator changes them through `MailStoreAdminMXBean`, a running server's `getStoreAdmin()`, which whoever runs the server registers as `us.bringardner.parley:type=MailStore,name=<id>` while it runs (a server's own `main` does), so the changes take that server's locks on the store; its `forgetUser` takes a deleted user out of the other users' ACLs and the share index.
- **Index:** each mailbox has a hidden `.imap-index` file holding UIDVALIDITY, UIDNEXT, and each message's UID, flags and INTERNALDATE. It is replaced safely (temp file, then rename). UIDs are never reused. Message files themselves are never modified, except for the next point.
- **Line ends:** a message file found with bare LF line ends is rewritten once with CRLF, so the sizes IMAP reports are exact.
- **Sharing within a server:** sessions in the same process share one `Mailbox` object per directory (`MailboxRegistry`), so changes reach other sessions at once (EXISTS, EXPUNGE and FETCH FLAGS, which include UID). Changes made outside the server, such as POP3 or delivery, are found by rescanning the directory, at most once a second.
- **Several servers on one store** (the store's `sharing` setting): `off` means one server serves the root, and its claim on the store refuses a second, whether it is in the same process or another; `host` lets several servers on one machine share it, and `cluster` several on any machines. The locks come from parley-net (`us.bringardner.parley.net.lock`): the servers of a JVM use one lock domain for the maildrop root (`MailLocks`, named by the store's `lockDomain`, default `mail`), each with a claim of its own, kept the best way the store allows (its own locks on a local disk or PostgreSQL/MySQL, lease directories on FTP or SFTP; see parley-net's README for `parley.net.lock.provider` and the rules that keep servers from keeping one store's locks differently). With sharing on:
  - every change to a mailbox (APPEND, COPY, MOVE, EXPUNGE, a rescan) runs under its lock, after catching up with the index another server saved, and saves before the lock goes. Each save has a random id, checked with UIDVALIDITY, so a mailbox another server deleted and made again is never taken for the old one: sessions that have it selected are told to close it. The last UIDVALIDITY given is kept in the store (`.mail-uidvalidity` in the maildrop root, under the lock `uidvalidity`), so a mailbox made again by another server, or after a restart, gets a new one even within the same second.
  - UIDNEXT only goes up, so no UID is ever given twice; an APPEND whose index can't be saved takes its message back out, so a client that tries again doesn't get it twice.
  - flag changes are kept in memory and saved by the next change (or the next rescan, at most a second later), replayed on the latest index, so two servers' flag changes to one message are both kept.
  - MOVE within a store locks both mailboxes (in one order, so moves both ways can't wait for each other) and moves the files with their flags; a crash in between leaves each message in one mailbox. Across stores it copies, then expunges: a crash in between leaves a copy in both, never in neither.
  - CREATE, DELETE, RENAME and subscriptions run under the user's tree lock.
  - The index is replaced in one step where the store can (`atomicReplace`); elsewhere the previous one stands in as `.imap-index.old` while the new one goes in.
