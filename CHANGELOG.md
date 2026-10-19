# Changelog

## parley-mail 1.1.0 (unreleased)

### Added

- **Several servers on one store** (`parley.mail.sharedStore` = `off`, `host` or `cluster`; default `off`), on
  parley-net 1.1.0's locks: the servers of a JVM share one lock domain per maildrop root (`MailLocks`); a server
  claims the store when it starts (`AbstractMailServer.start()`), alone or shared, and is refused if another's
  claim doesn't allow it. Mailbox changes, MOVE, CREATE/DELETE/RENAME and subscriptions run under locks that reach
  the other servers; a mailbox catches up with the index another server saved before it changes it.
- `AbstractMailServer.storeClaimed()` and `releasingStore()`: hooks for work on the store (the SMTP queue, say)
  that must start after the claim and stop before it is given up.
- `MailboxView.Updates.uidValidityChanged`: another server deleted and made the mailbox again; the session must
  close it.

### Changed (no code change needed)

- The index has a `VERSION` line (a save count and a random save id). Earlier versions ignore it.
- UIDNEXT never goes down, so a UID is never given twice, even after a save that failed.
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
