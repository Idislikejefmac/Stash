# 21 · Barcodes and QR codes

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

A barcode or QR code can be scanned into a note, found again by its value, and recognised when scanned a second time ("You noted this one on 3 November at Total Wine"). The app stores what the code says and never acts on it.

## Depends on

16 (attachments, the in-app camera, the annotate screen). Search checks need 08.

## Context

Scanning is done on the phone with **ML Kit Barcode Scanning**, model **bundled** (about 2.4 MB; the other packaging is about 200 KB and fetches the model through Google Play services). It reads the common product codes (EAN-13, EAN-8, UPC-A, UPC-E, Code 39/93/128, ITF, Codabar) and the 2D ones (QR, PDF417, Aztec, Data Matrix), from the live camera or from a still picture, and parses some payloads (links, Wi-Fi settings, contacts, driving licences). The same terms apply as for text recognition: images are processed on the phone; usage figures go to Google. (Google's pages, read 2026-11-11 by a research helper.) An open alternative with no reporting to Google exists (zxing-cpp, Apache 2.0); no trustworthy accuracy comparison was found, so it is the fallback if the owner objects to the reporting, not the default.

What a code can hold, and why it matters:

| Code | Holds | Risk |
| --- | --- | --- |
| Product barcode | a product number | none in itself; looking it up online tells someone what he scanned |
| Link | a web address | phishing; tracking addresses. The US consumer regulator warns that scammers hide harmful links in QR codes |
| Wi-Fi | network name and **password in clear** | a credential |
| Sign-in set-up (`otpauth://`) | the **secret** for a two-step sign-in | a credential |
| Boarding pass, ticket | name and booking reference, readable by anyone (medium confidence: secondary sources) | identity; the booking can be managed with it |
| Payment | account details and an amount | money |
| Loyalty card, receipt | account or member number | identity |

## Scope

### A. Storage

1. Table `code_scan` (a migration and migration test): `id`, `capture_id`, `attachment_id`? (the picture it was found in, if one was kept), `symbology` (as ML Kit names it), `raw_payload` (exactly as decoded), `payload_hash`, `parsed_type` (`PRODUCT`, `URL`, `WIFI`, `PAYMENT`, `CONTACT`, `TICKET`, `SIGN_IN`, `LOYALTY`, `TEXT`, `OTHER`), `parsed_json`?, `created_at`, `updated_at`.
2. `domain/CodeParser.kt` (pure): the parsed type from the symbology and the payload's shape. Numeric EAN/UPC → `PRODUCT`; `http(s)://` → `URL`; `WIFI:` → `WIFI`; `otpauth:` → `SIGN_IN`; PDF417 or Aztec beginning with the boarding-pass format's leading `M1`/`M2` → `TICKET`; payment formats recognised by their headers → `PAYMENT`; `BEGIN:VCARD`/`MECARD:` → `CONTACT`; else `TEXT` or `OTHER`.
3. **Personal codes default to Private.** `WIFI`, `SIGN_IN`, `TICKET`, `PAYMENT`, `LOYALTY` and `CONTACT` set the capture's `sensitive` mark with reason `PERSONAL_CODE`. The scan screen says so with the explanation-card line ("Marked private because it looks like a boarding pass · Keep private · It isn't").
4. The capture's search document includes `raw_payload` for `PRODUCT`, `URL` and `TEXT` codes only. For personal types the payload is searchable only after the second unlock.

### B. Scanning

5. `CameraActivity` gains a **Scan** mode (CameraX with ML Kit's analyser). On a decode: vibrate once, freeze, and show the result panel. No picture is kept unless he taps "Keep the picture too".
6. **Codes inside pictures:** every picture saved by card 16 is also passed through the barcode scanner once, in the background. A code found becomes a `code_scan` linked to that attachment, with the same Private default.
7. **Result panel**, by type, always showing the decoded text in full:
   - `PRODUCT`: the number, and the known-code line if any (section C). Buttons: **Add to a note** (the annotate screen from card 16, "Anything to add about this?").
   - `URL`: the full address as selectable text, with the site's name in bold so a look-alike is visible. Buttons: **Add to a note** · **Copy**. A separate, smaller **Open in browser…** shows a confirmation naming the destination before handing it to the browser. Nothing is ever fetched by Stash and nothing opens without that second tap.
   - `WIFI`, `PAYMENT`, `SIGN_IN`: shown as text, password or secret hidden behind "Show". **Add to a note (private)**. No "connect", no "pay", no "add account": the app never acts on these.
   - `TICKET`, `LOYALTY`, `CONTACT`: the readable fields. **Add to a note (private)**.
8. Capability `CODES` (needs Camera for live scanning; none for codes found in shared pictures).

### C. "You have seen this before"

9. On every decode, look up `payload_hash`. If found (and not private, or the second unlock is open): "You noted this on 3 November at Total Wine: 'Allegrini Valpolicella, row 5'". Tap opens the note. This is a lookup in his own notes, on the phone.
10. Router (card 08): no new question patterns. A scanned value pasted or spoken as digits is found by full-text search.

### D. Product lookup (capability `PRODUCT_LOOKUP`, default off)

11. `Outbound.PRODUCT_LOOKUP` in card 13's register. The first time a `PRODUCT` code is scanned, the result panel offers the explanation card ("Look up products by barcode… sends the barcode number to Open Food Facts…"). Off unless he turns it on.
12. When on: one GET to Open Food Facts for that number, from a new `lookup/net/` package added to card 13's network allow-list, with a generic app name in the required User-Agent and **no contact address that identifies the owner**. The returned name is stored as a suggestion on the note ("Looked up: …"), never as his words. Their limit is 15 lookups a minute per address; one per scan stays far inside it. Food and household products only; anything else returns nothing, and the panel says "Not found. Name it yourself."
13. Never for a sensitive capture.

## Out of scope

Acting on any code (joining Wi-Fi, paying, adding a sign-in, importing a contact, adding a pass to a wallet). Generating codes. Price comparison. A general product database beyond the one lookup. Google's permission-free code scanner (it lives in Play services, returns no picture, and cannot run over the lock screen; noted as an option for a lighter product tier that will not ask for the camera).

## Acceptance checks

- **Unit:** `CodeParser` table: an EAN-13, a UPC-A, an `https://` link, a look-alike link, a `WIFI:` string, an `otpauth://` string, a boarding-pass sample, a vCard, plain text.
- **Unit:** personal types set the Private mark; product, link and text types do not.
- **Unit:** the result panel model for `URL` has no action that opens anything without the confirmation step; for `WIFI`/`PAYMENT`/`SIGN_IN` it has no action other than saving and copying.
- **Unit:** known-code lookup finds a previous scan; does not reveal a private one before the second unlock.
- **Unit:** lookup is never called with the capability off, and never for a sensitive capture; the request's User-Agent contains no email address.
- **Unit:** a seeded picture containing a QR code produces a linked `code_scan` after the background pass.
- **Real phone, S25 Edge (RISKS.md R32):** scan 20 product barcodes in a shop (curved bottles, shiny packets, poor light) and 5 QR codes. Record how many decoded within 2 seconds, and how many needed a second try. **Pass:** 18 of 20 and 5 of 5.
- **Real phone:** scan a Wi-Fi QR code: the password is hidden until "Show", the note is private, and the phone does not join the network. Scan a link: nothing opens until the second tap. Scan a bottle already noted: the "you noted this" line appears.
- **Real phone:** scan a boarding pass or an event ticket of your own: is it recognised and marked private? Write down what the app showed.

## Notes and risks

- The boarding-pass and payment recognisers are best-effort. A personal code that is not recognised is stored as ordinary text; the scan panel therefore always offers "Make private", and `docs/PRIVACY.md` says the recognition can miss.
- Do not log payloads. `Dbg.event` takes the parsed type and the symbology only.
- ML Kit barcode scanning is a new dependency; give the APK size before and after.
