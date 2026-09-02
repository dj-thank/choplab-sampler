# H16 Windows package receipt — 2026-09-01

## Fixed source

- Branch candidate: `codex/choplab-h13-windows-package-20260901`
- Source HEAD: `0dcf618ac09b2a5a596019895fe4159eee44d520`
- Source tree: `4fd89db62152a6495685023693334ed2396f220f`
- Tested H13 implementation: `1f96ef8db2e6f55efb7b8764900293338e70fd2d`
- Independently reviewed H13 successor: `8cdc09a6d5c0461b377a61288484be7ff8445b02`

This is historical, source-bound local evidence for the H13 candidate before GitHub integration. It is not evidence for the later `v0.17.1` merge or Release bytes.

## Package result

- Existing JDK `17.0.20` and Gradle `9.7.1`; offline `:desktop:installDist` passed in 14 executed tasks.
- Direct `jpackage --type app-image` produced 405 files / 79 directories / 176,783,975 bytes.
- Main JAR SHA-256: `2873AB41B8A88D0D006714B8C922B2B373A9C92BB8E0599B4008337FE9516580`.
- Packaged H13 controller class SHA-256: `57F1116CE7128E8B67D98727BAD2BCEF3C3053EC0BE224EE507A752CD049CE19`; it matched the accepted H13 compile.
- `ChopLab.exe`: 449,024 bytes, ProductVersion `0.17.0`, Authenticode `NotSigned`, SHA-256 `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Portable ZIP: 89,182,337 bytes, SHA-256 `1E4A5541B5FD233B4A9C55B1BD601EE307BF2E4BB7760DB5317C8E4AFDA247DA`; all 405 app-image files matched after streamed extraction verification.
- Credential-shaped hits in the owned main JAR/config: 0. Forbidden source/test/private/media paths: 0.

The ZIP, app-image, caches, machine profile, and raw build logs are deliberately not committed to Git. They were not uploaded, installed, signed, or treated as a public Release.

## Gate

`LOCAL_BUILD` / package verification only. This receipt does not establish startup, physical audio, OS pointer delivery, accessibility speech, provider behavior, signing, publication, or Human acceptance.
