# Changelog

## parley-mail 2.0.0 (unreleased)

### Changed (2026-11-02: To-Do 3.6)

- **`Mailbox.copyTo(target, list, keepFlag)` and `moveTo(target, list, source, keepFlag)`**: the copies are made with
  only the flags the filter allows (IMAP's COPY and MOVE, by the rights on the target).
- **The mail store's management bean is named by the server's id in the launcher**
  (`us.bringardner.parley:type=MailStore,name=imaps`, as its `Server` bean), from parley-net's
  `ServerContext.getServerIdBeingMade()`; by the server's name when it was made without a launcher. It was named by the
  server's class (`name=ImapServer`, `instance=2` for imaps).

### Added (2026-11-02: mailbox ACLs, To-Do 3.4)

- **Per-mailbox access control lists (RFC 4314)** in a `.imap-acl` file in each mailbox's folder: `MailboxAcl` (the
  file format, rights and their calculation) and `MailStore.getAcl`, `getEffectiveAcl`, `setRights`, `deleteRights`,
  `rightsOf`. `new MailStore(inbox, registry, owner)` names the owner, whose mailboxes without a file give the owner
  every right. `MailStore.create(name, specialUse, creator)` copies the parent's ACL and gives the creator every right;
  RENAME moves an ACL with its mailbox (renaming INBOX copies INBOX's), DELETE removes it.
- **`MailStoreAdminMXBean`** (`MailStoreAdmin`): a mail store's ACLs for an administrator (parley-mail-admin):
  a user's mailboxes, their ACLs, SETACL and DELETEACL, and `forgetUser` for a deleted user. Every mail server
  made in a launcher registers one as `us.bringardner.parley:type=MailStore,name=<server>` while it runs, so
  changes take that server's locks on the store. `MailStore.removeIdentifier` and `ShareIndex.remove` behind it.
- **`ShareIndex`**: the maildrop root's `.imap-shares` (owner TAB identifier), which owners have shared mailboxes with
  which users, groups or anyone, kept up to date by `MailStore` (`setShareIndex`) for IMAP's Other Users namespace.

### Changed (2026-11-02: parley-net's IPrincipal split, To-Do 3.2)

- **`SaslPrep` moved to parley-net** (`us.bringardner.parley.net.sasl.SaslPrep`).

### Changed (2026-11-02: durations, To-Do 2.19)

- **`<prefix>.autologout` is a duration** (`10m`, `30m`, `90s`; a bare number is still milliseconds), read with
  parley-core's `getDuration`. A value of 0 or less gives the server's default.
- The shared user list's reload setting is parley-net's `parley.net.acl.reloadInterval` (a duration; was
  `reloadSeconds`), so servers whose reload interval differs get lists of their own, as before.

### Changed (2026-11-01: parley.servers says which servers run, To-Do 2.12)

- **A port is only a port**: `MailServerProvider` (new constructor with the server's prefix, standard port and whether
  it has implicit TLS) gives every server its standard port when `<prefix>.port` isn't set, so `submissions`, `imaps`,
  `pop3s` and parley-ftp's `ftps` are on 465, 993, 995 and 990 (they were off, port 0, unless set). Which servers run is
  what `parley.servers` lists. Port 0 no longer turns a server off: it is skipped with a warning to leave it out of
  `parley.servers`.
- **A server with implicit TLS and no key store is skipped with a warning** (`unavailable`: neither
  `parley.mail.tls.keyStore` nor `<prefix>.keyStoreName`), so the packaged list can name the TLS servers before a key
  store is set. `MailServerProvider.hasKeyStore`, `obsoleteSettings()` (warnings for settings 2.0.0 doesn't read).

### Changed (2026-10-26: each server alone, shared assets carry their own settings)

- **Each mail server reads only its own configuration** (`AbstractMailServer.makeIn(context, configuration, make)`),
  never the system properties when it has one, and nothing changes when servers share a process.
- **The store carries its own settings**: `.parley-store.properties` in the maildrop root (`sharing`: off, host or
  cluster; `lockDomain`), read by every server that uses it. It replaces the settings `parley.mail.sharedStore`
  and `parley.mail.lockDomain`, which are no longer read. A store without it isn't used (`MailLocks.writeStoreSettings`).
  Servers in one process count as several servers: the mail suite's servers on one root need `sharing=host`.
- **`parley.mail.root` is required**: the default `/pop3` (`C:/pop3`) is gone, and a server without one doesn't start.
- **A server's store id** is its own `<prefix>.serverId` (was the JVM-wide `parley.mail.serverId`).

### Added

- **The settings this module reads are listed** in `META-INF/parley/settings.list`, so a Parley server's
  start-up check (parley-net's `KnownSettings`, To-Do 2.16) knows them and warns only about a setting
  nothing reads (and parley-mail-admin's `parley.mail.admin.*`, which share `parley-mail.properties`). A test
  checks the list has every name the code here reads.
- **Settings the suite shares**, used by every mail server that has none of its own: `parley.mail.users`
  (the user file, read again when it changes; one `FileBasedAcl` per file for the servers of one launcher,
  in its `ServerContext`, made through `AbstractMailServer.makeIn` and found with
  `AbstractMailServer.sharedUsers(ServerContext, File)`) and `parley.mail.tls.keyStore`, `.keyStorePassword`, `.keyStoreType`.
- `MailServerProvider`: the base of the mail servers' parley-net `ServerProvider`s; they read
  `parley-mail.properties` before their own settings file, and are off when their port is 0.
- A server refused the store because another server has it (`parley.mail.sharedStore=off`) says to set
  `parley.mail.sharedStore=host` for servers in separate processes on one host.
  With sharing on here, a refusal says the server that has the store must share it too.

### Changed (needs a configuration change)

- **The maildrop root every mail server shares** is `parley.mail.root` (with `parley.mail.fileSource`):
  `AbstractMailServer.MAIL_ROOT_PROP` and `MAIL_FILE_SOURCE_PROP`. It replaces `JPop3.root`, `JImap.root` and
  `JSmtp.root` (Parley 2.0.0; the old names are no longer read).

## parley-mail 1.1.0 (unreleased)

### Added

- `MailStore.deliver(content, junk)`: the one way new mail gets into a store (SMTP's local delivery, or code putting
  mail into a user's mailbox): to INBOX or the Junk mailbox through `Mailbox.append`, with CRLF line ends and an
  index entry. `MailStore.junkMailbox()` (was in parley-smtp's LocalDelivery).
- **Several servers on one store** (`parley.mail.sharedStore` = `off`, `host` or `cluster`; default `off`), on
  parley-net 1.1.0's locks: the servers of a JVM share one lock domain per maildrop root (`MailLocks`); a server
  claims the store when it starts (`AbstractMailServer.start()`), alone or shared, and is refused if another's
  claim doesn't allow it. Mailbox changes, MOVE, CREATE/DELETE/RENAME and subscriptions run under locks that reach
  the other servers; a mailbox catches up with the index another server saved before it changes it.
- `AbstractMailServer.storeClaimed()` and `releasingStore()`: hooks for work on the store (the SMTP queue, say)
  that must start after the claim and stop before it is given up.
- `Mailbox.expunge(uids, view, uidValidity)`: removes nothing if the mailbox was recreated since the UIDs were read
  (the two-argument form uses the UIDVALIDITY when called).
- `MailboxView.Updates.uidValidityChanged`: another server deleted and made the mailbox again; the session must
  close it.

- SCRAM logins for the mail servers: `AbstractMailProcessor.supportsScram(hash)`, `getScramCredentials(user, hash)`
  and `verifiedPrincipal(user)`, on parley-net 1.1.0's access control list (user names prepared with SASLprep).
- A tests jar (`parley-mail-<version>-tests.jar`) with what the mail protocols' tests share, so they never copy it
  or depend on each other: `MailTestSupport` (the test user file `MailTestAcl.txt`, access control lists for
  SCRAM and for a store that is down, `deleteAll`, `scratchFile`).
- `AbstractMailProcessor.checkLogin(user, password)`: like `authenticate`, but throws
  `AccessControlUnavailableException` when the access control list can't answer (its database is down), so a
  server can say "try again later" instead of "wrong password".

### Changed (no code change needed)

- A MOVE that can't rename a file into the target writes the copy as `append` does (one place writes every new
  message file); the whole move is still saved, or undone, at once.
- The index has a `VERSION` line (a save count and a random save id). Earlier versions ignore it.
- UIDNEXT never goes down, so a UID is never given twice, even after a save that failed.
- A new UIDVALIDITY is always above the last one given in the store (kept in `.mail-uidvalidity`), so a mailbox
  deleted and made again by another server in the same second is still seen as new.
- An APPEND whose index save fails takes its message back out (a retry used to get it twice).
- Temporary files (index, CRLF rewrites, surrogates, subscriptions) have names of their own, so two writers
  never write into one; the index and other files are replaced in one step where the store can.

## parley-mail 1.0.0 (2026-10-16)

The message model and mailbox store of BjlEmail (`us.bringardner:bjl_email` 1.0.0-SNAPSHOT, never
released) are now **parley-mail**, part of the Parley library family. The SMTP, IMAP and POP3
code moves to `parley-smtp`, `parley-imap` and `parley-pop3`, which build on this module.

### Changed (needs a code change)

- Packages: `us.bringardner.net.email` is now `us.bringardner.parley.mail`, and
  `us.bringardner.net.imap.server.store` is now `us.bringardner.parley.mail.store`.
- The store's constants moved from `us.bringardner.net.imap.IMAP` to
  `us.bringardner.parley.mail.store.MailboxConstants` (`DELIMITER`, `INBOX`, `SENT`, `DRAFTS`,
  `TRASH`, `JUNK`, `ARCHIVE` and the `CODE_*` response codes), with the same values, so the store
  no longer depends on IMAP.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.mail`.
- Dependencies: only `parley-files` (and through it `parley-core` and `parley-io`); no DNS or
  network framework.

### Unchanged

- Mailboxes on disk: same directory layout, name encoding and `.imap-index` format
  (header `BJLIMAP 1`), so existing mail stores keep working.
