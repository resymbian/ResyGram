# Changelog

## 0.2.12

### Added
- Auto-connect when already authorized: bypasses the setup "Connect" screen and automatically loads dialogs and connects.
- Working cancel button during video downloads via custom softkeys on BusyScreen.
- Count of deleted video files reported when running clean videos command.

### Fixed
- Video streaming: non-blocking `platformRequest()` check allowing RealPlayer streaming without falling back to full download.

## 0.2.10

### Fixed

- Added a 3.5-second timeout watcher to `Notifier.deliverTo`: prevents the worker thread and `post()` delivery queue from hanging permanently if NotiHelper or the socket bridge connection stalls during handshake or reply.

## 0.2.9

### Fixed

- Fixed critical UI freeze / infinite loop when rendering emoji sequences (such as
  the Spanish flag 🇪🇸 or keycaps) positioned past the start of a message.
  `tokenEnd` was returning the relative sequence length instead of advancing the
  character pointer, causing the wrap and render loops to jump backward and hang
  the application completely.
- Added strict forward-progress guards across all text layout and wrapping loops
  in `ChatScreen`, `RichText`, and `EmojiText` to guarantee the UI thread can
  never become trapped in an infinite loop.

## 0.2.8

### Added

- Keypad link selection: Pressing Fire / key 5 on a focused message with links
  opens the link picker list, allowing non-touch keypad phones (like Nokia E51)
  to choose and open links one by one in Opera Mini or the default browser.
- Expanded text emoji fallbacks: Added intuitive text replacements for over 70
  common Unicode emoji codepoints (smilies, gestures, hearts, objects) instead
  of the generic `[emoji]`.

### Changed

- All videos are now streamed sequentially via loopback HTTP (`VideoStreamer`),
  freeing phone RAM and internal memory from large video downloads.
- Notification bridge: Do not retry `localhost` if the bridge refused the note,
  preventing a harmless refusal from being overwritten by a DNS `-34` error.

## 0.2.7

### Added

- "Borrar vídeos" command in the root/chat-list menu to remove temporary video
  copies immediately, without waiting for resume.

## 0.2.6

### Changed

- Videos are no longer kept on the device. Small clips download into a private
  `ResyGram/Videos` folder and are removed when the client returns from the
  phone's viewer; clips over 6 MB are streamed from `upload.getFile` through a
  loopback HTTP server, so no copy is written at all. When Telegram offers
  several video formats, the smallest phone-playable one is used.
- The JADs now also request `javax.microedition.io.Connector.server`, needed by
  the loopback stream.

## 0.2.5

### Added

- An extended emoji sheet is loaded only when a message needs it. Flags,
  keycaps, ZWJ families and skin-tone variants are drawn as whole-sequence
  sprites, so wrapping can no longer split one emoji across lines. The core
  sheet still covers reactions and the picker, and plain text still decodes no
  PNG at all.

### Fixed

- Notifications try `127.0.0.1` and then the IAP-local `localhost` route, so an
  S60 stack that resolves loopback through its selected IAP can reach
  NotiHelper. Titles and texts are trimmed by encoded UTF-8 bytes to the
  bridge's limits, and Settings reports the actual failure instead of a
  generic "no bridge".
- Voice notes, round videos and videos keep their `DocumentRef` instead of
  becoming bare labels, so tapping flagged media reaches the attachment/save
  path rather than only opening reactions.
- A short tap on a URL, email, phone number or mention in message text now
  follows that entity. It uses the same visible confirmation and user-resolving
  path as the Actions menu, including spoiler concealment.

## 0.2.4

### Changed

- UI responsiveness: the activity spinner now repaints only the header instead
  of the whole canvas, string clipping uses a halving search instead of
  measuring every prefix, and compact dates are cached instead of creating a
  `Calendar` for every row of every frame.
- Chat avatars load in batches of four per worker round trip, repaint only the
  row they belong to, and are found in the RMS cache without rewriting the
  record or decoding its image bytes on every lookup.

### Added

- Test notification is available from Settings as well as the chat list menu,
  so the NotiHelper bridge can be checked before signing in.

### Fixed

- The Notifications switch in Settings is now saved, not just loaded.

## 0.2.3

Heat/battery fixes for the E51:

- The background activity pulse now repaints 2.5x less often (500 ms instead
  of 200 ms) and does nothing at all while `pauseApp()` is in effect or the
  current Canvas is not shown. A backgrounded MIDlet was repainting a screen
  nobody could see.
- `EmojiText.drawString()` only loads the sprite sheet when a message actually
  contains a mapped emoji. Plain text messages no longer touch the PNG.
- Sprite decode attempts are throttled to one per second and capped at three;
  `release()` adds a short cooldown so memory-pressure shed/reload cycles do not
  decode the sheet twice in the same paint.
- The atlas is trimmed from 195 to 176 glyphs: the newest faces, animals and
  nature are kept, while the long tail of objects is dropped. Decoded heap goes
  from ~210 KB to ~180 KB.

## 0.2.2

Fixes from the Nokia E51:

- The fullscreen chat and topic-list canvases no longer register a native
  `CommandListener` or native commands. On S60 that made the handset consume the
  soft keys before `keyPressed` could run the drawn menu, so the right key in a
  channel opened Options instead of leaving the channel.
- The emoji atlas grew from 150 to 195 single-codepoint glyphs, adding recent
  faces, animals, nature and objects seen in real chats. Flags and ZWJ sequences
  still use text fallbacks (`[flag]`, `[emoji]`): a one-sprite-per-codepoint
  atlas cannot draw them.
- A transient PNG decode failure no longer latches emoji off for the whole
  session; loading is retried up to three times.

## 0.2.1

Maintenance rebuild of the 0.2.0 code with a higher `MIDlet-Version`, so the
Nokia application manager accepts it as an upgrade instead of rejecting a
reinstall of the same version. No source changes.

## 0.2.0

What followed the first build: attachment handling both ways, and system
notifications through the native bridge.


### Added

- View and save attachments: documents, videos and voice notes download in
  bounded chunks to the phone's downloads folder and open in the phone's viewer;
  photos keep the in-app viewer.
- Send photos and files from the phone: a JSR-75 folder browser, chunked
  `upload.saveFilePart`/`saveBigFilePart`, then `messages.sendMedia`.
- Notifications through the native loopback bridge on 127.0.0.1:4711, with a
  Settings switch and a Test notification entry. New arrivals outside the open
  conversation are folded into one system note. The bridge is its own project,
  [NotiHelper](https://github.com/resymbian/NotiHelper), shared with Discofi.
- A compile-time JSR-75 stub so the optional package can be used without adding
  it to the artifact, and file read/write permissions in the JAD.


## 1.6.0

### Added

- Bounded community administration: capability-driven chat info, participant
  paging, invitations, kick/ban/unban, join/leave, promote/demote, granular
  administrator rights and default group permissions.
- Invite-link and join-request management, including request-needed links,
  paged pending requests and single-user approve/reject flows.
- Forum topic administration: create, rename, close/reopen, pin/unpin,
  General-topic visibility and confirmed non-General history deletion.
- An account/environment-bound RMS dialog index with versioned CRC records,
  atomic replacement, bounded streaming queries, eviction and partial/exact
  generation tracking for offline custom folders.
- A reversible live community E2E harness covering the normal and minified
  packaged MIDlets while preserving the selected fixture's original state.

### Changed

- Custom folders now apply Telegram's include/exclude/pinned precedence,
  preserve explicit pin ordering, keep pin membership independent from
  explicit includes, and treat shared folders as explicit read-only lists.
- Dialog, participant, invite and topic invalidations are reconciled through
  the ordered update path, with dirty-index recovery when a live update cannot
  be represented exactly.
- Personal folders support create, edit, delete and reorder while cached
  offline folders remain read-only.

### Fixed

- `400 INVITE_REQUEST_SENT` is handled as successful request-needed invite
  submission instead of leaving a real pending request behind an error alert.

### Testing

- 68 desktop Java suites and 64 tooling tests cover wire shapes, rights
  round-trips, capability matrices, folder semantics, RMS corruption and
  bounded UI behaviour.
- Normal and minified production JARs passed CLDC/MIDP audits, 32 MiB packaged
  smoke and a reversible MicroEmulator community flow against a private forum.
  No new physical-device validation is claimed for these features.

## 1.5.0

### Added

- Personal dialog folders, explicit include/exclude/pinned peers, folder
  ordering, dialog pinning and main/archive management.
- Exact packaged normal/minified folder E2E with reversible server mutations.

### Changed

- Dialog paging and update reconciliation retain folder identity and pinned
  state across live refreshes and cached rows.

## 1.4.1

### Added

- Touchscreen navigation for every application Canvas used by the messenger.
  A short tap repeats the focused `FIRE` action, a live pixel-tracked drag moves
  lists/history or pans a photo while the finger is moving, and a long press
  opens a native action list for the touched chat, topic, message, poll answer,
  reaction or photo. D-pad/numeric handling is unchanged.

### Changed

- Live drag frames use non-blocking content-region repaint requests. MIDP can
  coalesce them and paint the newest finger position instead of synchronously
  rendering stale intermediate positions; chat viewport/paging callbacks stay
  deferred until release.

### Testing

- Deterministic pointer tests cover tap/hold/drag classification, suppression
  of activation after a swipe, live partial-pixel repaint before release,
  Canvas hit testing, message activation, picker selection, text scrolling,
  photo zoom and continued d-pad/FIRE behaviour.
- Basic tap, hold and swipe navigation was exercised on a Fly E190 Wi-Fi.
  Scrolling is functional there, though its vendor VM still renders live drags
  noticeably slower and less smoothly than desired.

## 1.4.0

### Added

- Incoming ordinary, multiple-choice, quiz and closed polls render inline and
  open into a keypad-driven radio/checkbox picker. `messages.sendVote` sends
  opaque option tokens, while `updateMessagePoll` supplies the authoritative
  choice, totals, quiz correctness and solution without a manual refresh.
- A two-session `tools/poll-e2e.ps1` scenario creates a uniquely marked poll in
  the prepared group, drives normal and minified packaged JARs through voting
  and an unsolicited revote, and retains screenshots plus the final test poll.

### Changed

- History cache v5 retains poll definitions, opaque answer tokens, choices,
  totals and quiz results. The reader accepts v1-v5; a downgrade may discard
  v5 history and fetch it again without touching auth, outbox or drafts.

### Testing

- Poll parsing, wire encoding, update sync, UI and cache coverage pass in the
  desktop suite. Normal and minified packaged JARs pass emulator smoke and the
  two-account live poll flow; voting was also verified on a Nokia E6-00.

## 1.3.0

### Changed

- The project and MIDlet suite are renamed from TelegramJ2ME to J2MEgram to
  comply with the [Telegram API Terms of Service](https://core.telegram.org/api/terms),
  which forbid the word "Telegram" in an app title. The JAD/manifest
  description becomes "Unofficial Telegram J2ME Client", release artifacts are
  now named `J2MEgram-<version>[-min]`, and the `initConnection` app_version
  shown in Telegram's "Active sessions" reads `J2MEgram <version>`.
- Because MIDlet suite identity is Name plus Vendor, a J2MEgram build installs
  alongside an existing TelegramJ2ME install instead of upgrading it in place:
  sign in again in the new install, then remove the old suite manually.
- The generated Telegram API schema now targets layer 225, pinned to the final
  official Telegram Desktop layer-225 TL commit and converted to JSON offline.
  The public JSON endpoint's exact older hash is monitored without allowing an
  unknown schema change to pass.
- Exact packaged E2E accepts current semantic-version artifact names. The live
  forum gate deletes and verifies both probe messages even after a failed run.

### Testing

- Live production config, dialogs, reactions and forum flows pass at layer 225.
  Normal and minified packaged JARs also pass the two-account flow under both
  ordinary and fragmented slow-network transport, with server cleanup
  confirmed after every run.

## 1.2.0

### Added

- Incoming message formatting (issue #18): bold, italic, underline,
  strikethrough, inline code, preformatted blocks and block quotes are laid
  out with bounded MIDP fonts while preserving UTF-16 and emoji boundaries.
- Lightweight spoilers use a static dotted bitmap mask with no animation or
  timer. A focused concealed message exposes a `Reveal spoiler` command, and
  revealing it changes paint state without reflowing the transcript.
- A live `forumspoiler` emulator scenario covers a spoiler containing an emoji
  and a link in a real forum topic, including the dialog list, topic list,
  concealed transcript, reveal action and post-reveal link action.

### Changed

- Visual entities may overlap each other and actionable entities; styles are
  combined, while only invalid UTF-16 ranges and exact duplicates are dropped.
  Entity retention remains bounded and fails closed by concealing the whole
  message if its spoiler count exceeds the cap.
- Links, mentions, email addresses and phone numbers overlapping a concealed
  spoiler are unavailable until the message is revealed.
- Dialog and topic previews, full-text views, replies, forwards and search
  representations redact concealed ranges before clipping or caching them.

### Fixed

- Spoiler text no longer leaks through the dialog list or forum topic list.
  Verified in a real topic and in the chat list on a physical Nokia E6-00.

### Compatibility notes

- Dialog cache is v3. Older v1/v2 dialog records stored only flattened preview
  text and are deliberately discarded because their missing entities make old
  spoiler previews impossible to conceal safely; dialogs are fetched again.
  History, auth keys, outbox, drafts and update cursors are unchanged.
- Formatting is incoming-only. LaTeX, custom emoji, animated spoiler particles,
  collapsed block quotes and formatted-message composition remain out of scope.

## 1.1.0

### Added

- Forum supergroups open as a topic list (issue #17): a bounded, windowed
  topic screen between the chat list and the transcript, per-topic history
  via `messages.getReplies`, sends that write `top_msg_id`, per-topic read
  cursors via `messages.readDiscussion`, unread badges, closed/hidden
  markers, live row updates, and in-topic message search.
- Channel comments: posts show their comment count, a `Comments` action opens
  the linked discussion thread, and replies can be sent into it.
- The open transcript's identity is `(peer, thread)` end to end: staleness
  guards, read marks, the acknowledgement queue, local read reconciliation,
  the composer, drafts, and the outbox all carry the thread beside the peer.
- Live per-thread read sync from other devices
  (`updateReadChannelDiscussionInbox`), and channel pts seeding from thread
  responses so discussion groups outside the dialog window do not loop on
  snapshot recovery.
- A `forum` live scenario (`./tools/live.ps1 forum`) driving the whole
  feature end to end against a prepared account.

### Changed

- Outbox records are v3 (the thread survives a power cut before the first
  send; `random_id` is still never regenerated), drafts are envelope v2
  (keyed by peer and thread), history cache is v4 (keyed by peer and thread,
  carrying the reply-header thread facts), and the dialog cache is v2
  (carrying the forum flag so an offline forum opens as its topic list).
  Every legacy format is still read as thread 0.
- The navigation stack floor is five screens: chat list, topic list,
  transcript, photo, over the root.

### Fixed

- Sending a reply in a forum no longer lands outside every topic: the topic
  root travels in `inputReplyToMessage.top_msg_id`.
- Plain topic messages no longer carry a "Reply to" caption naming the
  topic's root service message; the caption stays on real replies. Verified
  on a physical Nokia E6-00.

### Compatibility notes

- Downgrading discards history-cache v4 and dialog-cache v2 records; they are
  refetched. Auth keys, outbox, drafts, and update cursors are unaffected.
- Topic management (create/close/pin) is out of scope; forwarding into a
  forum lands in General.

## 1.0.1

### Added

- Live foreground updates for open chats and the dialog list, backed by
  `updates.getDifference` recovery and a 30-second safety audit.
- Compact `online/live`, degraded retry, and `+N new` status indicators that
  preserve the reader's viewport and current dialog selection.
- Update diagnostics with the last delivery source, update age, next retry or
  audit, and bounded queue size.

### Fixed

- A temporary common or channel recovery failure no longer silently disables
  the MTProto push loop; retries are isolated and use bounded backoff.
- Optimised builds no longer let ProGuard synthesize CLDC-incompatible
  `Integer.valueOf(int)` and `Long.valueOf(long)` calls. The packaged class tree
  is now API-checked after optimisation as well as before it.

### Verified

- Foreground live delivery, dialog updates, pause/resume catch-up, and recovery
  from degraded state on a physical Nokia E6-00.
- The CLDC incompatibility was reproduced from a physical Nokia C3-00 failure;
  the corrected packaged class tree passes the post-optimisation API gate.

## 1.0.0-rc1

Release candidate; not tagged or published until the Nokia C3-00 upgrade run.

### Added

- Bounded in-chat server-side message search with paged results and
  history-around opening.
- Full-message text view and bounded parsing/detection of URL, text URL,
  username, mention-name, phone, and email entities.
- Bounded URL, email, phone, and username entities are also attached to new
  and edited outgoing text so Telegram preserves the same safe actions.
- Confirmed, validated external actions with explicit platform-launch outcomes.
- Editing of eligible own outgoing text, live edit updates, an `edited` label,
  and history-cache v3.
- Deterministic 1.0 failure matrix, stability gate, and evidence template.

### Changed

- History cache reads v1/v2/v3 and writes v3; dialog cache remains v1.
- Release metadata is `MIDlet-Version: 1.0.0` while preserving the existing
  production suite name and vendor for an in-place 0.8.1 upgrade.
- Public-looking `TelegramJ2ME-*` builds refuse embedded development report or
  proxy secrets.

### Compatibility notes

- Downgrading to 0.8.1 may discard history cache v3.
- Telegram's live config reports layer 225 while the pinned public JSON schema
  remains layer 223; the client intentionally continues to advertise 223.
- New emulator and Nokia C3-00 rows remain `NOT RUN` until their exact RC gates
  complete.

## 0.8.1

Baseline production release for the 1.0 upgrade path. See the Git history and
GitHub release notes for earlier changes.
