# Changelog

## parley-mail 2.0.0 (unreleased)

### Added

- **Settings the suite shares**, used by every mail server that has none of its own: `parley.mail.users`
  (the user file, one `FileBasedAcl` per file in a JVM, read again when it changes;
  `AbstractMailServer.sharedUsers(File)`) and `parley.mail.tls.keyStore`, `.keyStorePassword`, `.keyStoreType`.
- `MailServerProvider`: the base of the mail servers' parley-net `ServerProvider`s; they read
  `parley-mail.properties` before their own settings file, and are off when their port is 0.
- A server refused the store because another server has it (`parley.mail.sharedStore=off`) says to set
  `parley.mail.sharedStore=host` for servers in separate processes on one host.

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
